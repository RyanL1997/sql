/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.sql.antlr;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.opensearch.sql.common.antlr.SyntaxCheckException;

/** Probe: does `brain(...) OVER ()` parse now that the grammar allows it? */
public class BrainWindowFunctionProbeTest {

  private final SQLSyntaxParser parser = new SQLSyntaxParser();

  @Test
  public void brain_parses_with_empty_over() {
    assertNotNull(parser.parse("SELECT brain(`message`) OVER () FROM t"));
  }

  @Test
  public void brain_parses_with_threshold_args() {
    assertNotNull(parser.parse("SELECT brain(`message`, 5, 0.3) OVER () FROM t"));
  }

  @Test
  public void brain_parses_nested_in_derived_table() {
    assertNotNull(
        parser.parse(
            "SELECT pattern, COUNT(*) FROM (SELECT brain(`message`) OVER () AS pattern FROM"
                + " (SELECT * FROM t) sub_inner) sub GROUP BY pattern"));
  }

  @Test
  public void brain_without_over_is_still_a_syntax_error() {
    assertThrows(SyntaxCheckException.class, () -> parser.parse("SELECT brain(`message`) FROM t"));
  }

  @Test
  public void existing_ranking_functions_still_parse() {
    assertNotNull(parser.parse("SELECT ROW_NUMBER() OVER (ORDER BY a) FROM t"));
    assertNotNull(parser.parse("SELECT RANK() OVER (PARTITION BY a ORDER BY b) FROM t"));
    assertNotNull(parser.parse("SELECT DENSE_RANK() OVER (ORDER BY a) FROM t"));
  }
}
