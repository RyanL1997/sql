/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.calcite.combination;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.opensearch.sql.legacy.TestsConstants.TEST_INDEX_BANK;

import java.io.IOException;
import org.junit.Test;
import org.opensearch.sql.opensearch.storage.scan.context.PushDownType;
import org.opensearch.sql.ppl.PPLIntegTestCase;

/**
 * The shape oracle on a <b>live</b> explain — the framework's Piece 4. Feeds {@link
 * #explainQueryYaml} output through {@link PushdownShapeOracle} (the same parser its unit test
 * validates on the golden corpus).
 *
 * <p>Authored Path-A (RandomizedRunner-native): extends {@link PPLIntegTestCase}, zero-arg {@code
 * test*} methods, no Jupiter {@code @ParameterizedTest}. The three cases double as a runtime
 * confirmation of the latent-bug findings on the {@code bank} index, where {@code address}/{@code
 * employer} are {@code text} with no {@code .keyword} and {@code state} is {@code text} with a
 * {@code .keyword} subfield.
 */
public class CalcitePplCombinationShapeIT extends PPLIntegTestCase {

  @Override
  public void init() throws Exception {
    super.init();
    enableCalcite();
    loadIndex(Index.BANK);
  }

  /** Positive: sort on a keyword field pushes (SORT token present). */
  @Test
  public void sortKeywordFieldIsPushedDown() throws IOException {
    String physical = explainQueryYaml("source=" + TEST_INDEX_BANK + " | sort firstname");
    assertTrue(
        "expected SORT-> for keyword field 'firstname':\n" + physical,
        PushdownShapeOracle.pushed(physical, PushDownType.SORT));
  }

  /**
   * Field-type-aware: sort on a {@code text}-without-{@code .keyword} field correctly does NOT push
   * (the no-pushdown outcome is correct — finding #1 is only the swallowed-exception mechanism).
   * The load-bearing check is the ABSENCE of the SORT token; the coordinator node name varies (here
   * {@code CalciteEnumerableTopK}, not {@code EnumerableSort}) so we treat it only as a hint.
   */
  @Test
  public void sortBareTextFieldIsNotPushedDown() throws IOException {
    String physical = explainQueryYaml("source=" + TEST_INDEX_BANK + " | sort address");
    assertFalse(
        "text-without-keyword 'address' must NOT push SORT:\n" + physical,
        PushdownShapeOracle.pushed(physical, PushDownType.SORT));
    assertTrue(
        "expected the sort on the coordinator (EnumerableSort or CalciteEnumerableTopK):\n"
            + physical,
        physical.contains("EnumerableSort") || physical.contains("EnumerableTopK"));
  }

  /**
   * Finding #2 (the genuine bug): a mixed expr+field sort routes through SORT_EXPR, whose
   * simple-field branch uses the RAW field name with no {@code .keyword} conversion — so it sorts
   * on {@code state} instead of {@code state.keyword}, unlike the plain {@code sort state} path.
   * Characterization test: when the bug is fixed, the final assertion flips and this must be
   * updated.
   */
  @Test
  public void sortExprOnTextFieldKeysOnRawField() throws IOException {
    String plain = explainQueryYaml("source=" + TEST_INDEX_BANK + " | sort state");
    assertTrue(
        "plain 'sort state' should key on state.keyword:\n" + plain,
        plain.contains("state.keyword"));

    String mixed =
        explainQueryYaml(
            "source="
                + TEST_INDEX_BANK
                + " | eval x = age + balance | sort x, state | fields state, age");
    assertTrue(
        "expected SORT_EXPR-> for the expression sort:\n" + mixed,
        PushdownShapeOracle.pushed(mixed, PushDownType.SORT_EXPR));
    assertFalse(
        "FINDING #2: SORT_EXPR sorts on raw 'state' not 'state.keyword' (flip once fixed):\n"
            + mixed,
        mixed.contains("state.keyword"));
  }
}
