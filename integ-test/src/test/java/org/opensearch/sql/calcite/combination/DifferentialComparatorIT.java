/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.calcite.combination;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.opensearch.sql.calcite.combination.DifferentialComparator.Result;
import org.opensearch.sql.calcite.combination.DifferentialComparator.Schema;

/**
 * Cluster-free validation of {@link DifferentialComparator}: it must absorb the tolerable
 * divergences (ULP, row ordering) and surface the genuine ones (0-vs-null, "null"-vs-null, missing
 * row, schema drift).
 */
public class DifferentialComparatorIT {

  private static final Schema METRIC = new Schema(List.of("avg(balance)"), List.of("double"));
  private static final Schema GROUPED =
      new Schema(List.of("state", "c"), List.of("string", "long"));

  @Test
  public void tolerableFloatUlp() {
    // AVG decomposes to SUM0/COUNT with pushdown off -> last-bit difference, must be tolerated.
    assertEqual(
        DifferentialComparator.compare(
            METRIC, METRIC, rows(row(8761.333333333334)), rows(row(8761.333333333332))));
  }

  @Test
  public void tolerableRowOrdering() {
    assertEqual(
        DifferentialComparator.compare(
            GROUPED,
            GROUPED,
            rows(row("WA", 3L), row("VA", 5L)),
            rows(row("VA", 5L), row("WA", 3L))));
  }

  @Test
  public void genuineEmptySumZeroVsNull() {
    // 0 (pushdown) vs null (no-pushdown), issue #3408 — a real difference, not float noise.
    assertDifferent(
        DifferentialComparator.compare(METRIC, METRIC, rows(row(0)), rows(row((Object) null))));
  }

  @Test
  public void genuineBucketKeyNullStringVsNull() {
    assertDifferent(
        DifferentialComparator.compare(
            GROUPED, GROUPED, rows(row("null", 7L)), rows(row((Object) null, 7L))));
  }

  @Test
  public void genuineMissingRow() {
    assertDifferent(
        DifferentialComparator.compare(
            GROUPED, GROUPED, rows(row("WA", 3L)), rows(row("WA", 3L), row("VA", 5L))));
  }

  @Test
  public void genuineSchemaDrift() {
    assertDifferent(
        DifferentialComparator.compare(
            METRIC,
            new Schema(List.of("avg(balance)"), List.of("float")),
            rows(row(1.0)),
            rows(row(1.0))));
  }

  private static void assertEqual(Result r) {
    assertTrue(r.reason(), r.equal());
  }

  private static void assertDifferent(Result r) {
    assertFalse("expected the comparator to surface this divergence", r.equal());
  }

  private static List<List<Object>> rows(List<Object>... rs) {
    return List.of(rs);
  }

  private static List<Object> row(Object... cells) {
    return Arrays.asList(cells);
  }
}
