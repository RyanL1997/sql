/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.expression.function.udf;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.calcite.adapter.enumerable.NotNullImplementor;
import org.apache.calcite.adapter.enumerable.NullPolicy;
import org.apache.calcite.adapter.enumerable.RexToLixTranslator;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.type.SqlReturnTypeInference;
import org.opensearch.sql.calcite.utils.PPLOperandTypes;
import org.opensearch.sql.expression.function.ImplementorUDF;
import org.opensearch.sql.expression.function.UDFOperandMetadata;

/** Custom REX_OFFSET function for calculating regex match positions. */
public final class RexOffsetFunction extends ImplementorUDF {

  public RexOffsetFunction() {
    super(new RexOffsetImplementor(), NullPolicy.ARG0);
  }

  @Override
  public SqlReturnTypeInference getReturnTypeInference() {
    return ReturnTypes.VARCHAR_2000_NULLABLE;
  }

  @Override
  public UDFOperandMetadata getOperandMetadata() {
    return PPLOperandTypes.STRING_STRING_INTEGER;
  }

  private static class RexOffsetImplementor implements NotNullImplementor {

    @Override
    public Expression implement(
        RexToLixTranslator translator, RexCall call, List<Expression> translatedOperands) {
      Expression field = translatedOperands.get(0);
      Expression pattern = translatedOperands.get(1);
      Expression maxMatch = translatedOperands.get(2);

      return Expressions.call(RexOffsetFunction.class, "calculateOffsets", field, pattern, maxMatch);
    }
  }

  public static String calculateOffsets(String text, String patternStr, int maxMatch) {
    if (text == null || patternStr == null) {
      return null;
    }

    try {
      Pattern pattern = Pattern.compile(patternStr);
      Matcher matcher = pattern.matcher(text);

      List<String> allOffsetPairs = new java.util.ArrayList<>();

      Pattern namedGroupPattern = Pattern.compile("\\(\\?<([^>]+)>");
      Matcher namedGroupMatcher = namedGroupPattern.matcher(patternStr);
      List<String> groupNames = new java.util.ArrayList<>();
      while (namedGroupMatcher.find()) {
        groupNames.add(namedGroupMatcher.group(1));
      }

      if (groupNames.isEmpty()) {
        return null;
      }

      // Find matches up to maxMatch limit and collect offsets
      int matchCount = 0;
      int maxMatchLimit = (maxMatch > 0) ? maxMatch : Integer.MAX_VALUE;
      
      while (matcher.find() && matchCount < maxMatchLimit) {
        for (int groupIndex = 1; groupIndex <= matcher.groupCount() && groupIndex <= groupNames.size(); groupIndex++) {
          String groupName = groupNames.get(groupIndex - 1);
          int start = matcher.start(groupIndex);
          int end = matcher.end(groupIndex);

          if (start >= 0 && end >= 0) {
            allOffsetPairs.add(groupName + "=" + start + "-" + (end - 1));
          }
        }
        matchCount++;
      }

      return allOffsetPairs.isEmpty() ? null : String.join("&", allOffsetPairs);
    } catch (Exception e) {
      return null;
    }
  }
}
