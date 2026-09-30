/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.calcite.utils;

import java.util.Set;
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
 * What a query may do with a {@code flat_object} field: a list of the allowed uses, everything else
 * refused.
 *
 * <p>A flat_object gives its leaves no mapping of their own. The index files every leaf value as
 * one folded {@code path=value} keyword term, so the queries the field type answers are the ones
 * that match a term -- the list under <a
 * href="https://docs.opensearch.org/latest/mappings/supported-field-types/flat-object/#supported-queries">Supported
 * queries</a>. Anything else would have to open {@code _source} for every record it is asked about,
 * the cost the field type exists to avoid, so it is refused while the plan is built rather than
 * answered slowly.
 *
 * <p>Today the list is: read a leaf, or read the whole field. A read may wear a cast, which is how
 * a query asks for a leaf as a number.
 *
 * <p>The refusal is raised from {@code QualifiedNameResolver}, where every reference to a field
 * passes, so a command that is not on the list needs no entry here to be refused -- including one
 * added later. {@code CalciteRelNodeVisitor} opens the permission for the reads above and closes it
 * again, and names the command it was doing for the message.
 */
public final class FlatObjectScope {

  private static final String DOC_URL =
      "https://docs.opensearch.org/latest/mappings/supported-field-types/flat-object/";

  private static final String SUGGESTION =
      "A flat_object field can only be read. Its leaves have no mapping of their own, so nothing"
          + " else can be answered without opening every record. See "
          + DOC_URL;

  private FlatObjectScope() {}

  /**
   * Whether {@code name} is a flat_object field or a leaf under one, or a column a read of either
   * went into. The mapping types are recorded as each relation is bound; the columns as each read
   * is allowed.
   */
  public static boolean isFlatObject(String name, CalcitePlanContext context) {
    return context.getFlatObjectFields().contains(name)
        || context.getFlatObjectLeafColumns().containsKey(name);
  }

  /** Whether any part of {@code parts} up to {@code length} names a flat_object field. */
  public static boolean isFlatObjectField(String fieldName, CalcitePlanContext context) {
    return context.getFlatObjectFields().contains(fieldName);
  }

  /**
   * Refuses the use unless the command being analyzed opened the permission. {@code name} is the
   * field or leaf as the query spelled it.
   */
  public static void check(String name, CalcitePlanContext context) {
    if (context.isFlatObjectReadAllowed() || !rootedInFlatObject(name, context)) {
      return;
    }
    throw refuse("Cannot %s %s", context.getFlatObjectUse(), originOf(name, context));
  }

  /**
   * Runs {@code read} with the permission open, and notes the column a read of a flat_object went
   * into so a later command that uses that column is refused too. {@code target} is the name the
   * command gives the result, or null when it does not name one.
   */
  public static <T> T allowRead(
      UnresolvedExpression expression, String target, CalcitePlanContext context, Reader<T> read) {
    boolean isRead = nameOf(expression) != null;
    boolean previousAllowed = context.isFlatObjectReadAllowed();
    String previousUse = context.getFlatObjectUse();
    context.setFlatObjectReadAllowed(isRead);
    if (!isRead) {
      context.setFlatObjectUse("compute a value from");
    }
    try {
      T resolved = read.get();
      if (isRead && target != null && namesFlatObject(expression, context)) {
        context.getFlatObjectLeafColumns().put(target, nameOf(expression));
      }
      return resolved;
    } finally {
      context.setFlatObjectReadAllowed(previousAllowed);
      context.setFlatObjectUse(previousUse);
    }
  }

  /** Runs {@code read} while naming what the command would be doing, for the refusal message. */
  public static <T> T as(String use, CalcitePlanContext context, Reader<T> read) {
    String previous = context.getFlatObjectUse();
    context.setFlatObjectUse(use);
    try {
      return read.get();
    } finally {
      context.setFlatObjectUse(previous);
    }
  }

  /**
   * Runs {@code read} with the permission open, for a step that is a read by construction rather
   * than by the shape of an expression.
   */
  public static void runAsRead(CalcitePlanContext context, Runnable read) {
    boolean previous = context.isFlatObjectReadAllowed();
    context.setFlatObjectReadAllowed(true);
    try {
      read.run();
    } finally {
      context.setFlatObjectReadAllowed(previous);
    }
  }

  /** {@link #runAsRead} for a resolution that returns a value. */
  public static <T> T allowReadOf(CalcitePlanContext context, Reader<T> read) {
    boolean previous = context.isFlatObjectReadAllowed();
    context.setFlatObjectReadAllowed(true);
    try {
      return read.get();
    } finally {
      context.setFlatObjectReadAllowed(previous);
    }
  }

  /**
   * Notes that {@code target} now holds what {@code source} held, so a use of it is refused too.
   */
  public static void trackRename(String source, String target, CalcitePlanContext context) {
    if (rootedInFlatObject(source, context)) {
      context.getFlatObjectLeafColumns().put(target, originOf(source, context));
    }
  }

  /** {@link #as} for a resolution that returns nothing. */
  public static void runAs(String use, CalcitePlanContext context, Runnable read) {
    as(
        use,
        context,
        () -> {
          read.run();
          return null;
        });
  }

  /** The field a name refers to: a column read from a leaf is named by that leaf, not by itself. */
  private static String originOf(String name, CalcitePlanContext context) {
    return context.getFlatObjectLeafColumns().getOrDefault(name, name);
  }

  /** Whether {@code name} is a flat_object field, a leaf under one, or a column read from one. */
  private static boolean rootedInFlatObject(String name, CalcitePlanContext context) {
    if (isFlatObject(name, context)) {
      return true;
    }
    Set<String> fields = context.getFlatObjectFields();
    for (String field : fields) {
      if (name.startsWith(field + ".")) {
        return true;
      }
    }
    return false;
  }

  /** Whether the expression is nothing but a field reference, optionally cast. */
  private static boolean isBareRead(UnresolvedExpression expression) {
    return nameOf(expression) != null;
  }

  /** Whether the expression is a bare read of a flat_object field or a leaf under one. */
  private static boolean namesFlatObject(
      UnresolvedExpression expression, CalcitePlanContext context) {
    String name = nameOf(expression);
    return name != null && rootedInFlatObject(name, context);
  }

  /** The field a bare read names, with the aliases and casts around it removed; null otherwise. */
  private static String nameOf(UnresolvedExpression expression) {
    UnresolvedExpression read = expression;
    while (true) {
      switch (read) {
        case Alias alias -> read = alias.getDelegated();
        case Let let -> read = let.getExpression();
        case Cast cast -> read = cast.getExpression();
        case Field field -> read = field.getField();
        // typeof asks what a leaf is, not what it holds, so it reads nothing
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

  /** What {@link #allowRead} and {@link #as} wrap: the resolution the command was about to do. */
  public interface Reader<T> {
    T get();
  }
}
