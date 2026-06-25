/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.calcite.combination;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.Test;
import org.opensearch.sql.opensearch.storage.scan.context.PushDownType;

/**
 * Cluster-free validation of {@link PushdownShapeOracle} and the command→{@link PushDownType} map.
 * Plain JUnit4 (no cluster); reads the real golden + benchmark resources from the test classpath.
 *
 * <p>These assertions cover the oracle's parsing, bidirectional loss/gain detection, the
 * golden-corpus closed vocabulary, and the command→token map — and run in CI.
 */
public class PushdownShapeOracleIT {

  // A pushed-down dedup-after-where: FILTER + AGGREGATION(LITERAL_AGG) + LIMIT, nothing on
  // coordinator.
  private static final String HEALTHY_DEDUP =
      "CalciteEnumerableIndexScan(table=[[OpenSearch, opensearch-sql_test_index_bank]], "
          + "PushDownContext=[[FILTER->>($8, 25), "
          + "AGGREGATION->rel#:LogicalAggregate(group={7}, agg#0=LITERAL_AGG(1)), LIMIT->10], "
          + "OpenSearchRequestBuilder(sourceBuilder={})])";

  // #5488: dedup fell back to an in-memory ROW_NUMBER window; the AGGREGATION token is gone.
  private static final String REGRESSED_DEDUP =
      "EnumerableCalc($condition=[<=($9, 1)])\n"
          + "  EnumerableWindow(window#0=[ROW_NUMBER()])\n"
          + "    CalciteEnumerableIndexScan(table=[[OpenSearch, opensearch-sql_test_index_bank]],"
          + " PushDownContext=[[FILTER->>($8, 25), LIMIT->10000],"
          + " OpenSearchRequestBuilder(sourceBuilder={})])";

  // #3387-style gain: an eval+where now pushes as a SCRIPT filter (undeclared).
  private static final String IMPROVED_EVAL =
      "CalciteEnumerableIndexScan(table=[[OpenSearch, opensearch-sql_test_index_bank]],"
          + " PushDownContext=[[SCRIPT->>(+($1, 1), 10), LIMIT->10000],"
          + " OpenSearchRequestBuilder(sourceBuilder={})])";

  @Test
  public void parsesPushedTokensFromRealFormat() {
    assertEquals(
        EnumSet.of(PushDownType.FILTER, PushDownType.AGGREGATION, PushDownType.LIMIT),
        PushdownShapeOracle.pushedTokens(HEALTHY_DEDUP));
  }

  @Test
  public void verifiesHealthyShape() {
    Set<PushDownType> expected =
        EnumSet.of(PushDownType.FILTER, PushDownType.AGGREGATION, PushDownType.LIMIT);
    assertTrue(
        PushdownShapeOracle.verify(expected, HEALTHY_DEDUP).message(),
        PushdownShapeOracle.verify(expected, HEALTHY_DEDUP).ok());
  }

  @Test
  public void detectsPushdownLoss_issue5488() {
    Set<PushDownType> expected =
        EnumSet.of(PushDownType.FILTER, PushDownType.AGGREGATION, PushDownType.LIMIT);
    PushdownShapeOracle.Verdict v = PushdownShapeOracle.verify(expected, REGRESSED_DEDUP);
    assertFalse("must detect the lost AGGREGATION pushdown", v.ok());
    assertTrue(v.message(), v.message().contains("LOST") && v.message().contains("AGGREGATION"));
  }

  @Test
  public void detectsPushdownGain_issue3387() {
    // Declared expectation: eval breaks reachability -> only the system LIMIT.
    Set<PushDownType> expected = EnumSet.of(PushDownType.LIMIT);
    PushdownShapeOracle.Verdict v = PushdownShapeOracle.verify(expected, IMPROVED_EVAL);
    assertFalse("must detect the undeclared SCRIPT gain", v.ok());
    assertTrue(v.message(), v.message().contains("GAIN") && v.message().contains("SCRIPT"));
  }

  /**
   * Every {@code TYPE->} token across the whole calcite golden corpus parses as a known
   * PushDownType.
   */
  @Test
  public void closedVocabularyAcrossGoldenCorpus() throws IOException, URISyntaxException {
    Path dir = resourceDir("/expectedOutput/calcite");
    int files = 0, withToken = 0;
    try (Stream<Path> walk = Files.walk(dir)) {
      for (Path p : (Iterable<Path>) walk.filter(x -> x.toString().endsWith(".yaml"))::iterator) {
        files++;
        Set<PushDownType> toks = PushdownShapeOracle.pushedTokens(Files.readString(p));
        if (!toks.isEmpty()) withToken++;
        // PushdownShapeOracle.pushedTokens only emits valid PushDownType values by construction; a
        // raw "[A-Z_]+->" that is NOT a PushDownType would be a vocabulary drift -> assert none.
        Set<String> raw = rawArrowTokens(Files.readString(p));
        raw.removeAll(Stream.of(PushDownType.values()).map(Enum::name).toList());
        assertTrue("unknown TYPE-> token(s) in " + p.getFileName() + ": " + raw, raw.isEmpty());
      }
    }
    assertTrue("expected many golden files", files > 100);
    assertTrue("expected most goldens to carry a pushed token", withToken > files / 2);
  }

