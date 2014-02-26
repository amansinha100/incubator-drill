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
package org.apache.drill.exec.physical.impl.common;

import java.util.ArrayList;

import javax.inject.Named;

import org.apache.drill.common.expression.LogicalExpression;
import org.apache.drill.common.logical.data.NamedExpression;
import org.apache.drill.exec.ops.FragmentContext;
import org.apache.drill.exec.record.MaterializedField;
import org.apache.drill.exec.record.VectorContainer;
import org.apache.drill.exec.compile.sig.RuntimeOverridden;
import org.apache.drill.exec.expr.TypeHelper;
import org.apache.drill.exec.expr.holders.IntHolder;
import org.apache.drill.exec.record.RecordBatch;
import org.apache.drill.exec.vector.ValueVector;
import org.apache.drill.exec.vector.allocator.VectorAllocator;
import org.apache.drill.exec.vector.BigIntVector;
import org.apache.drill.exec.expr.holders.BigIntHolder;

public abstract class HashTableTemplate implements HashTable { 

  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(HashTable.class);
  private static final boolean EXTRA_DEBUG = false;

  private static final int EMPTY_SLOT = -1;

  // A hash 'bucket' consists of the start index to indicate start of a hash chain

  // Array of start indexes. start index is a global index across all batch holders
  private int startIndices[] ;

  // Array of batch holders..each batch holder can hold up to BATCH_SIZE entries
  private ArrayList<BatchHolder> batchHolders;

  // Size of the hash table in terms of number of buckets
  private int tableSize = 0;

  // Threshold after which we rehash; It must be the tableSize * loadFactor
  private int threshold;

  // Actual number of entries in the hash table
  private int numEntries = 0;

  // current available (free) slot globally across all batch holders
  private int freeIndex = 0;

  // Placeholder for the current index while probing the hash table
  private IntHolder currentIdxHolder; 

  private FragmentContext context;

  // The incoming record batch
  private RecordBatch incoming;

  // The outgoing record batch
  private RecordBatch outgoing;

  // Hash table configuration parameters
  private HashTableConfig htConfig; 

  private MaterializedField[] materializedKeyFields;

  private int outputCount = 0;

  // This class encapsulates the links, keys and values for up to BATCH_SIZE
  // *unique* records. Thus, suppose there are N incoming record batches, each 
  // of size BATCH_SIZE..but they have M unique keys altogether, the number of 
  // BatchHolders will be (M/BATCH_SIZE) + 1
  public class BatchHolder {

    // Container of vectors to hold type-specific keys
    private VectorContainer htContainer;

    // Array of 'link' values 
    private int links[]; 

    // Array of hash values - this is useful when resizing the hash table
    private int hashValues[];

    int maxOccupiedIdx = 0;

    private BatchHolder() {

      htContainer = new VectorContainer();
      ValueVector vector;

      for(int i = 0; i < materializedKeyFields.length; i++) {
        MaterializedField outputField = materializedKeyFields[i];

        // Create a type-specific ValueVector for this key
        vector = TypeHelper.getNewVector(outputField, context.getAllocator()) ;
        int avgBytes = 50;  // TODO: do proper calculation for variable width fields 
        VectorAllocator.getAllocator(vector, avgBytes).alloc(BATCH_SIZE) ;
        htContainer.add(vector) ;
      }

      links = new int[BATCH_SIZE];
      hashValues = new int[BATCH_SIZE];
 
      init(links, hashValues);
    }

    private void init(int[] links, int[] hashValues) {
      for (int i=0; i < links.length; i++) {
        links[i] = EMPTY_SLOT;
      }
      for (int i=0; i < hashValues.length; i++) {
        hashValues[i] = 0;
      }
    }

    private void setup() {
      setupInterior(incoming, outgoing, htContainer);
    }

    // Check if the key at the currentIdx position in hash table matches the key
    // at the incomingRowIdx. if the key does not match, update the 
    // currentIdxHolder with the index of the next link.
    private boolean isKeyMatch(int incomingRowIdx, 
                               IntHolder currentIdxHolder) {

      int currentIdxWithinBatch = currentIdxHolder.value & BATCH_MASK;

      if (! isKeyMatchInternal(incomingRowIdx, currentIdxWithinBatch)) {
        currentIdxHolder.value = links[currentIdxWithinBatch];
        return false;
      }
      return true;
    }

