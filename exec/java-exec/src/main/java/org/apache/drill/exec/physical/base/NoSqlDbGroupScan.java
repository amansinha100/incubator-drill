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
package org.apache.drill.exec.physical.base;

import java.util.List;

import org.apache.drill.common.expression.SchemaPath;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * A NoSqlDbGroupScan operator represents the scan associated with a NoSql DB
 */
public interface NoSqlDbGroupScan {

  /**
   * Whether or not this NoSqlDbGroupScan supports secondary indexes
   */
  public boolean supportsSecondaryIndex();

  /**
   * Returns a list of columns that can be used for secondary index based lookup
   *
   */
  @JsonIgnore
  public List<SchemaPath> getSecondaryIndexColumns();

}