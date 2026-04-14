/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.lint;

import java.util.List;
import org.opensearch.sql.ast.tree.UnresolvedPlan;

/** Interface for a single lint rule that inspects an AST and returns diagnostics. */
public interface LintRule {

  /** A short identifier for this rule, e.g. "head-without-sort". */
  String id();

  /** Inspect the given AST and return any diagnostics found. */
  List<Diagnostic> check(UnresolvedPlan plan);
}
