/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.lint;

import java.util.ArrayList;
import java.util.List;
import org.opensearch.sql.ast.tree.UnresolvedPlan;
import org.opensearch.sql.lint.rules.HeadWithoutSortRule;
import org.opensearch.sql.lint.rules.StatsWithoutFilterRule;

/** Runs all registered lint rules against a parsed PPL AST. */
public class QueryLinter {

  private final List<LintRule> rules;

  public QueryLinter() {
    this.rules = List.of(new HeadWithoutSortRule(), new StatsWithoutFilterRule());
  }

  public QueryLinter(List<LintRule> rules) {
    this.rules = rules;
  }

  /** Lint the given plan and return all diagnostics from all rules. */
  public List<Diagnostic> lint(UnresolvedPlan plan) {
    List<Diagnostic> diagnostics = new ArrayList<>();
    for (LintRule rule : rules) {
      diagnostics.addAll(rule.check(plan));
    }
    return diagnostics;
  }
}
