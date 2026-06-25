/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.calcite.combination;

import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.opensearch.sql.opensearch.storage.scan.context.PushDownType;

/**
 * The shape oracle: parses a physical explain plan into the set of pushed {@link PushDownType}
 * tokens and the coordinator {@code Enumerable*} nodes, and verifies an expected token set
 * bidirectionally — a missing token is a pushdown <b>loss</b> (the #5488 class), an extra token is
 * an undeclared <b>gain</b> (the #3387 class).
 *
 * <p>The token vocabulary is derived from the real {@link PushDownType} enum, so it tracks the
 * production set automatically. The {@code PushDownContext} token is the load-bearing signal; the
 * {@code Enumerable*} node is a best-effort secondary corroboration only (node names vary — e.g. an
 * unpushed sort can surface as {@code CalciteEnumerableTopK}, which a naive {@code \bEnumerable}
 * scan does not even match).
 */
public final class PushdownShapeOracle {

  private PushdownShapeOracle() {}

  // Enum names longest-first so SORT_AGG_METRICS / SORT_EXPR win over SORT.
  private static final Pattern TOKEN =
      Pattern.compile(
          "\\b("
              + Arrays.stream(PushDownType.values())
                  .map(Enum::name)
                  .sorted(Comparator.comparingInt(String::length).reversed())
                  .reduce((a, b) -> a + "|" + b)
                  .orElseThrow()
              + ")->");

  // \b keeps the Calcite-prefixed pushed scan nodes (CalciteEnumerable*) out of the coordinator
  // set.
  private static final Pattern ENUMERABLE = Pattern.compile("\\bEnumerable[A-Za-z]+");

  /**
   * The set of {@link PushDownType} operators pushed into the scan (parsed from PushDownContext).
   */
  public static Set<PushDownType> pushedTokens(String physical) {
    Set<PushDownType> tokens = EnumSet.noneOf(PushDownType.class);
    Matcher m = TOKEN.matcher(physical);
    while (m.find()) tokens.add(PushDownType.valueOf(m.group(1)));
    return tokens;
  }

  /** True if {@code type} was pushed (its {@code TYPE->} token is present). */
  public static boolean pushed(String physical, PushDownType type) {
    return physical.contains(type.name() + "->");
  }

  /** Plain coordinator {@code Enumerable*} node types above the scan (secondary signal only). */
  public static Set<String> coordinatorNodes(String physical) {
    Set<String> nodes = new TreeSet<>();
    Matcher m = ENUMERABLE.matcher(physical);
    while (m.find()) nodes.add(m.group());
    return nodes;
  }

  public record Verdict(boolean ok, String message) {}

  /**
   * Verify the actual pushed-token set equals {@code expected}, bidirectionally.
   *
   * @return a failing verdict naming the missing tokens (pushdown loss) or extra tokens (undeclared
   *     gain), or an OK verdict.
   */
  public static Verdict verify(Set<PushDownType> expected, String physical) {
    Set<PushDownType> actual = pushedTokens(physical);
    Set<PushDownType> missing =
        EnumSet.copyOf(expected.isEmpty() ? EnumSet.noneOf(PushDownType.class) : expected);
    missing.removeAll(actual);
    Set<PushDownType> extra =
        actual.isEmpty() ? EnumSet.noneOf(PushDownType.class) : EnumSet.copyOf(actual);
    extra.removeAll(expected);
    if (!missing.isEmpty()) {
      return new Verdict(
          false,
          "PUSHDOWN LOST — expected "
              + missing
              + " not pushed; coordinator="
              + coordinatorNodes(physical));
    }
    if (!extra.isEmpty()) {
      return new Verdict(
          false,
          "PUSHDOWN GAIN — undeclared " + extra + " now pushed; declare it in the CommandSpec");
    }
    return new Verdict(true, "shape OK — pushed " + actual);
  }
}
