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

package org.apache.drill.exec.planner.logical;

import java.io.IOException;
import java.util.List;

import org.apache.drill.common.exceptions.DrillRuntimeException;
import org.apache.drill.exec.physical.base.FileGroupScan;
import org.apache.drill.exec.planner.physical.PlannerSettings;
import org.apache.drill.exec.planner.physical.PrelUtil;
import org.apache.drill.exec.store.dfs.FileSelection;
import org.apache.drill.exec.store.dfs.FormatSelection;
import org.eigenbase.relopt.RelOptRule;
import org.eigenbase.relopt.RelOptRuleCall;
import org.eigenbase.relopt.RelOptRuleOperand;
import org.eigenbase.rex.RexNode;

import com.google.common.collect.Lists;

public abstract class DrillPushPartitionFilterIntoScan extends RelOptRule {

  public static final RelOptRule FILTER_ON_PROJECT =
    new DrillPushPartitionFilterIntoScan(
        RelOptHelper.some(DrillFilterRel.class, RelOptHelper.some(DrillProjectRel.class, RelOptHelper.any(DrillScanRel.class))),
        "DrillPushPartitionFilterIntoScan:Filter_On_Project") {

    @Override
      public boolean matches(RelOptRuleCall call) {
        final DrillScanRel scan = (DrillScanRel) call.rel(2);
        return scan.getGroupScan().supportsPartitionFilterPushdown();
      }

    @Override
    public void onMatch(RelOptRuleCall call) {
      final DrillFilterRel filterRel = (DrillFilterRel) call.rel(0);
      final DrillProjectRel projectRel = (DrillProjectRel) call.rel(1);
      final DrillScanRel scanRel = (DrillScanRel) call.rel(2);
      doOnMatch(call, filterRel, projectRel, scanRel);
    }
  };

  public static final RelOptRule FILTER_ON_SCAN =
      new DrillPushPartitionFilterIntoScan(
          RelOptHelper.some(DrillFilterRel.class, RelOptHelper.any(DrillScanRel.class)),
          "DrillPushPartitionFilterIntoScan:Filter_On_Scan") {

      @Override
        public boolean matches(RelOptRuleCall call) {
          final DrillScanRel scan = (DrillScanRel) call.rel(1);
          return scan.getGroupScan().supportsPartitionFilterPushdown();
        }

      @Override
      public void onMatch(RelOptRuleCall call) {
        final DrillFilterRel filterRel = (DrillFilterRel) call.rel(0);
        final DrillScanRel scanRel = (DrillScanRel) call.rel(1);
        doOnMatch(call, filterRel, null, scanRel);
      }
    };

  private DrillPushPartitionFilterIntoScan(
      RelOptRuleOperand operand,
      String id) {
    super(operand, id);
  }

  private FormatSelection splitFilter(FormatSelection origSelection, DirPathBuilder builder) {

    List<String> origFiles = origSelection.getAsFiles();
    String pathPrefix = origSelection.getSelection().selectionRoot;

    List<String> newFiles = Lists.newArrayList();

    List<String> dirPathList = builder.getDirPath();

    for (String dirPath : dirPathList) {
      String fullPath = pathPrefix + dirPath;
      // check containment of this path in the list of files
      for (String origFilePath : origFiles) {
        String[] components = origFilePath.split(":"); // some paths are of the form 'file:<path>', so we need to split
        assert (components.length <= 2);
        String origFileName = "";
        if (components.length == 1) {
          origFileName = components[0];
        } else if (components.length == 2) {
          origFileName = components[1];
        } else {
          assert false ;
        }
        if (origFileName.startsWith(fullPath)) {
          newFiles.add(origFileName);
        }
      }
    }

    if (newFiles.size() > 0) {
      FileSelection newFileSelection = new FileSelection(newFiles, origSelection.getSelection().selectionRoot, true);
      FormatSelection newFormatSelection = new FormatSelection(origSelection.getFormat(), newFileSelection);
      return newFormatSelection;
    }

    return origSelection;
  }

  protected void doOnMatch(RelOptRuleCall call, DrillFilterRel filterRel, DrillProjectRel projectRel, DrillScanRel scanRel) {
    DrillRel inputRel = projectRel != null ? projectRel : scanRel;

    PlannerSettings settings = PrelUtil.getPlannerSettings(call.getPlanner());
    DirPathBuilder builder = new DirPathBuilder(filterRel, inputRel, filterRel.getCluster().getRexBuilder(), settings.getFsPartitionColumnLabel());

    FormatSelection origSelection = (FormatSelection)scanRel.getDrillTable().getSelection();
    FormatSelection newSelection = splitFilter(origSelection, builder);

    if (origSelection == newSelection) {
      return; // no directory filter was pushed down
    }

    RexNode origFilterCondition = filterRel.getCondition();
    RexNode newFilterCondition = builder.getFinalCondition();

    try {
      FileGroupScan fgscan = ((FileGroupScan)scanRel.getGroupScan()).clone(newSelection.getSelection());

      if (newFilterCondition.isAlwaysTrue()) {

        final DrillScanRel newScanRel =
            new DrillScanRel(scanRel.getCluster(),
                scanRel.getTraitSet().plus(DrillRel.DRILL_LOGICAL),
                scanRel.getTable(),
                fgscan,
                scanRel.getRowType(),
                scanRel.getColumns());

        if (projectRel != null) {
          DrillProjectRel newProjectRel = new DrillProjectRel(projectRel.getCluster(), projectRel.getTraitSet(),
              newScanRel, projectRel.getProjects(), filterRel.getRowType());

          call.transformTo(newProjectRel);
        } else {
          call.transformTo(newScanRel);
        }
      } else {

      final DrillScanRel newScanRel =
          new DrillScanRel(scanRel.getCluster(),
              scanRel.getTraitSet().plus(DrillRel.DRILL_LOGICAL),
              scanRel.getTable(),
              fgscan,
              scanRel.getRowType(),
              scanRel.getColumns());
      if (projectRel != null) {
        DrillProjectRel newProjectRel = new DrillProjectRel(projectRel.getCluster(), projectRel.getTraitSet(),
            newScanRel, projectRel.getProjects(), projectRel.getRowType());
        inputRel = newProjectRel;
      } else {
        inputRel = newScanRel;
      }
      final DrillFilterRel newFilterRel = new DrillFilterRel(filterRel.getCluster(), filterRel.getTraitSet(),
          inputRel, origFilterCondition /* for now keep the original condition until we add more test coverage */);

      call.transformTo(newFilterRel);
      }
    } catch (IOException e) {
      throw new DrillRuntimeException(e) ;
    }

  }

}
