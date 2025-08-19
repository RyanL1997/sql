/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.expression.parse;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.sql.data.model.ExprStringValue;
import org.opensearch.sql.data.model.ExprValue;
import org.opensearch.sql.data.model.ExprValueUtils;
import org.opensearch.sql.data.type.ExprCoreType;
import org.opensearch.sql.data.type.ExprType;
import org.opensearch.sql.exception.ExpressionEvaluationException;
import org.opensearch.sql.expression.Expression;
import org.opensearch.sql.expression.ExpressionNodeVisitor;
import org.opensearch.sql.expression.FunctionExpression;
import org.opensearch.sql.expression.env.Environment;
import org.opensearch.sql.expression.function.FunctionName;

/** RexExpression for field extraction using regex with named capture groups. */
@EqualsAndHashCode(callSuper = false)
@ToString
public class RexExpression extends FunctionExpression {
  private static final Logger log = LogManager.getLogger(RexExpression.class);
  private static final Pattern GROUP_PATTERN = Pattern.compile("\\(\\?<([a-zA-Z][a-zA-Z0-9]*)>");
  
  @Getter protected final Expression sourceField;
  protected final Expression pattern;
  @Getter @EqualsAndHashCode.Exclude private final Pattern regexPattern;
  @Getter private final int maxMatch;
  @Getter private final String offsetField;

  /**
   * RexExpression.
   *
   * @param sourceField source text field
   * @param pattern pattern used for parsing with named capture groups
   * @param maxMatch maximum number of matches (default 1)
   * @param offsetField field name for storing offsets (optional)
   */
  public RexExpression(Expression sourceField, Expression pattern, int maxMatch, String offsetField) {
    super(FunctionName.of("rex"), ImmutableList.of(sourceField, pattern));
    this.sourceField = sourceField;
    this.pattern = pattern;
    this.regexPattern = Pattern.compile(pattern.valueOf().stringValue());
    this.maxMatch = maxMatch;
    this.offsetField = offsetField;
  }

  /**
   * RexExpression with default parameters.
   *
   * @param sourceField source text field
   * @param pattern pattern used for parsing with named capture groups
   */
  public RexExpression(Expression sourceField, Expression pattern) {
    this(sourceField, pattern, 1, null);
  }

  @Override
  public ExprValue valueOf(Environment<Expression, ExprValue> valueEnv) {
    ExprValue value = valueEnv.resolve(sourceField);
    if (value.isNull() || value.isMissing()) {
      return ExprValueUtils.nullValue();
    }
    try {
      return extractFields(value);
    } catch (ExpressionEvaluationException e) {
      throw new ExpressionEvaluationException(
          String.format("failed to extract fields from \"%s\" with pattern [%s]", 
                       sourceField, regexPattern.pattern()));
    }
  }

  @Override
  public ExprType type() {
    return ExprCoreType.STRUCT; // Returns multiple fields
  }

  @Override
  public <T, C> T accept(ExpressionNodeVisitor<T, C> visitor, C context) {
    return visitor.visitRex(this, context);
  }

  /**
   * Extract fields using regex pattern with named capture groups.
   * Returns a struct containing all extracted field values.
   */
  private ExprValue extractFields(ExprValue value) throws ExpressionEvaluationException {
    String rawString = value.stringValue();
    ImmutableMap.Builder<String, Object> resultBuilder = ImmutableMap.builder();
    
    Matcher matcher = regexPattern.matcher(rawString);
    List<String> namedGroups = getNamedGroupCandidates(regexPattern.pattern());
    
    int matchCount = 0;
    while (matcher.find() && (maxMatch == 0 || matchCount < maxMatch)) {
      for (String groupName : namedGroups) {
        try {
          String groupValue = matcher.group(groupName);
          if (groupValue != null) {
            // For multiple matches, we could append to arrays
            // For now, keep it simple and use the last match
            resultBuilder.put(groupName, groupValue);
            
            // Add offset information if requested
            if (offsetField != null && !offsetField.isEmpty()) {
              int start = matcher.start(groupName);
              int end = matcher.end(groupName);
              resultBuilder.put(offsetField, start + "-" + (end - 1));
            }
          }
        } catch (IllegalArgumentException e) {
          // Group doesn't exist in this match, skip
          log.debug("Named group {} not found in match", groupName);
        }
      }
      matchCount++;
    }
    
    if (resultBuilder.build().isEmpty()) {
      log.debug("failed to extract any fields from pattern {} from input ***", regexPattern.pattern());
    }
    
    return ExprValueUtils.tupleValue(resultBuilder.build());
  }

  /**
   * Get list of derived fields based on parse pattern.
   *
   * @param pattern pattern used for parsing
   * @return list of names of the derived fields
   */
  public static List<String> getNamedGroupCandidates(String pattern) {
    ImmutableList.Builder<String> namedGroups = ImmutableList.builder();
    Matcher m = GROUP_PATTERN.matcher(pattern);
    while (m.find()) {
      namedGroups.add(m.group(1));
    }
    return namedGroups.build();
  }

  /**
   * Get the regex pattern string.
   */
  public String getPatternString() {
    return regexPattern.pattern();
  }
}