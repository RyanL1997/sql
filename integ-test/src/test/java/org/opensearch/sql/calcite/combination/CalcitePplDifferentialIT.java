/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.calcite.combination;

import static org.junit.Assert.assertTrue;
import static org.opensearch.sql.legacy.TestsConstants.TEST_INDEX_BANK;

import java.io.IOException;
import org.junit.Test;
import org.opensearch.sql.calcite.combination.DifferentialComparator.Result;

/**
 * The differential oracle on <b>live</b> result sets for a few curated, divergence-free queries:
 * each is executed with pushdown ON and OFF on the same cluster and the two result sets must match.
 *
 * <p>The queries are deliberately free of the documented divergences (no empty-aggregate {@code
 * sum}, no CASE-derived range bucket key, no unstable {@code head}/{@code dedup} without a total
 * order), so a mismatch here is a genuine correctness bug, not a tolerated difference.
 */
public class CalcitePplDifferentialIT extends PushdownDifferentialTestCase {

  @Test
  public void countByGenderIsPushdownInvariant() throws IOException {
    assertPushdownInvariant("source=" + TEST_INDEX_BANK + " | stats count() as c by gender");
  }

  @Test
  public void avgByStateIsPushdownInvariantWithinUlp() throws IOException {
    // Pushdown ON = a single OpenSearch avg aggregation; OFF = SUM0/COUNT in Calcite Enumerable,
    // which can differ in the last bits — the comparator's per-cell ULP tolerance must absorb it.
    assertPushdownInvariant("source=" + TEST_INDEX_BANK + " | stats avg(balance) as a by state");
  }

  @Test
  public void filterThenAggIsPushdownInvariant() throws IOException {
    assertPushdownInvariant(
        "source=" + TEST_INDEX_BANK + " | where age > 30 | stats count() as c by state");
  }

  private void assertPushdownInvariant(String query) throws IOException {
    Result result = comparePushdownOnVsOff(query);
    assertTrue("pushdown ON vs OFF differ for [" + query + "]: " + result.reason(), result.equal());
  }
}
