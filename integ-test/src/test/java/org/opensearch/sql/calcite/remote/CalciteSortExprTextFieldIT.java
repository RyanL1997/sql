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
 * Regression tests for the keyword-subfield guard in sort-expression pushdown. The {@code
 * SORT_EXPR} simple-field branch previously sorted on the raw analyzed field name; for a {@code
 * text} field that either produced a wrong order (sorting on the analyzed value instead of the
 * {@code .keyword} subfield) or, for a {@code text} field with no {@code .keyword}, an un-sortable
 * field that OpenSearch rejects at search time.
 */
public class CalciteSortExprTextFieldIT extends PPLIntegTestCase {

  @Override
  public void init() throws Exception {
    super.init();
    enableCalcite();
    loadIndex(Index.BANK);
  }

  /**
   * A {@code SORT_EXPR} simple-field key on a {@code text} field that has a {@code .keyword}
   * subfield must sort on the keyword subfield, not the raw analyzed field.
   */
  @Test
  public void sortExprSimpleKeyOnTextWithKeywordUsesKeywordSubfield() throws IOException {
    String physical =
        explainQueryYaml(
            "source="
                + TEST_INDEX_BANK
                + " | eval x = age + balance | sort x, gender | fields gender, age");
    assertTrue(
        "sort-expr simple key should sort on gender.keyword, not raw gender:\n" + physical,
        physical.contains("gender.keyword"));
  }

  /**
   * A {@code SORT_EXPR} simple-field key on a raw {@code text} field (no {@code .keyword}) must
   * decline the pushdown cleanly (coordinator sort) rather than emit an un-sortable raw-text field
   * sort that OpenSearch rejects.
   */
  @Test
  public void sortExprSimpleKeyOnRawTextDeclinesPushdownWithoutError() throws IOException {
    String query =
        "source="
            + TEST_INDEX_BANK
            + " | eval x = age + balance | sort x, employer | fields employer, age";
    // Must not throw: previously an un-sortable raw-text fieldSort caused an OpenSearch error.
    executeQuery(query);
    String physical = explainQueryYaml(query);
    assertFalse(
        "raw-text sort-expr key must NOT push SORT_EXPR (no un-sortable fieldSort):\n" + physical,
        physical.contains("SORT_EXPR->"));
  }
}
