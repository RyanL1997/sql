/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.calcite.remote;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.opensearch.sql.legacy.TestsConstants.TEST_INDEX_BANK;

import java.io.IOException;
import org.junit.Test;
import org.opensearch.sql.ppl.PPLIntegTestCase;

/**
 * Characterization tests for the keyword-subfield guard in aggregate (terms-source) pushdown. A
 * {@code stats ... by <field>} on a raw {@code text} field (no {@code .keyword}) cannot be a native
 * terms-aggregation source, so it must decline the pushdown cleanly (coordinator aggregation)
 * instead of building a composite source on {@code field(null)}, which fails with an opaque error.
 * A field with a {@code .keyword} subfield still pushes.
 */
public class CalciteAggTextFieldIT extends PPLIntegTestCase {

  @Override
  public void init() throws Exception {
    super.init();
    enableCalcite();
    loadIndex(Index.BANK);
  }

  @Test
  public void statsByRawTextFieldDeclinesPushdownWithoutError() throws IOException {
    String query = "source=" + TEST_INDEX_BANK + " | stats count() as c by employer";
    // Must not throw: the terms source on a keyword-less text field is declined, not built on null.
    executeQuery(query);
    String physical = explainQueryYaml(query);
    assertFalse(
        "aggregation grouped by a raw text field must NOT be pushed down:\n" + physical,
        physical.contains("AGGREGATION->"));
  }

  @Test
  public void statsByTextWithKeywordFieldIsPushedDown() throws IOException {
    String physical =
        explainQueryYaml("source=" + TEST_INDEX_BANK + " | stats count() as c by gender");
    assertTrue(
        "aggregation grouped by a text field with a .keyword subfield should be pushed down:\n"
            + physical,
        physical.contains("AGGREGATION->"));
  }
}
