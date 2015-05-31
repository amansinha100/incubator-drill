/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.drill.exec.physical.impl.window;

import org.apache.drill.common.exceptions.DrillException;
import org.apache.drill.exec.exception.SchemaChangeException;
import org.apache.drill.exec.physical.impl.sort.RecordBatchData;
import org.apache.drill.exec.physical.impl.window.WindowFrameRecordBatch.WindowVector;
import org.apache.drill.exec.record.TransferPair;
import org.apache.drill.exec.record.VectorAccessible;
import org.apache.drill.exec.record.VectorContainer;
import org.apache.drill.exec.record.VectorWrapper;
import org.apache.drill.exec.vector.BigIntVector;
import org.apache.drill.exec.vector.Float8Vector;
import org.apache.drill.exec.vector.ValueVector;

import javax.inject.Named;
import java.util.Iterator;
import java.util.List;


public abstract class DefaultFrameTemplate implements WindowFramer {
  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(DefaultFrameTemplate.class);

  private VectorContainer container;
  private List<RecordBatchData> batches;
  private int outputCount; // number of rows in currently/last processed batch

  private List<WindowVector> winvecs;

  /**
   * current partition being processed. Can span over multiple batches, so we may need to keep it between calls to doWork()
   */
  private Partition partition;

  @Override
  public void setup(List<RecordBatchData> batches, VectorContainer container, List<WindowVector> winvecs) throws SchemaChangeException {
    this.container = container;
    this.batches = batches;

    outputCount = 0;
    partition = null;

    logger.debug("winvecs.size = {}", winvecs.size());
    this.winvecs = winvecs;

    setupOutgoing(container);
  }

  /**
   * processes all rows of current batch:
   * <ul>
   *   <li>compute window aggregations</li>
   *   <li>copy remaining vectors from current batch to container</li>
   * </ul>
   */
  @Override
  public void doWork() throws DrillException {
    logger.trace("WindowFramer.doWork() START, num batches {}, current batch has {} rows",
      batches.size(), batches.get(0).getRecordCount());

    final VectorAccessible current = batches.get(0).getContainer();

    // we need to store the record count explicitly, in case we release current batch at the end of this call
    outputCount = current.getRecordCount();

    int currentRow = 0;

    while (currentRow < outputCount) {
      if (partition != null) {
        assert currentRow == 0 : "pending windows are only expected at the start of the batch";
        // we have a pending window we need to handle from a previous call to doWork()
        logger.trace("we have a pending partition {}", partition);
      } else {
        // compute the size of the new partition
        final int length = computePartitionSize(currentRow);
        partition = new Partition(length);
        resetValues(); // reset aggregations
      }

      currentRow = processPartition(currentRow);
      if (partition.isDone()) {
        partition = null;
      }
    }

    // transfer "non aggregated" vectors
    for (VectorWrapper<?> vw : current) {
      ValueVector v = container.addOrGet(vw.getField());
      TransferPair tp = vw.getValueVector().makeTransferPair(v);
      tp.transfer();
    }

    //TODO we only need to do this for the "aggregated" vectors
    for (VectorWrapper<?> v : container) {
      v.getValueVector().getMutator().setValueCount(outputCount);
    }

    // because we are using the default frame, and we keep the aggregated value until we start a new frame
    // we can safely free the current batch
    batches.remove(0).clear();

    logger.trace("WindowFramer.doWork() END");
  }

  /**
   * process all rows (computes and writes aggregation values) of current batch that are part of current partition.
   * @return index of next unprocessed row
   * @throws DrillException if it can't write into the container
   */
  private int processPartition(int currentRow) throws DrillException {
    logger.trace("process partition {}, currentRow: {}, outputCount: {}", partition, currentRow, outputCount);

    // when computing the frame for the current row, keep track of how many peer rows need to be processed
    // because all peer rows share the same frame, we only need to compute and aggregate the frame once
    for (; currentRow < outputCount && !partition.isDone(); currentRow++) {
      if (partition.isFrameDone()) {
        partition.newFrame(countPeers(currentRow));
        aggregatePeers(currentRow);
      }

      outputAggregatedValues(currentRow);

      //TODO investigate how we could generate this computation unit to avoid for/switch
      {
        for (WindowVector wv : winvecs) {
          //TODO move the computation inside WindowVector and pass the min amount of information needed
          switch (wv.func) {
            case ROW_NUMBER:
              ((BigIntVector) wv.vector).getMutator().setSafe(currentRow, partition.rowNumber);
              break;
            case RANK:
              ((BigIntVector) wv.vector).getMutator().setSafe(currentRow, partition.rank);
              break;
            case DENSE_RANK:
              ((BigIntVector) wv.vector).getMutator().setSafe(currentRow, partition.denseRank);
              break;
            case PERCENT_RANK:
              ((Float8Vector) wv.vector).getMutator().setSafe(currentRow, partition.percentRank);
              break;
            case CUME_DIST:
              ((Float8Vector) wv.vector).getMutator().setSafe(currentRow, partition.cumeDist);
              break;
          }
        }
      }

      partition.rowAggregated();
    }

    return currentRow;
  }

