/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.calcite.utils;

import org.opensearch.sql.ast.expression.Alias;
import org.opensearch.sql.ast.expression.Cast;
import org.opensearch.sql.ast.expression.Field;
import org.opensearch.sql.ast.expression.Function;
import org.opensearch.sql.ast.expression.Let;
import org.opensearch.sql.ast.expression.QualifiedName;
import org.opensearch.sql.ast.expression.UnresolvedExpression;
import org.opensearch.sql.calcite.CalcitePlanContext;
import org.opensearch.sql.common.error.ErrorCode;
import org.opensearch.sql.common.error.ErrorReport;
import org.opensearch.sql.common.error.QueryProcessingStage;
import org.opensearch.sql.common.utils.StringUtils;

/**
 * The uses a {@code flat_object} field allows: read a leaf, read the whole field, or ask its type.
 * A read may wear a cast, which is how a query asks for a leaf as a number. Everything else is
 * refused, because the index files each leaf as one folded {@code path=value} term and answering
 * anything more would mean opening {@code _source} for every record.
 *
 * <p>{@link #check} runs where a field reference resolves, so a command not on the list is refused
 * without an entry of its own. {@code CalciteRelNodeVisitor} opens the permission for the reads and
 * names what it was doing for the message.
 */
public final class FlatObjectScope {

  private static final String SUGGESTION =
      "A flat_object field can only be read. Its leaves have no mapping of their own, so nothing"
          + " else can be answered without opening every record. See"
          + " https://docs.opensearch.org/latest/mappings/supported-field-types/flat-object/";

  private FlatObjectScope() {}

  /** Refuses {@code name} unless the command opened the permission. */
  public static void check(String name, CalcitePlanContext context) {
    if (context.isFlatObjectReadAllowed() || !isFlatObject(name, context)) {
      return;
    }
    throw refuse("Cannot %s %s", context.getFlatObjectUse(), originOf(name, context));
  }

  /**
   * Resolves {@code expression}, permitted when it is one of the allowed reads. {@code target} is
   * the column the command puts it in, remembered so a later use of that column is refused too.
   */
  public static <T> T allowRead(
      UnresolvedExpression expression, String target, CalcitePlanContext context, Reader<T> read) {
    String name = nameOf(expression);
    boolean previousAllowed = context.isFlatObjectReadAllowed();
    String previousUse = context.getFlatObjectUse();
    context.setFlatObjectReadAllowed(name != null);
    if (name == null) {
      context.setFlatObjectUse("compute a value from");
    }
    try {
      T resolved = read.get();
      if (target != null && name != null && isFlatObject(name, context)) {
        context.getFlatObjectLeafColumns().put(target, originOf(name, context));
      }
      return resolved;
    } finally {
      context.setFlatObjectReadAllowed(previousAllowed);
      context.setFlatObjectUse(previousUse);
    }
  }

  /** Resolves {@code read} as a read, for a step that is one by construction. */
  public static <T> T allowReadOf(CalcitePlanContext context, Reader<T> read) {
    boolean previous = context.isFlatObjectReadAllowed();
    context.setFlatObjectReadAllowed(true);
    try {
      return read.get();
    } finally {
      context.setFlatObjectReadAllowed(previous);
    }
  }

  /** {@link #allowReadOf} for a step that returns nothing. */
  public static void runAsRead(CalcitePlanContext context, Runnable read) {
    allowReadOf(
        context,
        () -> {
          read.run();
          return null;
        });
  }

  /** Resolves {@code read} while naming what the command is doing, for the message. */
  public static <T> T as(String use, CalcitePlanContext context, Reader<T> read) {
    String previous = context.getFlatObjectUse();
    context.setFlatObjectUse(use);
    try {
      return read.get();
    } finally {
      context.setFlatObjectUse(previous);
    }
  }

  /** {@link #as} for a step that returns nothing. */
  public static void runAs(String use, CalcitePlanContext context, Runnable read) {
    as(
        use,
        context,
        () -> {
          read.run();
          return null;
        });
  }

  /** Carries the fact that a column holds a leaf over to the name it is renamed to. */
  public static void trackRename(String source, String target, CalcitePlanContext context) {
    if (isFlatObject(source, context)) {
      context.getFlatObjectLeafColumns().put(target, originOf(source, context));
    }
  }

  /** Whether the name is a flat_object field, a leaf under one, or a column read from either. */
  private static boolean isFlatObject(String name, CalcitePlanContext context) {
    return context.getFlatObjectFields().contains(name)
        || context.getFlatObjectLeafColumns().containsKey(name)
        || context.getFlatObjectFields().stream().anyMatch(field -> name.startsWith(field + "."));
  }

  /** A column read from a leaf is named by that leaf; any other name is its own. */
  private static String originOf(String name, CalcitePlanContext context) {
    return context.getFlatObjectLeafColumns().getOrDefault(name, name);
  }

  /** The field an allowed read names, or null when the expression is not one. */
  private static String nameOf(UnresolvedExpression expression) {
    UnresolvedExpression read = expression;
    while (true) {
      switch (read) {
        case Alias alias -> read = alias.getDelegated();
        case Let let -> read = let.getExpression();
        case Cast cast -> read = cast.getExpression();
        case Field field -> read = field.getField();
        // typeof asks what a leaf is, not what it holds
        case Function function
            when "typeof".equalsIgnoreCase(function.getFuncName())
                && function.getFuncArgs().size() == 1 ->
            read = function.getFuncArgs().get(0);
        case QualifiedName name -> {
          return name.toString();
        }
        default -> {
          return null;
        }
      }
    }
  }

  private static ErrorReport refuse(String message, Object... args) {
    return ErrorReport.wrap(new IllegalArgumentException(StringUtils.format(message, args)))
        .code(ErrorCode.UNSUPPORTED_OPERATION)
        .stage(QueryProcessingStage.ANALYZING)
        .location("while checking what a flat_object field can answer")
        .suggestion(SUGGESTION)
        .build();
  }

  /** The resolution a command was about to do. */
  public interface Reader<T> {
    T get();
  }
}