    // Insert a new <key1, key2...keyN> entry coming from the incoming batch into the hash table 
    // container at the specified index 
    private boolean insertEntry(int incomingRowIdx, int currentIdx, int hashValue, BatchHolder lastEntryBatch, int lastEntryIdxWithinBatch) { 
      int currentIdxWithinBatch = currentIdx & BATCH_MASK;

      if (! setValue(incomingRowIdx, currentIdxWithinBatch)) {
        return false;
      }

      // the previous entry in this hash chain should now point to the entry in this currentIdx
      if (lastEntryBatch != null) {
        lastEntryBatch.updateLinks(lastEntryIdxWithinBatch, currentIdx);
      }

      // since this is the last entry in the hash chain, the links array at position currentIdx 
      // will point to a null (empty) slot
      links[currentIdxWithinBatch] = EMPTY_SLOT;
      hashValues[currentIdxWithinBatch] = hashValue;

      maxOccupiedIdx = Math.max(maxOccupiedIdx, currentIdxWithinBatch);

      if (EXTRA_DEBUG) logger.debug("BatchHolder: inserted key at incomingRowIdx = {}, currentIdx = {}, hash value = {}.", incomingRowIdx, currentIdx, hashValue);

      return true;
    }

    private void updateLinks(int lastEntryIdxWithinBatch, int currentIdx) {
      links[lastEntryIdxWithinBatch] = currentIdx;
    }

    private void rehash(int numbuckets, int[] newStartIndices, int batchStartIdx) {

      if (EXTRA_DEBUG) logger.debug("Resizing and rehashing table...");

      int[] newLinks = new int[links.length];
      int[] newHashValues = new int[hashValues.length];

      init(newLinks, newHashValues);

      for (int i = 0; i <= maxOccupiedIdx; i++) {
        int entryIdxWithinBatch = i; 
        int entryIdx = entryIdxWithinBatch + batchStartIdx;
        int hash = hashValues[entryIdxWithinBatch]; // get the already saved hash value
        int bucketIdx = getBucketIndex(hash, numbuckets);
        int newStartIdx = newStartIndices[bucketIdx];

        if (newStartIdx == EMPTY_SLOT) { // new bucket was empty
          newStartIndices[bucketIdx] = entryIdx; // update the start index to point to entry
          newLinks[entryIdxWithinBatch] = EMPTY_SLOT;
          newHashValues[entryIdxWithinBatch] = hash;

          if (EXTRA_DEBUG) logger.debug("New bucket was empty. bucketIdx = {}, newStartIndices[ {} ] = {}, newLinks[ {} ] = {}, hash value = {}.", bucketIdx, bucketIdx, newStartIndices[bucketIdx], entryIdxWithinBatch, newLinks[entryIdxWithinBatch], newHashValues[entryIdxWithinBatch]);

        } else {
          // follow the new table's hash chain until we encounter empty slot
          int idx = newStartIdx;
          int idxWithinBatch = 0;
          while (true) {
            idxWithinBatch = idx & BATCH_MASK;
            if (newLinks[idxWithinBatch] == EMPTY_SLOT) {
              newLinks[idxWithinBatch] = entryIdx;
              newLinks[entryIdxWithinBatch] = EMPTY_SLOT;
              newHashValues[entryIdxWithinBatch] = hash;

              if (EXTRA_DEBUG) logger.debug("Followed hash chain in new bucket. bucketIdx = {}, newLinks[ {} ] = {}, newLinks[ {} ] = {}, hash value = {}.", bucketIdx, idxWithinBatch, newLinks[idxWithinBatch], entryIdxWithinBatch, newLinks[entryIdxWithinBatch], newHashValues[entryIdxWithinBatch]);

              break;
            }
            idx = newLinks[idxWithinBatch];
          }

        }

      }      

      links = newLinks;
      hashValues = newHashValues;
    }
    
    private boolean outputKeys() {

      /** for debugging 
      Object tmp = (htContainer).getValueAccessorById(0, BigIntVector.class).getValueVector();
      BigIntVector vv0 = null;
      BigIntHolder holder = null;

      if (tmp != null) { 
        vv0 = ((BigIntVector) tmp);
        holder = new BigIntHolder();
      }
      */

      for (int i = 0; i <= maxOccupiedIdx; i++) { 
        if (outputRecordKeys(i, outputCount) ) {
          if (EXTRA_DEBUG) logger.debug("Outputting keys to {}", outputCount) ;

          // debugging 
          // holder.value = vv0.getAccessor().get(i);
          // if (holder.value == 100018 || holder.value == 100021) { 
          //  logger.debug("Outputting key = {} at index - {} to outgoing index = {}.", holder.value, i, outputCount);
          // }

          outputCount++;
        } else {
          return false;
        }
      }
      return true;
    }

