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

package org.apache.drill.exec.physical.impl.join;

import static org.junit.Assert.assertEquals;

import org.apache.drill.PlanTestBase;
import org.apache.drill.common.util.TestTools;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;

public class TestNestedLoopJoin extends PlanTestBase {

  private static String nlpattern = "NestedLoopJoin";
  private static final String WORKING_PATH = TestTools.getWorkingPath();
  private static final String TEST_RES_PATH = WORKING_PATH + "/src/test/resources";

  private static final String NLJ = "Alter session set `planner.enable_hashjoin` = false; " +
      "alter session set `planner.enable_mergejoin` = false; " +
      "alter session set `planner.enable_nljoin_for_scalar_only` = false; ";
  private static final String SINGLE_NLJ = "alter session set `planner.disable_exchanges` = true; " + NLJ;
  private static final String DISABLE_HJ = "alter session set `planner.enable_hashjoin` = false";
  private static final String ENABLE_HJ = "alter session set `planner.enable_hashjoin` = true";
  private static final String DISABLE_MJ = "alter session set `planner.enable_mergejoin` = false";
  private static final String ENABLE_MJ = "alter session set `planner.enable_mergejoin` = true";
  private static final String DISABLE_NLJ_SCALAR = "alter session set `planner.enable_nljoin_for_scalar_only` = false";
  private static final String ENABLE_NLJ_SCALAR = "alter session set `planner.enable_nljoin_for_scalar_only` = true";

  // Test queries used by planning and execution tests
  private static final String testNlJoinExists_1 = "select r_regionkey from cp.`tpch/region.parquet` "
      + " where exists (select n_regionkey from cp.`tpch/nation.parquet` "
      + " where n_nationkey < 10)";

  private static final String testNlJoinNotIn_1 = "select r_regionkey from cp.`tpch/region.parquet` "
      + " where r_regionkey not in (select n_regionkey from cp.`tpch/nation.parquet` "
      + "                            where n_nationkey < 4)";

  private static final String testNlJoinInequality_1 = "select r_regionkey from cp.`tpch/region.parquet` "
      + " where r_regionkey > (select min(n_regionkey) from cp.`tpch/nation.parquet` "
      + "                        where n_nationkey < 4)";


  @Test
  public void testNlJoinExists_1_planning() throws Exception {
    testPlanMatchingPatterns(testNlJoinExists_1, new String[]{nlpattern}, new String[]{});
  }

  @Test
  public void testNlJoinNotIn_1_planning() throws Exception {
    testPlanMatchingPatterns(testNlJoinNotIn_1, new String[]{nlpattern}, new String[]{});
  }

  @Test
  public void testNlJoinInequality_1() throws Exception {
    testPlanMatchingPatterns(testNlJoinInequality_1, new String[]{nlpattern}, new String[]{});
  }

  @Test
  public void testNlJoinAggrs_1_planning() throws Exception {
    String query = "select total1, total2 from "
       + "(select sum(l_quantity) as total1 from cp.`tpch/lineitem.parquet` where l_suppkey between 100 and 200), "
       + "(select sum(l_quantity) as total2 from cp.`tpch/lineitem.parquet` where l_suppkey between 200 and 300)  ";
    testPlanMatchingPatterns(query, new String[]{nlpattern}, new String[]{});
  }

  @Test // equality join and scalar right input, hj and mj disabled
  public void testNlJoinEqualityScalar_1_planning() throws Exception {
    String query = "select r_regionkey from cp.`tpch/region.parquet` "
        + " where r_regionkey = (select min(n_regionkey) from cp.`tpch/nation.parquet` "
        + "                        where n_nationkey < 10)";
    test(DISABLE_HJ);
    test(DISABLE_MJ);
    testPlanMatchingPatterns(query, new String[]{nlpattern}, new String[]{});
    test(ENABLE_HJ);
    test(ENABLE_MJ);
  }

  @Test // equality join and scalar right input, hj and mj disabled, enforce exchanges
  public void testNlJoinEqualityScalar_2_planning() throws Exception {
    String query = "select r_regionkey from cp.`tpch/region.parquet` "
        + " where r_regionkey = (select min(n_regionkey) from cp.`tpch/nation.parquet` "
        + "                        where n_nationkey < 10)";
    test("alter session set `planner.slice_target` = 1");
    test(DISABLE_HJ);
    test(DISABLE_MJ);
    testPlanMatchingPatterns(query, new String[]{nlpattern, "BroadcastExchange"}, new String[]{});
    test(ENABLE_HJ);
    test(ENABLE_MJ);
    test("alter session set `planner.slice_target` = 100000");
  }

  @Test // equality join and non-scalar right input, hj and mj disabled
  public void testNlJoinEqualityNonScalar_1_planning() throws Exception {
    String query = "select r.r_regionkey from cp.`tpch/region.parquet` r inner join cp.`tpch/nation.parquet` n"
        + " on r.r_regionkey = n.n_regionkey where n.n_nationkey < 10";
    test(DISABLE_HJ);
    test(DISABLE_MJ);
    test(DISABLE_NLJ_SCALAR);
    testPlanMatchingPatterns(query, new String[]{nlpattern}, new String[]{});
    test(ENABLE_HJ);
    test(ENABLE_MJ);
    test(ENABLE_NLJ_SCALAR);
  }

  @Test // equality join and non-scalar right input, hj and mj disabled, enforce exchanges
  public void testNlJoinEqualityNonScalar_2_planning() throws Exception {
    // String query = "select r.r_regionkey from cp.`tpch/region.parquet` r inner join cp.`tpch/nation.parquet` n"
    //  + " on r.r_regionkey = n.n_regionkey where n.n_nationkey < 10";
    String query = String.format("select n.n_nationkey from cp.`tpch/nation.parquet` n, "
        + " dfs_test.`%s/multilevel/parquet` o "
        + " where n.n_regionkey = o.o_orderkey and o.o_custkey < 5", TEST_RES_PATH);
    test("alter session set `planner.slice_target` = 1");
    test(DISABLE_HJ);
    test(DISABLE_MJ);
    test(DISABLE_NLJ_SCALAR);
    testPlanMatchingPatterns(query, new String[]{nlpattern, "BroadcastExchange"}, new String[]{});
    test(ENABLE_HJ);
    test(ENABLE_MJ);
    test(ENABLE_NLJ_SCALAR);
    test("alter session set `planner.slice_target` = 100000");
  }

}
