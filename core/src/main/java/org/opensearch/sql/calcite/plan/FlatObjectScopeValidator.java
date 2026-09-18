/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.calcite.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.core.Values;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexVisitorImpl;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.fun.SqlLikeOperator;
import org.apache.calcite.sql.type.SqlTypeFamily;
import org.apache.calcite.sql.type.SqlTypeName;
import org.opensearch.sql.common.error.ErrorCode;
import org.opensearch.sql.common.error.ErrorReport;
import org.opensearch.sql.exception.NonFallbackCalciteException;

/**
 * Keeps a query to what a {@code flat_object} field can answer efficiently.
 *
 * <p>A {@code flat_object} field indexes every leaf value as a keyword term with the path folded
 * in, and builds none of the numeric structures an ordinary field has. OpenSearch can therefore
 * find a leaf by its text (term, exists, prefix) in one index lookup, but cannot compare, aggregate
 * or sort a leaf as a number without opening every record — seconds per ten million records. PPL
 * supports exactly what the index answers:
 *
 * <ul>
 *   <li>projecting a leaf, or the whole field;
 *   <li>filtering a leaf by text: equality with a text literal, {@code isnull} / {@code isnotnull},
 *       and {@code like} with a leading literal and a trailing {@code %}; combined with {@code and}
 *       / {@code or}.
 * </ul>
 *
 * <p>Everything else that touches a leaf — a numeric comparison, an aggregate, a group-by, a sort,
 * a function, a negation — is rejected at planning time with a message that names the leaf and says
 * why. A leaf is recognized by type: an {@code ITEM} on a column of type {@code MAP<VARCHAR,
 * VARIANT>}, the Calcite type a {@code flat_object} field is registered with.
 */
public final class FlatObjectScopeValidator {

  static final String REASON =
      "flat_object indexes every value as a keyword term, so OpenSearch can filter it by text but"
          + " cannot compare, aggregate or sort it as a number without reading every record (see"
          + " the flat_object field type documentation). PPL therefore supports on a flat_object"
          + " leaf only projection and text filters: equality with a text value, isnull /"
          + " isnotnull, and like with a leading prefix.";

  private FlatObjectScopeValidator() {}

  /** Validates the plan; throws if a flat_object leaf is used beyond the supported scope. */
  public static void validate(RelNode plan) {
    leafColumns(plan);
  }

  /**
   * The error for a function that received a flat_object leaf it cannot take, thrown by the
   * function resolver in place of its own type error, so that the reason is the same everywhere.
   */
  public static RuntimeException unsupportedFunction(String functionName) {
    return unsupported("the function " + functionName + " over a flat_object leaf");
  }

  /**
   * The output columns of {@code node} that are a flat_object leaf (or the field itself) -- index
   * to the name a message should use -- after checking that the node uses its inputs' leaf columns
   * only in the supported ways.
   */
  private static Map<Integer, String> leafColumns(RelNode node) {
    if (node instanceof TableScan || node instanceof Values) {
      return flatObjectColumns(node.getRowType());
    }
    List<Map<Integer, String>> inputs = new ArrayList<>();
    for (RelNode input : node.getInputs()) {
      inputs.add(leafColumns(input));
    }
    if (node instanceof Project project) {
      return checkProject(project, inputs.get(0));
    }
    if (node instanceof Filter filter) {
      checkPredicate(filter.getCondition(), inputs.get(0));
      return inputs.get(0);
    }
    if (node instanceof Sort sort) {
      Map<Integer, String> in = inputs.get(0);
      for (var key : sort.getCollation().getFieldCollations()) {
        if (in.containsKey(key.getFieldIndex())) {
          throw unsupported("sort by " + in.get(key.getFieldIndex()));
        }
      }
      return in;
    }
    if (node instanceof Aggregate aggregate) {
      Map<Integer, String> in = inputs.get(0);
      for (int key : aggregate.getGroupSet()) {
        if (in.containsKey(key)) {
          throw unsupported("group by " + in.get(key));
        }
      }
      for (AggregateCall call : aggregate.getAggCallList()) {
        for (int arg : call.getArgList()) {
          if (in.containsKey(arg)) {
            throw unsupported(
                call.getAggregation().getName().toLowerCase(Locale.ROOT) + " over " + in.get(arg));
          }
        }
      }
      return new LinkedHashMap<>();
    }
    // Any other operator (join, window, correlate, ...): reject if an input carries a leaf.
    for (Map<Integer, String> in : inputs) {
      if (!in.isEmpty()) {
        throw unsupported(
            "the command "
                + node.getRelTypeName().replace("Logical", "").toLowerCase(Locale.ROOT)
                + " on "
                + String.join(", ", in.values()));
      }
    }
    return new LinkedHashMap<>();
  }

  private static Map<Integer, String> flatObjectColumns(RelDataType rowType) {
    Map<Integer, String> columns = new LinkedHashMap<>();
    for (int i = 0; i < rowType.getFieldCount(); i++) {
      if (isFlatObject(rowType.getFieldList().get(i).getType())) {
        columns.put(i, rowType.getFieldList().get(i).getName());
      }
    }
    return columns;
  }

  static boolean isFlatObject(RelDataType type) {
    return type.getSqlTypeName() == SqlTypeName.MAP
        && type.getValueType() != null
        && type.getValueType().getSqlTypeName() == SqlTypeName.VARIANT;
  }