    private void dump(int idx) {
      while (true) {
        int idxWithinBatch = idx & BATCH_MASK;
        if (idxWithinBatch == EMPTY_SLOT) {
          break;
        } else {
          logger.debug("links[ {} ] = {}, hashValues[ {} ] = {}.", idxWithinBatch, links[idxWithinBatch], idxWithinBatch, hashValues[idxWithinBatch]);
          idx = links[idxWithinBatch];
        }
      }
    }
    
    private void clear() {
      htContainer = null;
      links = null;
      hashValues = null;
    }

    // These methods will be code-generated 

    @RuntimeOverridden
    protected void setupInterior(@Named("incoming") RecordBatch incoming, 
                                 @Named("outgoing") RecordBatch outgoing,
                                 @Named("htContainer") VectorContainer htContainer) {}

    @RuntimeOverridden
    protected boolean isKeyMatchInternal(@Named("incomingRowIdx") int incomingRowIdx, @Named("htRowIdx") int htRowIdx) {return false;} 

    @RuntimeOverridden
    protected boolean setValue(@Named("incomingRowIdx") int incomingRowIdx, @Named("htRowIdx") int htRowIdx) {return false;} 

    @RuntimeOverridden
    protected boolean outputRecordKeys(@Named("htRowIdx") int htRowIdx, @Named("outRowIdx") int outRowIdx) {return false;} 

  } // class BatchHolder


  @Override
  public void setup(HashTableConfig htConfig, FragmentContext context, RecordBatch incoming, 
                    RecordBatch outgoing, LogicalExpression[] keyExprs) {
    float loadf = htConfig.getLoadFactor(); 
    int initialCap = htConfig.getInitialCapacity();

    if (loadf <= 0 || Float.isNaN(loadf)) throw new IllegalArgumentException("Load factor must be a valid number greater than 0");
    if (initialCap <= 0) throw new IllegalArgumentException("The initial capacity must be greater than 0");
    if (initialCap > MAXIMUM_CAPACITY) throw new IllegalArgumentException("The initial capacity must be less than maximum capacity allowed");

    if (htConfig.getKeyExprs() == null || htConfig.getKeyExprs().length == 0) throw new IllegalArgumentException("Hash table must have at least 1 key expression");

    this.htConfig = htConfig;
    this.context = context;
    this.incoming = incoming;
    this.outgoing = outgoing;

    // round up the initial capacity to nearest highest power of 2
    tableSize = roundUpToPowerOf2(initialCap);
    if (tableSize > MAXIMUM_CAPACITY)
      tableSize = MAXIMUM_CAPACITY;

    threshold = (int) Math.ceil(tableSize * loadf);

    startIndices = new int[tableSize];

    materializedKeyFields = new MaterializedField[keyExprs.length];

    for(int i = 0; i < keyExprs.length; i++) {
      LogicalExpression expr = keyExprs[i];	
      NamedExpression ne = htConfig.getKeyExprs()[i];
      materializedKeyFields[i] = MaterializedField.create(ne.getRef(), expr.getMajorType()) ;
    }

    // Create the first batch holder 
    batchHolders = new ArrayList<BatchHolder>();
    addBatchHolder();

    doSetup(incoming);

    currentIdxHolder = new IntHolder();    
    initBuckets();
  }

  private void initBuckets() {
    for (int i=0; i < startIndices.length; i++) {
      startIndices[i] = EMPTY_SLOT;
    }
  }

  public int numBuckets() {
    return startIndices.length;
  }

  public int size() {
    return numEntries;
  }

  public boolean isEmpty() {
    return numEntries == 0;
  }

  public void clear() {
    for (BatchHolder bh : batchHolders) {
      bh.clear();
    }
    batchHolders.clear();
    batchHolders = null;
    startIndices = null;
    materializedKeyFields = null;
    currentIdxHolder = null; 
    numEntries = 0;
  }