  /** Every pushed token in a benchmark golden is explainable by some command in its query. */
  @Test
  public void commandTokenMapExplainsBenchmarkGoldens() throws IOException, URISyntaxException {
    int checked = 0;
    for (String corpus : List.of("big5", "clickbench")) {
      Path q = resourceDir("/" + corpus + "/queries");
      Path g = resourceDir("/expectedOutput/calcite/" + corpus);
      try (Stream<Path> walk = Files.list(q)) {
        for (Path ppl :
            (Iterable<Path>) walk.filter(x -> x.toString().endsWith(".ppl")).sorted()::iterator) {
          String base = ppl.getFileName().toString().replaceFirst("\\.ppl$", "");
          Path golden = g.resolve(base + ".yaml");
          if (!Files.isRegularFile(golden)) continue;
          checked++;
          Set<PushDownType> possible = EnumSet.of(PushDownType.LIMIT, PushDownType.PROJECT);
          for (String cmd : commandsOf(sanitize(Files.readString(ppl)))) {
            possible.addAll(POSSIBLE.getOrDefault(cmd, Set.of()));
          }
          Set<PushDownType> actual = PushdownShapeOracle.pushedTokens(Files.readString(golden));
          actual.removeAll(possible);
          assertTrue(
              corpus + "/" + base + ": token(s) no command can explain: " + actual,
              actual.isEmpty());
        }
      }
    }
    assertTrue("expected to check benchmark goldens", checked > 50);
  }

  // ---- helpers --------------------------------------------------------------------------------

  private static final Map<String, Set<PushDownType>> POSSIBLE =
      Map.ofEntries(
          Map.entry("where", EnumSet.of(PushDownType.FILTER, PushDownType.SCRIPT)),
          Map.entry("search", EnumSet.of(PushDownType.FILTER, PushDownType.HIGHLIGHT)),
          Map.entry("fields", EnumSet.of(PushDownType.PROJECT)),
          Map.entry("table", EnumSet.of(PushDownType.PROJECT)),
          Map.entry("eval", EnumSet.of(PushDownType.PROJECT)),
          Map.entry("stats", EnumSet.of(PushDownType.AGGREGATION, PushDownType.PROJECT)),
          Map.entry(
              "chart",
              EnumSet.of(
                  PushDownType.AGGREGATION, PushDownType.PROJECT, PushDownType.SORT_AGG_METRICS)),
          Map.entry(
              "timechart",
              EnumSet.of(
                  PushDownType.AGGREGATION, PushDownType.PROJECT, PushDownType.SORT_AGG_METRICS)),
          Map.entry("dedup", EnumSet.of(PushDownType.AGGREGATION)),
          Map.entry(
              "sort",
              EnumSet.of(PushDownType.SORT, PushDownType.SORT_EXPR, PushDownType.SORT_AGG_METRICS)),
          Map.entry("head", EnumSet.of(PushDownType.LIMIT)),
          Map.entry("top", EnumSet.of(PushDownType.RARE_TOP, PushDownType.AGGREGATION)),
          Map.entry("rare", EnumSet.of(PushDownType.RARE_TOP, PushDownType.AGGREGATION)));

  private static final Pattern RAW_ARROW = Pattern.compile("\\b([A-Z][A-Z_]{2,})->");

  private static Set<String> rawArrowTokens(String s) {
    Set<String> r = new TreeSet<>();
    Matcher m = RAW_ARROW.matcher(s);
    while (m.find()) r.add(m.group(1));
    return r;
  }

  private static String sanitize(String ppl) {
    return ppl.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("\\s+", " ").trim();
  }

  private static Set<String> commandsOf(String ppl) {
    Set<String> cmds = new TreeSet<>();
    for (String seg : ppl.split("\\|")) {
      String s = seg.trim();
      if (s.isEmpty()) continue;
      if (s.matches("(?i)^(source|search|index)\\b.*")) {
        cmds.add("search");
        continue;
      }
      Matcher m = Pattern.compile("^([A-Za-z_]+)").matcher(s);
      if (m.find()) cmds.add(m.group(1).toLowerCase());
    }
    return cmds;
  }

  private static Path resourceDir(String classpath) throws URISyntaxException {
    var url = PushdownShapeOracleIT.class.getResource(classpath);
    if (url == null) throw new IllegalStateException("resource not on classpath: " + classpath);
    return Path.of(url.toURI());
  }
}
