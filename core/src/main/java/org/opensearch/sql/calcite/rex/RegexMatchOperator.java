/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.calcite.rex;

import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlSyntax;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;

/**
 * Custom Calcite operator for PCRE2 regex matching. This operator represents our RegexMatch
 * expression and ensures PCRE2 support when pushed down to the script engine.
 */
public class RegexMatchOperator extends SqlOperator {

  public static final RegexMatchOperator INSTANCE = new RegexMatchOperator();

  private RegexMatchOperator() {
    super(
        "REGEX_MATCH",
        SqlKind.OTHER_FUNCTION,
        20,
        false,
        ReturnTypes.BOOLEAN,
        null,
        OperandTypes.STRING_STRING);
  }

  @Override
  public SqlSyntax getSyntax() {
    return SqlSyntax.FUNCTION;
  }
}
