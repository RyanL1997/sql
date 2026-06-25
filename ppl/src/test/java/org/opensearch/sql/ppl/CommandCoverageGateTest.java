/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.ppl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.Test;
import org.opensearch.sql.ppl.antlr.parser.OpenSearchPPLParser;

/**
 * Coverage gate (goal C): the canonical PPL command set is derived by reflecting the generated
 * parser's {@code ruleNames} — i.e. the <b>active</b> grammar that the build actually compiles, not
 * the stale {@code language-grammar/} copy. Every command rule must have an entry in the declared
 * manifest below.
 *
 * <p>When a command is added to or removed from the grammar, {@code ruleNames} changes and this
 * test fails until {@link #DECLARED} is updated — the forcing function that makes the author
 * consciously give the new command combination + pushdown coverage. (A fuller version would key the
 * manifest to a resource carrying each command's schema-effect and pushdown expectation; this
 * asserts existence.)
 */
public class CommandCoverageGateTest {

  /**
   * The declared command set (the manifest). A new/renamed grammar command rule must be added here.
   */
  private static final Set<String> DECLARED =
      new TreeSet<>(
          Set.of(
              "adCommand",
              "addcoltotalsCommand",
              "addtotalsCommand",
              "appendCommand",
              "appendPipeCommand",
              "appendcolCommand",
              "binCommand",
              "chartCommand",
              "convertCommand",
              "dedupCommand",
              "describeCommand",
              "evalCommand",
              "eventstatsCommand",
              "expandCommand",
              "fieldformatCommand",
              "fieldsCommand",
              "fillnullCommand",
              "flattenCommand",
              "graphLookupCommand",
              "grokCommand",
              "headCommand",
              "joinCommand",
              "kmeansCommand",
              "lookupCommand",
              "mlCommand",
              "multisearchCommand",
              "mvcombineCommand",
              "mvexpandCommand",
              "nomvCommand",
              "parseCommand",
              "patternsCommand",
              "rareTopCommand",
              "regexCommand",
              "renameCommand",
              "replaceCommand",
              "reverseCommand",
              "rexCommand",
              "searchCommand",
              "showDataSourcesCommand",
              "sortCommand",
              "spathCommand",
              "statsCommand",
              "streamstatsCommand",
              "tableCommand",
              "timechartCommand",
              "transposeCommand",
              "trendlineCommand",
              "unionCommand",
              "whereCommand"));

  private static Set<String> grammarCommandRules() {
    return Arrays.stream(OpenSearchPPLParser.ruleNames)
        .filter(name -> name.endsWith("Command"))
        .collect(Collectors.toCollection(TreeSet::new));
  }

  @Test
  public void everyGrammarCommandIsDeclaredAndNoStaleEntries() {
    Set<String> grammar = grammarCommandRules();

    Set<String> uncovered = new TreeSet<>(grammar);
    uncovered.removeAll(DECLARED);
    assertTrue(
        "new/renamed grammar command(s) with no coverage manifest entry — add them to DECLARED and"
            + " give them combination + pushdown coverage: "
            + uncovered,
        uncovered.isEmpty());

    Set<String> stale = new TreeSet<>(DECLARED);
    stale.removeAll(grammar);
    assertTrue(
        "manifest entries no longer present in the grammar (stale): " + stale, stale.isEmpty());

    assertEquals(grammar, DECLARED);
  }

  @Test
  public void sourcedFromActiveGrammar() {
    // The active grammar has ~49 command rules; the stale language-grammar copy has ~24. A count
    // well below the active floor means tooling was pointed at the wrong grammar.
    assertTrue(
        "expected the active grammar's command rules (>=40), got " + grammarCommandRules().size(),
        grammarCommandRules().size() >= 40);
  }
}