  private int getBucketIndex(int hash, int numBuckets) {
    return hash & (numBuckets - 1);
  }

  private static int roundUpToPowerOf2(int number) {
    int rounded = number >= MAXIMUM_CAPACITY
           ? MAXIMUM_CAPACITY
           : (rounded = Integer.highestOneBit(number)) != 0
               ? (Integer.bitCount(number) > 1) ? rounded << 1 : rounded
               : 1;

        return rounded;
  }

  public PutStatus put(int incomingRowIdx, IntHolder htIdxHolder) {

    int hash = getHash(incomingRowIdx);
    int i = getBucketIndex(hash, numBuckets()); 
    int startIdx = startIndices[i];
    int currentIdx;
    int currentIdxWithinBatch;
    BatchHolder bh;
    BatchHolder lastEntryBatch = null;
    int lastEntryIdxWithinBatch = EMPTY_SLOT;

        
    if (startIdx == EMPTY_SLOT) {
      // this is the first entry in this bucket; find the first available slot in the 
      // container of keys and values
      currentIdx = freeIndex++;
      addBatchIfNeeded(currentIdx);

      if (EXTRA_DEBUG) logger.debug("Empty bucket index = {}. incomingRowIdx = {}; inserting new entry at currentIdx = {}.", i, incomingRowIdx, currentIdx);

      if (insertEntry(incomingRowIdx, currentIdx, hash, lastEntryBatch, lastEntryIdxWithinBatch)) {
        // update the start index array
        startIndices[getBucketIndex(hash, numBuckets())] = currentIdx;
        htIdxHolder.value = currentIdx;
        return PutStatus.KEY_ADDED;
      }
      return PutStatus.PUT_FAILED;
    }

    currentIdx = startIdx;
    boolean found = false;

    // bh = batchHolders.get(currentIdx / BATCH_SIZE);
    bh = batchHolders.get( (currentIdx >>> 16) & BATCH_MASK);
    currentIdxHolder.value = currentIdx;
    
    // if startIdx is non-empty, follow the hash chain links until we find a matching 
    // key or reach the end of the chain
    while (true) {
      // currentIdxWithinBatch = currentIdxHolder.value % BATCH_SIZE;
      currentIdxWithinBatch = currentIdxHolder.value & BATCH_MASK;

      if (bh.isKeyMatch(incomingRowIdx, currentIdxHolder)) {
        htIdxHolder.value = currentIdxHolder.value;
        found = true;
        break;        
      }
      else if (currentIdxHolder.value == EMPTY_SLOT) {
        lastEntryBatch = bh;
        lastEntryIdxWithinBatch = currentIdxWithinBatch;
        break;
      } else {
        // bh = batchHolders.get(currentIdxHolder.value / BATCH_SIZE);
        bh = batchHolders.get( (currentIdxHolder.value >>> 16) & HashTable.BATCH_MASK);
        lastEntryBatch = bh;
      }
    }

    if (!found) {
      // no match was found, so insert a new entry
      currentIdx = freeIndex++;
      addBatchIfNeeded(currentIdx);

      if (EXTRA_DEBUG) logger.debug("No match was found for incomingRowIdx = {}; inserting new entry at currentIdx = {}.", incomingRowIdx, currentIdx);

      if (insertEntry(incomingRowIdx, currentIdx, hash, lastEntryBatch, lastEntryIdxWithinBatch)) {
        htIdxHolder.value = currentIdx;
        return PutStatus.KEY_ADDED;
      }
      else 
        return PutStatus.PUT_FAILED;
    }

    return found ? PutStatus.KEY_PRESENT : PutStatus.KEY_ADDED ;
  }

  private boolean insertEntry(int incomingRowIdx, int currentIdx, int hashValue, BatchHolder lastEntryBatch, int lastEntryIdx) {

    // resize hash table if needed and transfer the metadata 
    resizeAndRehashIfNeeded(currentIdx);

    addBatchIfNeeded(currentIdx);

    BatchHolder bh = batchHolders.get( (currentIdx >>> 16) & BATCH_MASK);

    if (bh.insertEntry(incomingRowIdx, currentIdx, hashValue, lastEntryBatch, lastEntryIdx)) {
      numEntries++ ;
      return true;
    }

    return false;
  }

