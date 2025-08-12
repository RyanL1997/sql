/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.expression.operator.predicate;

import java.util.concurrent.ConcurrentHashMap;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.opensearch.sql.data.model.ExprValue;
import org.opensearch.sql.data.model.ExprValueUtils;
import org.opensearch.sql.data.type.ExprCoreType;
import org.opensearch.sql.data.type.ExprType;
import org.opensearch.sql.expression.Expression;
import org.opensearch.sql.expression.ExpressionNodeVisitor;
import org.opensearch.sql.expression.env.Environment;
import org.pcre4j.Pcre4j;
import org.pcre4j.jna.Pcre2;
import org.pcre4j.regex.Matcher;
import org.pcre4j.regex.Pattern;

/**
 * Expression for PCRE-compatible regex matching using JPCRE2. Supports full PCRE features
 * including: - Named groups (?<name>...) - Lookahead/lookbehind (including variable-length) -
 * Backreferences - Recursion (?R) and named recursion (?&name) - Conditionals (?(condition)yes|no)
 * - Inline flags (?i), (?m), (?s), etc.
 */
@ToString
@EqualsAndHashCode
public class RegexMatch implements Expression {
  @Getter private final Expression field;

  @Getter private final Expression pattern;

  @Getter private final boolean negated;

  // Pattern cache to avoid recompiling the same patterns
  private static final ConcurrentHashMap<String, Pattern> patternCache = new ConcurrentHashMap<>();

  // Maximum cache size to prevent memory issues
  private static final int MAX_CACHE_SIZE = 1000;

  // Initialize PCRE4J with JNA backend (done once)
  static {
    Pcre4j.setup(new Pcre2());
  }

  public RegexMatch(Expression field, Expression pattern, boolean negated) {
    this.field = field;
    this.pattern = pattern;
    this.negated = negated;
  }

  @Override
  public ExprValue valueOf(Environment<Expression, ExprValue> valueEnv) {
    ExprValue fieldValue = field.valueOf(valueEnv);
    ExprValue patternValue = pattern.valueOf(valueEnv);

    // Handle null/missing values
    if (fieldValue.isNull()
        || fieldValue.isMissing()
        || patternValue.isNull()
        || patternValue.isMissing()) {
      return ExprValueUtils.booleanValue(false);
    }

    String text = fieldValue.stringValue();
    String regex = patternValue.stringValue();

    try {
      // Get compiled pattern from cache or compile new one
      Pattern compiledPattern = getCompiledPattern(regex);

      // Create matcher and check for match
      Matcher matcher = compiledPattern.matcher(text);
      boolean matches = matcher.find(); // Use find() for partial match like SPL

      // Apply negation if needed
      return ExprValueUtils.booleanValue(negated ? !matches : matches);

    } catch (Exception e) {
      // Return false on pattern compilation/matching errors
      // Note: In production, proper logging should be added here
      return ExprValueUtils.booleanValue(false);
    }
  }

  /** Get compiled pattern from cache or compile and cache it. */
  private Pattern getCompiledPattern(String regex) {
    // Check cache size and clear if needed (simple LRU-like behavior)
    if (patternCache.size() > MAX_CACHE_SIZE) {
      patternCache.clear();
    }

    return patternCache.computeIfAbsent(
        regex,
        r -> {
          // Compile with PCRE2 defaults
          // pcre4j compiles the pattern with full PCRE2 support
          return Pattern.compile(r);
        });
  }

  @Override
  public ExprType type() {
    return ExprCoreType.BOOLEAN;
  }

  @Override
  public <T, C> T accept(ExpressionNodeVisitor<T, C> visitor, C context) {
    return visitor.visitRegex(this, context);
  }
}
