/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.lint.rules;

import java.util.ArrayList;
import java.util.List;
import org.opensearch.sql.ast.AbstractNodeVisitor;
import org.opensearch.sql.ast.Node;
import org.opensearch.sql.ast.tree.Head;
import org.opensearch.sql.ast.tree.Sort;
import org.opensearch.sql.ast.tree.UnresolvedPlan;
import org.opensearch.sql.lint.Diagnostic;
import org.opensearch.sql.lint.Diagnostic.Severity;
import org.opensearch.sql.lint.LintRule;

/**
 * Warns when {@code head} is used without a preceding {@code sort}. Without an explicit sort the
 * returned rows are arbitrary and may differ between runs.
 */
public class HeadWithoutSortRule implements LintRule {

  @Override
  public String id() {
    return "head-without-sort";
  }

  @Override
  public List<Diagnostic> check(UnresolvedPlan plan) {
    List<Diagnostic> diagnostics = new ArrayList<>();
    plan.accept(new Checker(diagnostics), null);
    return diagnostics;
  }

  private static class Checker extends AbstractNodeVisitor<Void, Void> {
    private final List<Diagnostic> diagnostics;
    private boolean sawSort = false;

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
    public Void visitSort(Sort node, Void context) {
      sawSort = true;
      return visitChildren(node, context);
    }

    @Override
    public Void visitHead(Head node, Void context) {
      if (!sawSort) {
        diagnostics.add(
            Diagnostic.builder()
                .severity(Severity.WARNING)
                .command("head")
                .message(
                    "head without a preceding sort returns an arbitrary subset of rows."
                        + " Results may differ between executions.")
                .suggestion("Add '| sort <field>' before '| head' for deterministic results.")
                .build());
      }
      return visitChildren(node, context);
    }
  }
}
