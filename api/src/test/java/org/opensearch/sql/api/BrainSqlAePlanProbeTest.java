/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.api;

import static org.apache.calcite.sql.type.SqlTypeName.INTEGER;
import static org.apache.calcite.sql.type.SqlTypeName.VARCHAR;

import java.util.Map;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractSchema;
import org.junit.Before;
import org.junit.Test;
import org.opensearch.sql.executor.QueryType;

/** Probe: does `brain(...) OVER ()` plan on the analytics-engine (Calcite) SQL route? */
public class BrainSqlAePlanProbeTest extends UnifiedQueryTestBase {

  @Override
  protected QueryType queryType() {
    return QueryType.SQL;
  }

  @Before
  @Override
  public void setUp() {
    testSchema =
        new AbstractSchema() {
          @Override
          protected Map<String, Table> getTableMap() {
            return Map.of("logs", createLogsTable());
          }
        };
    context = contextBuilder().build();
    planner = new UnifiedQueryPlanner(context);
  }

  private Table createLogsTable() {
    return SimpleTable.builder()
        .col("id", INTEGER)
        .col("message", VARCHAR)
        .row(new Object[] {1, "GET /api/v1/users 200"})
        .row(new Object[] {2, "GET /api/v2/orders 404"})
        .build();
  }

  @Test
  public void brainWindowFunctionPlans() {
    // Deliberately assert a plan we know is wrong: the failure message prints the
    // ACTUAL plan, which is what we want to read. A throw is caught and printed.
    try {
      givenQuery("SELECT brain(message) OVER () FROM catalog.logs").assertPlan("PROBE_SHOW_ME");
    } catch (Throwable t) {
      System.out.println("PROBE1: " + t.getClass().getName() + ": " + t.getMessage());
    }
  }

  @Test
  public void brainInsideDerivedTableWithGroupByPlans() {
    try {
      givenQuery(
              "SELECT pattern, COUNT(*) FROM (SELECT brain(message) OVER () AS pattern FROM"
                  + " (SELECT * FROM catalog.logs) sub_inner) sub GROUP BY pattern")
          .assertPlan("PROBE_SHOW_ME");
    } catch (Throwable t) {
      System.out.println("PROBE2: " + t.getClass().getName() + ": " + t.getMessage());
    }
  }
}
