/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.calcite.combination;

import java.util.List;
import java.util.Objects;

/**
 * The differential oracle's comparator: compares a query's results with pushdown ON vs OFF and
 * decides whether they are equal up to the tolerable divergences (floating-point accumulation, row
 * ordering) while still surfacing the genuine ones (an empty-aggregate {@code 0} vs {@code null}, a
 * range-bucket key {@code "null"} vs {@code null}, a different/missing row, or schema drift).
 *
 * <p>Comparison is: a strict schema-equality precondition, then an order-insensitive multiset match
 * of rows with per-cell ULP tolerance for numbers and exact equality for everything else. See the
 * design doc divergence taxonomy for the query shapes that must be excluded upstream (e.g. an
 * unstable {@code head}/{@code dedup} without a total-order sort).
 */
public final class DifferentialComparator {

  private DifferentialComparator() {}

  /** Absolute floor so values near zero are not held to an impossibly tight ULP bound. */
  static final double ABSOLUTE_TOLERANCE = 1e-10;

  /** Numbers within this many ULPs are considered equal (covers AVG = SUM0/COUNT decomposition). */
  static final int ULP_TOLERANCE_FACTOR = 4;

  /** A result schema: column names and their types, in order. */
  public record Schema(List<String> names, List<String> types) {}

  /** The outcome of a comparison: equal, or not with a human-readable reason. */
  public record Result(boolean equal, String reason) {}

  /**
   * Compare two result sets. Rows are matched as a multiset (order-insensitive) with per-cell ULP
   * tolerance; schema must be identical.
   */
  public static Result compare(
      Schema on, Schema off, List<List<Object>> rowsOn, List<List<Object>> rowsOff) {
    if (!on.names().equals(off.names()) || !on.types().equals(off.types())) {
      return new Result(false, "schema drift: " + on + " vs " + off);
    }
    if (rowsOn.size() != rowsOff.size()) {
      return new Result(false, "row count differs: " + rowsOn.size() + " vs " + rowsOff.size());
    }
    boolean[] used = new boolean[rowsOff.size()];
    for (List<Object> rowOn : rowsOn) {
      int match = -1;
      for (int j = 0; j < rowsOff.size(); j++) {
        if (!used[j] && rowsMatch(rowOn, rowsOff.get(j))) {
          match = j;
          break;
        }
      }
      if (match < 0) {
        return new Result(false, "no tolerant match for row " + rowOn);
      }
      used[match] = true;
    }
    return new Result(true, "equal (within ULP, order-insensitive)");
  }

  /**
   * Order-sensitive comparison for pipelines that end in a total-order sort: rows must match
   * position-by-position (with per-cell ULP tolerance). Unlike {@link #compare}, this catches a
   * wrong <i>ordering</i>, not just a wrong row set — use it only when the query's sort fully
   * determines the order (no ties), otherwise legitimate tie reordering causes false positives.
   */
  public static Result compareInOrder(
      Schema on, Schema off, List<List<Object>> rowsOn, List<List<Object>> rowsOff) {
    if (!on.names().equals(off.names()) || !on.types().equals(off.types())) {
      return new Result(false, "schema drift: " + on + " vs " + off);
    }
    if (rowsOn.size() != rowsOff.size()) {
      return new Result(false, "row count differs: " + rowsOn.size() + " vs " + rowsOff.size());
    }
    for (int i = 0; i < rowsOn.size(); i++) {
      if (!rowsMatch(rowsOn.get(i), rowsOff.get(i))) {
        return new Result(
            false, "row " + i + " differs: " + rowsOn.get(i) + " vs " + rowsOff.get(i));
      }
    }
    return new Result(true, "equal in order (within ULP)");
  }

  static boolean rowsMatch(List<Object> a, List<Object> b) {
    if (a.size() != b.size()) {
      return false;
    }
    for (int i = 0; i < a.size(); i++) {
      if (!cellsMatch(a.get(i), b.get(i))) {
        return false;
      }
    }
    return true;
  }

  /**
   * Numbers compared within ULP; everything else exact, so {@code 0 != null} and {@code "null" !=
   * null}.
   */
  static boolean cellsMatch(Object a, Object b) {
    if (a instanceof Number na && b instanceof Number nb) {
      double x = na.doubleValue();
      double y = nb.doubleValue();
      double tolerance =
          Math.max(ABSOLUTE_TOLERANCE, ULP_TOLERANCE_FACTOR * Math.max(Math.ulp(x), Math.ulp(y)));
      return Math.abs(x - y) <= tolerance;
    }
    return Objects.equals(a, b);
  }
}
