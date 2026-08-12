/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.sql.antlr;

import org.junit.jupiter.api.Test;

/** Probe: does reserving BRAIN break queries that used `brain` as an identifier? */
public class BrainBackwardCompatProbeTest {

  private final SQLSyntaxParser parser = new SQLSyntaxParser();

  private void probe(String label, String sql) {
    try {
      parser.parse(sql);
      System.out.println("BC_OK   " + label + "  |  " + sql);
    } catch (Throwable t) {
      System.out.println("BC_BROKE " + label + "  |  " + sql + "  ->  " + t.getClass().getSimpleName());
    }
  }

  @Test
  public void identifierUsages() {
    probe("column        ", "SELECT brain FROM t");
    probe("column in WHERE", "SELECT * FROM t WHERE brain = 1");
    probe("alias         ", "SELECT a AS brain FROM t");
    probe("table         ", "SELECT * FROM brain");
    probe("qualified col ", "SELECT t.brain FROM t");
    probe("group by      ", "SELECT brain, COUNT(*) FROM t GROUP BY brain");
    probe("backticked    ", "SELECT `brain` FROM t");
    // control: an existing reserved window-function keyword behaves the same way
    probe("rank as column", "SELECT rank FROM t");
    probe("row_number col", "SELECT row_number FROM t");
  }
}
