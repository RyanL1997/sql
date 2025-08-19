/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.expression.function.udf;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.apache.calcite.adapter.enumerable.NotNullImplementor;
import org.apache.calcite.adapter.enumerable.NullPolicy;
import org.apache.calcite.adapter.enumerable.RexImpTable;
import org.apache.calcite.adapter.enumerable.RexToLixTranslator;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.linq4j.tree.Types;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.schema.impl.ScalarFunctionImpl;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.type.SqlReturnTypeInference;
import org.opensearch.sql.expression.function.ImplementorUDF;
import org.opensearch.sql.expression.function.UDFOperandMetadata;

/**
 * REX_EXTRACT UDF implementation for Calcite engine. This function extracts specific named 
 * capture groups from text using Java regex patterns.
 */
public class RexExtractFunctionImpl extends ImplementorUDF {

  public RexExtractFunctionImpl() {
    super(new RexExtractImplementor(), NullPolicy.ANY);
  }

  @Override
  public SqlReturnTypeInference getReturnTypeInference() {
    return ReturnTypes.VARCHAR_2000_NULLABLE;
  }

  @Override
  public UDFOperandMetadata getOperandMetadata() {
    return null;
  }

  public static class RexExtractImplementor implements NotNullImplementor {
    @Override
    public Expression implement(
        RexToLixTranslator translator, RexCall call, List<Expression> translatedOperands) {
      ScalarFunctionImpl function =
          (ScalarFunctionImpl)
              ScalarFunctionImpl.create(
                  Types.lookupMethod(
                      RexExtractFunctionImpl.class, "eval", String.class, String.class, String.class));
      return function.getImplementor().implement(translator, call, RexImpTable.NullAs.NULL);
    }
  }

  /**
   * Evaluation method for REX_EXTRACT function. This method is called by Calcite's generated code
   * during execution.
   *
   * @param field The field value to extract from
   * @param pattern The Java regex pattern with named capture groups
   * @param groupName The name of the capture group to extract
   * @return String value of the extracted group, or null if no match or group not found
   */
  public static String eval(String field, String pattern, String groupName) {
    if (field == null || pattern == null || groupName == null) {
      return null;
    }

    try {
      Pattern compiledPattern = Pattern.compile(pattern);
      Matcher matcher = compiledPattern.matcher(field);
      
      if (matcher.find()) {
        try {
          return matcher.group(groupName);
        } catch (IllegalArgumentException e) {
          // Named group doesn't exist
          return null;
        }
      }
      return null; // No match found
    } catch (PatternSyntaxException e) {
      throw new IllegalArgumentException("Invalid regex pattern: " + e.getMessage());
    }
  }

  /**
   * Overloaded eval method with max_match parameter.
   *
   * @param field The field value to extract from
   * @param pattern The Java regex pattern with named capture groups
   * @param maxMatch Maximum number of matches to process (currently ignored, defaults to first match)
   * @param groupName The name of the capture group to extract
   * @return String value of the extracted group, or null if no match or group not found
   */
  public static String eval(String field, String pattern, Integer maxMatch, String groupName) {
    // For now, we only support the first match, but maxMatch parameter is ready for future extension
    return eval(field, pattern, groupName);
  }
}