  // Return -1 if key is not found in the hash table. Otherwise, return the global index of the key
  public int containsKey(int incomingRowIdx) {
    int hash = getHash(incomingRowIdx);
    int i = getBucketIndex(hash, numBuckets());

    int currentIdx = startIndices[i];

    if (currentIdx == EMPTY_SLOT)
      return -1;
    
    BatchHolder bh = batchHolders.get( (currentIdx >>> 16) & BATCH_MASK);
    currentIdxHolder.value = currentIdx;

    boolean found = false;

    while (true) {
      if (bh.isKeyMatch(incomingRowIdx, currentIdxHolder)) {
        found = true; 
        break;
      } else if (currentIdxHolder.value == EMPTY_SLOT) {
        break;
      } else {
        bh = batchHolders.get( (currentIdxHolder.value >>> 16) & BATCH_MASK);
      }
    }
   
    return found ? currentIdxHolder.value : -1;
  }


  // Add a new BatchHolder to the list of batch holders if needed. This is based on the supplied 
  // currentIdx; since each BatchHolder can hold up to BATCH_SIZE entries, if the currentIdx exceeds
  // the capacity, we will add a new BatchHolder. 
  private BatchHolder addBatchIfNeeded(int currentIdx) {
    int totalBatchSize = batchHolders.size() * BATCH_SIZE;
    
    if (currentIdx >= batchHolders.size() * BATCH_SIZE) {
      BatchHolder bh = addBatchHolder(); 
      if (EXTRA_DEBUG) logger.debug("HashTable: Added new batch. Num batches = {}.", batchHolders.size());
      return bh;
    }
    else {
      return batchHolders.get(batchHolders.size() - 1);
    }
  }

  private BatchHolder addBatchHolder() {
    BatchHolder bh = new BatchHolder();
    batchHolders.add(bh);
    bh.setup();
    return bh;
  }

  // Resize the hash table if needed by creating a new one with double the number of buckets. 
  // For each entry in the old hash table, re-hash it to the new table and update the metadata
  // in the new table.. the metadata consists of the startIndices, links and hashValues. 
  // Note that the keys stored in the BatchHolders are not moved around. 
  private void resizeAndRehashIfNeeded(int currentIdx) {
    if (numEntries < threshold)
      return;

    if (EXTRA_DEBUG) logger.debug("Hash table numEntries = {}, threshold = {}; resizing the table...", numEntries, threshold);

    // If the table size is already MAXIMUM_CAPACITY, don't resize 
    // the table, but set the threshold to Integer.MAX_VALUE such that 
    // future attempts to resize will return immediately. 
    if (tableSize == MAXIMUM_CAPACITY) {
      threshold = Integer.MAX_VALUE;
      return;
    }

    int newSize = 2 * tableSize;

    tableSize = roundUpToPowerOf2(newSize);
    if (tableSize > MAXIMUM_CAPACITY)
      tableSize = MAXIMUM_CAPACITY;

    // set the new threshold based on the new table size and load factor
    threshold = (int) Math.ceil(tableSize * htConfig.getLoadFactor());

    int[] newStartIndices = new int[tableSize] ;

    // initialize
    for (int i = 0; i < newStartIndices.length; i++) {
      newStartIndices[i] = EMPTY_SLOT;
    }

    for (int i = 0; i < batchHolders.size(); i++) {
      BatchHolder bh = batchHolders.get(i) ;
      int batchStartIdx = i * BATCH_SIZE;
      bh.rehash(tableSize, newStartIndices, batchStartIdx);  
    }    
   
    startIndices = newStartIndices;

    if (EXTRA_DEBUG) {
      logger.debug("After resizing and rehashing, dumping the hash table...");
      logger.debug("Number of buckets = {}.", startIndices.length);
      for (int i = 0; i < startIndices.length; i++) {
        logger.debug("Bucket: {}, startIdx[ {} ] = {}.", i, i, startIndices[i]);
        int idx = startIndices[i];
        BatchHolder bh = batchHolders.get( (idx >>> 16) & BATCH_MASK);
        bh.dump(idx);
      }
    }
  }

  public boolean outputKeys() {
    for (BatchHolder bh : batchHolders) {
      if ( ! bh.outputKeys()) {
        return false;
      }
    }
    return true;
  }

  // These methods will be code-generated in the context of the outer class 
  protected abstract void doSetup(@Named("incoming") RecordBatch incoming);
  protected abstract int getHash(@Named("incomingRowIdx") int incomingRowIdx);

} 