  /** A leaf reference: ITEM(<flat_object column>, <text key>), or a column that already is one. */
  private static boolean isLeafRef(RexNode node, Map<Integer, String> inputLeaves) {
    if (node instanceof RexInputRef ref) {
      return inputLeaves.containsKey(ref.getIndex());
    }
    return node instanceof RexCall call
        && call.getKind() == SqlKind.ITEM
        && call.getOperands().get(0) instanceof RexInputRef ref
        && inputLeaves.containsKey(ref.getIndex())
        && isFlatObject(ref.getType())
        && call.getOperands().get(1) instanceof RexLiteral;
  }

  /** {@code column.key} for a leaf reference, for messages. */
  private static String leafName(RexNode node, Map<Integer, String> inputLeaves) {
    node = stripTextCast(node);
    if (node instanceof RexInputRef ref) {
      return inputLeaves.get(ref.getIndex());
    }
    RexCall item = (RexCall) node;
    return inputLeaves.get(((RexInputRef) item.getOperands().get(0)).getIndex())
        + "."
        + RexLiteral.stringValue((RexLiteral) item.getOperands().get(1));
  }

  /** The leaf references inside an expression, as {@code column.key}, for messages. */
  private static List<String> leafNames(RexNode node, Map<Integer, String> inputLeaves) {
    List<String> names = new ArrayList<>();
    node.accept(
        new RexVisitorImpl<Void>(true) {
          @Override
          public Void visitInputRef(RexInputRef ref) {
            if (inputLeaves.containsKey(ref.getIndex())) {
              names.add(inputLeaves.get(ref.getIndex()));
            }
            return null;
          }

          @Override
          public Void visitCall(RexCall call) {
            if (isLeafRef(call, inputLeaves)) {
              names.add(leafName(call, inputLeaves));
              return null;
            }
            return super.visitCall(call);
          }
        });
    return names;
  }

  private static Map<Integer, String> checkProject(
      Project project, Map<Integer, String> inputLeaves) {
    Map<Integer, String> out = new LinkedHashMap<>();
    List<RexNode> exprs = project.getProjects();
    for (int i = 0; i < exprs.size(); i++) {
      RexNode expr = exprs.get(i);
      if (isLeafRef(expr, inputLeaves)) {
        out.put(i, leafName(expr, inputLeaves));
      } else {
        List<String> names = leafNames(expr, inputLeaves);
        if (!names.isEmpty()) {
          throw unsupported("an expression over " + String.join(", ", names));
        }
      }
    }
    return out;
  }

  private static void checkPredicate(RexNode condition, Map<Integer, String> inputLeaves) {
    List<String> names = leafNames(condition, inputLeaves);
    if (names.isEmpty()) {
      return;
    }
    if (condition instanceof RexCall call) {
      switch (call.getKind()) {
        case AND, OR -> {
          call.getOperands().forEach(op -> checkPredicate(op, inputLeaves));
          return;
        }
        case EQUALS -> {
          RexNode a = stripTextCast(call.getOperands().get(0));
          RexNode b = stripTextCast(call.getOperands().get(1));
          if ((isLeafRef(a, inputLeaves) && isTextLiteral(b))
              || (isLeafRef(b, inputLeaves) && isTextLiteral(a))) {
            return;
          }
        }
        case IS_NULL, IS_NOT_NULL -> {
          if (isLeafRef(stripTextCast(call.getOperands().get(0)), inputLeaves)) {
            return;
          }
        }
        case LIKE -> {
          // LIKE and ILIKE both: the index answers a prefix case-insensitively when asked to
          if (call.getOperator() instanceof SqlLikeOperator
              && isLeafRef(stripTextCast(call.getOperands().get(0)), inputLeaves)
              && call.getOperands().get(1) instanceof RexLiteral pattern
              && isTextLiteral(pattern)
              && isPrefixPattern(RexLiteral.stringValue(pattern))) {
            return;
          }
        }
        default -> {}
      }
      throw unsupported(
          "the filter "
              + call.getOperator().getName().toLowerCase(Locale.ROOT)
              + " on "
              + String.join(", ", names));
    }
    throw unsupported("the filter on " + String.join(", ", names));
  }

  private static RexNode stripTextCast(RexNode node) {
    while (node instanceof RexCall cast
        && (cast.getKind() == SqlKind.CAST || cast.getKind() == SqlKind.SAFE_CAST)
        && SqlTypeFamily.CHARACTER.contains(cast.getType())) {
      node = cast.getOperands().get(0);
    }
    return node;
  }

  private static boolean isTextLiteral(RexNode node) {
    return node instanceof RexLiteral literal
        && SqlTypeFamily.CHARACTER.contains(literal.getType());
  }

  static boolean isPrefixPattern(String pattern) {
    return pattern.endsWith("%")
        && !pattern.substring(0, pattern.length() - 1).matches(".*[%_\\\\].*");
  }

  private static RuntimeException unsupported(String what) {
    return ErrorReport.wrap(
            new NonFallbackCalciteException("Not supported: " + what + ". " + REASON))
        .code(ErrorCode.UNSUPPORTED_OPERATION)
        .location("while checking what a flat_object field can answer")
        .build();
  }
}
