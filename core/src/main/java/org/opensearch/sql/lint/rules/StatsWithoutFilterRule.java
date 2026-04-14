/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.lint.rules;

import java.util.ArrayList;
import java.util.List;
import org.opensearch.sql.ast.AbstractNodeVisitor;
import org.opensearch.sql.ast.Node;
import org.opensearch.sql.ast.tree.Aggregation;
import org.opensearch.sql.ast.tree.Filter;
import org.opensearch.sql.ast.tree.UnresolvedPlan;
import org.opensearch.sql.lint.Diagnostic;
import org.opensearch.sql.lint.Diagnostic.Severity;
import org.opensearch.sql.lint.LintRule;

/**
 * Warns when {@code stats} aggregation runs without a preceding {@code where} filter. This
 * typically means the aggregation processes the entire index, which can be expensive on large
 * datasets.
 */
public class StatsWithoutFilterRule implements LintRule {

  @Override
  public String id() {
    return "stats-without-filter";
  }

  @Override
  public List<Diagnostic> check(UnresolvedPlan plan) {
    List<Diagnostic> diagnostics = new ArrayList<>();
    plan.accept(new Checker(diagnostics), null);
    return diagnostics;
  }

  private static class Checker extends AbstractNodeVisitor<Void, Void> {
    private final List<Diagnostic> diagnostics;
    private boolean sawFilter = false;

    Checker(List<Diagnostic> diagnostics) {
      this.diagnostics = diagnostics;
    }

    @Override
    public Void visitChildren(Node node, Void context) {
      for (Node child : node.getChild()) {
        child.accept(this, context);
      }
      return null;
    }

    @Override
    public Void visitFilter(Filter node, Void context) {
      sawFilter = true;
      return visitChildren(node, context);
    }

    @Override
    public Void visitAggregation(Aggregation node, Void context) {
      if (!sawFilter) {
        diagnostics.add(
            Diagnostic.builder()
                .severity(Severity.WARNING)
                .command("stats")
                .message(
                    "stats aggregation without a preceding where filter will process the entire"
                        + " index. This can be slow on large datasets.")
                .suggestion(
                    "Add '| where <condition>' before '| stats' to limit the data scanned.")
                .build());
      }
      return visitChildren(node, context);
    }
  }
}
