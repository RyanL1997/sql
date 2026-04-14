/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.lint;

import lombok.Builder;
import lombok.Getter;

/** A diagnostic message produced by a lint rule. */
@Getter
@Builder
public class Diagnostic {

  public enum Severity {
    WARNING,
    INFO
  }

  private final Severity severity;
  private final String message;
  private final String suggestion;
  /** The PPL command name that triggered this diagnostic (e.g. "head", "stats"). */
  private final String command;
}