  /**
   * @return number of rows that are part of the partition starting at row start of first batch
   */
  private int computePartitionSize(final int start) {
    logger.trace("compute partition size starting from {} on {} batches", start, batches.size());

    // current partition always starts from first batch
    final VectorAccessible first = getCurrent();

    int length = 0;

    // count all rows that are in the same partition of start
    // keep increasing length until we find first row of next partition or we reach the very
    // last batch
    for (RecordBatchData batch : batches) {
      final VectorAccessible cont = batch.getContainer();
      final int recordCount = cont.getRecordCount();

      // check first container from start row, and subsequent containers from first row
      for (int row = (cont == first) ? start : 0; row < recordCount; row++, length++) {
        if (!isSamePartition(start, first, row, cont)) {
          return length;
        }
      }
    }

    return length;
  }

  /**
   * find the limits of the window frame for a row
   * @param start first row of frame we want to count
   * @return frame interval
   */
  private int countPeers(final int start) {

    // using default frame for now RANGE BETWEEN UNBOUND PRECEDING AND CURRENT ROW
    // frame contains all rows from start of partition to last peer of row

    // current frame always starts from first batch
    final VectorAccessible first = getCurrent();

    int length = 0;

    // count all rows that are in the same frame of starting row
    // keep increasing length until we find first non peer row we reach the very
    // last batch
    for (RecordBatchData batch : batches) {
      final VectorAccessible cont = batch.getContainer();
      final int recordCount = cont.getRecordCount();

      // for every remaining row in the partition, count it if it's a peer row
      for (int row = (cont == first) ? start : 0; row < recordCount && length < partition.remaining; row++, length++) {
        if (!isPeer(start, first, row, cont)) {
          return length;
        }
      }
    }

    return length;
  }

  /**
   * aggregates all peer rows of current row
   * @param currentRow starting row of the current frame
   * @throws SchemaChangeException
   */
  private void aggregatePeers(final int currentRow) throws SchemaChangeException {
    logger.trace("aggregating {} rows starting from {}", partition.peers, currentRow);
    assert !partition.isFrameDone() : "frame is empty!";

    // a single frame can include rows from multiple batches
    // start processing first batch and, if necessary, move to next batches
    Iterator<RecordBatchData> iterator = batches.iterator();
    VectorAccessible current = iterator.next().getContainer();
    setupIncoming(current);

    for (int i = 0, row = currentRow; i < partition.peers; i++, row++) {
      if (row >= current.getRecordCount()) {
        // we reached the end of the current batch, move to the next one
        current = iterator.next().getContainer();
        setupIncoming(current);
        row = 0;
      }

      aggregateRecord(row);
    }

  }

  @Override
  public boolean canDoWork() {
    // check if we can process a saved batch
    if (batches.isEmpty()) {
      logger.trace("we don't have enough batches to proceed, fetch next batch");
      return false;
    }

    final VectorAccessible current = getCurrent();
    final int currentSize = current.getRecordCount();
    final VectorAccessible last = batches.get(batches.size() - 1).getContainer();
    final int lastSize = last.getRecordCount();

    if (!isSamePartition(currentSize - 1, current, lastSize - 1, last)
        || !isPeer(currentSize - 1, current, lastSize - 1, last)) {
      logger.trace("frame changed, we are ready to process first saved batch");
      return true;
    } else {
      logger.trace("frame didn't change, fetch next batch");
      return false;
    }
  }

  @Override
  public VectorAccessible getCurrent() {
    return batches.get(0).getContainer();
  }

  @Override
  public int getOutputCount() {
    return outputCount;
  }

  @Override
  public void cleanup() {
    winvecs = null;
  }

  /**
   * setup incoming container for aggregateRecord()
   */
  public abstract void setupIncoming(@Named("incoming") VectorAccessible incoming) throws SchemaChangeException;

  /**
   * setup outgoing container for outputAggregatedValues
   */
  public abstract void setupOutgoing(@Named("outgoing") VectorAccessible outgoing) throws SchemaChangeException;

  /**
   * aggregates a row from the incoming container
   * @param index of row to aggregate
   */
  public abstract void aggregateRecord(@Named("index") int index);

  /**
   * writes aggregated values to row of outgoing container
   * @param outIndex index of row
   */
  public abstract void outputAggregatedValues(@Named("outIndex") int outIndex);

  /**
   * reset all window functions
   */
  public abstract boolean resetValues();

  /**
   * compares two rows from different batches (can be the same), if they have the same value for the partition by
   * expression
   * @param b1Index index of first row
   * @param b1 batch for first row
   * @param b2Index index of second row
   * @param b2 batch for second row
   * @return true if the rows are in the same partition
   */
  public abstract boolean isSamePartition(@Named("b1Index") int b1Index, @Named("b1") VectorAccessible b1,
                                          @Named("b2Index") int b2Index, @Named("b2") VectorAccessible b2);

  /**
   * compares two rows from different batches (can be the same), if they have the same value for the order by
   * expression
   * @param b1Index index of first row
   * @param b1 batch for first row
   * @param b2Index index of second row
   * @param b2 batch for second row
   * @return true if the rows are in the same partition
   */
  public abstract boolean isPeer(@Named("b1Index") int b1Index, @Named("b1") VectorAccessible b1,
                                 @Named("b2Index") int b2Index, @Named("b2") VectorAccessible b2);
}
