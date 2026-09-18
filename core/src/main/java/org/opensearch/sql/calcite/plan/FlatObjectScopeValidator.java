/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.calcite.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelVisitor;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.core.Correlate;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.rex.RexVisitorImpl;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.fun.SqlLikeOperator;
import org.apache.calcite.sql.type.SqlTypeFamily;
import org.apache.calcite.sql.type.SqlTypeName;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.opensearch.sql.common.utils.StringUtils;

/**
 * Keeps a query to what a {@code flat_object} field can answer.
 *
 * <p>A flat_object indexes every leaf value as a keyword term with the path folded in and gives the
 * leaf no mapping of its own, so OpenSearch can look a leaf up by its exact text but cannot
 * compare, aggregate or sort it. PPL supports what that lookup answers -- projecting a leaf, and
 * filtering it by exact text, by existence or by a leading prefix -- and rejects everything else
 * here, while the plan is built, rather than reading every record to answer it.
 *
 * <p>Both are recognized by type: a flat_object field is {@code MAP<VARCHAR, VARIANT>} and a leaf
 * of one is {@code VARIANT}, the types the field is registered with.
 */
public final class FlatObjectScopeValidator {

  private static final String DOC_URL =
      "https://opensearch.org/docs/latest/field-types/supported-field-types/flat-object/";

  private FlatObjectScopeValidator() {}

  /** Throws if the plan uses a flat_object field beyond what its index can answer. */
  public static void validate(RelNode plan) {
    new RelVisitor() {
      @Override
      public void visit(RelNode node, int ordinal, @Nullable RelNode parent) {
        check(node);
        super.visit(node, ordinal, parent);
      }
    }.go(plan);
  }

  /**
   * The error for a function that cannot take a flat_object leaf, raised by the function resolver
   * in place of its own type error so that the reason is the same wherever it surfaces.
   */
  public static IllegalArgumentException unsupportedFunction(String functionName) {
    return unsupported("Cannot apply %s to a flat_object leaf", functionName);
  }

  private static void check(RelNode node) {
    List<RelDataTypeField> inputFields = inputFields(node);
    // Sort keys, group keys, aggregate arguments and the column an expand correlates on are
    // positions, not expressions, so they are checked against the input row type directly.
    if (node instanceof Sort sort) {
      for (var key : sort.getCollation().getFieldCollations()) {
        rejectIfFlatObject(node, inputFields, key.getFieldIndex(), "Cannot sort by %s");
      }
      return;
    }
    if (node instanceof Aggregate aggregate) {
      for (int key : aggregate.getGroupSet()) {
        rejectIfFlatObject(node, inputFields, key, "Cannot group by %s");
      }
      for (AggregateCall call : aggregate.getAggCallList()) {
        for (int arg : call.getArgList()) {
          rejectIfFlatObject(
              node,
              inputFields,
              arg,
              "Cannot apply "
                  + call.getAggregation().getName().toLowerCase(Locale.ROOT)
                  + " to %s");
        }
      }
      return;
    }
    if (node instanceof Correlate correlate) {
      for (int required : correlate.getRequiredColumns()) {
        rejectIfFlatObject(node, inputFields, required, "Cannot expand %s");
      }
      return;
    }
    if (node instanceof Project project) {
      // A leaf may be projected as it is; an expression over one would have to read the value.
      for (RexNode expression : project.getProjects()) {
        if (isLeafRef(expression, inputFields)) {
          continue;
        }
        List<String> leaves = leavesIn(expression, inputFields);
        if (!leaves.isEmpty()) {
          throw unsupported("Cannot evaluate an expression over %s", String.join(", ", leaves));
        }
      }
      return;
    }
    if (node instanceof Filter filter && filter.getCondition() instanceof RexCall condition) {
      checkPredicate(condition, inputFields);
      return;
    }
    // Any other node -- a join condition, a window, a command with expressions of its own -- may
    // pass a leaf through by position, but nothing it computes may read one.
    node.accept(
        new RexShuttle() {
          @Override
          public RexNode visitInputRef(RexInputRef ref) {
            if (isFlatObjectOrLeaf(inputFields.get(ref.getIndex()).getType())) {
              throw unsupported("Cannot use %s here", name(inputFields, ref.getIndex()));
            }
            return ref;
          }
        });
  }

  /** The fields a {@link RexInputRef} inside {@code node} addresses: its inputs, in order. */
  private static List<RelDataTypeField> inputFields(RelNode node) {
    List<RelDataTypeField> fields = new ArrayList<>();
    for (RelNode input : node.getInputs()) {
      fields.addAll(input.getRowType().getFieldList());
    }
    return fields;
  }

  /** A flat_object field is a map of leaf path to VARIANT; a leaf of one is a VARIANT. */
  static boolean isFlatObject(RelDataType type) {
    return type.getSqlTypeName() == SqlTypeName.MAP
        && type.getValueType() != null
        && type.getValueType().getSqlTypeName() == SqlTypeName.VARIANT;
  }

  private static boolean isFlatObjectOrLeaf(RelDataType type) {
    return isFlatObject(type) || type.getSqlTypeName() == SqlTypeName.VARIANT;
  }

  /** A leaf reference: {@code ITEM(<flat_object field>, '<path>')}, or a column that is one. */
  private static boolean isLeafRef(RexNode node, List<RelDataTypeField> inputFields) {
    node = stripTextCast(node);
    if (node instanceof RexInputRef ref) {
      return isFlatObjectOrLeaf(inputFields.get(ref.getIndex()).getType());
    }
    return node instanceof RexCall call
        && call.getKind() == SqlKind.ITEM
        && call.getOperands().get(0) instanceof RexInputRef ref
        && isFlatObject(ref.getType())
        && call.getOperands().get(1) instanceof RexLiteral;
  }

  /** {@code field.path} for a leaf reference, as a query author writes it. */
  private static String leafName(RexNode node, List<RelDataTypeField> inputFields) {
    node = stripTextCast(node);
    if (node instanceof RexInputRef ref) {
      return name(inputFields, ref.getIndex());
    }
    RexCall item = (RexCall) node;
    return name(inputFields, ((RexInputRef) item.getOperands().get(0)).getIndex())
        + "."
        + RexLiteral.stringValue((RexLiteral) item.getOperands().get(1));
  }

  /**
   * Whether a filter is one the index answers on its own: exact text, existence, or a leading
   * prefix, combined with and / or. Throws for a predicate over a leaf that it does not answer;
   * returns false for a predicate that does not touch one.
   */
  private static boolean checkPredicate(RexCall call, List<RelDataTypeField> inputFields) {
    List<String> leaves = leavesIn(call, inputFields);
    if (leaves.isEmpty()) {
      return false;
    }
    switch (call.getKind()) {
      case AND, OR, NOT -> {
        // a leaf predicate the index answers is a match, not its absence: not(...) would have to
        // read the records the index does not return
        if (call.getKind() != SqlKind.NOT) {
          call.getOperands().stream()
              .filter(RexCall.class::isInstance)
              .forEach(operand -> checkPredicate((RexCall) operand, inputFields));
          return true;
        }
      }
      case EQUALS -> {
        RexNode left = stripTextCast(call.getOperands().get(0));
        RexNode right = stripTextCast(call.getOperands().get(1));
        RexNode literal =
            isLeafRef(left, inputFields) ? right : isLeafRef(right, inputFields) ? left : null;
        if (literal instanceof RexLiteral text && isTextLiteral(text)) {
          String value = RexLiteral.stringValue(text);
          if (couldSpellANonTextValue(value)) {
            // the index files a number and its text form under one term, so it cannot tell the
            // number 503 from the text "503"; only reading the record could
            throw unsupported(
                "Cannot compare %s with '%s', which is also how a number, a boolean or null is"
                    + " written",
                leaves.getFirst(), value);
          }
          return true;
        }
      }
      case IS_NULL, IS_NOT_NULL -> {
        if (isLeafRef(call.getOperands().get(0), inputFields)) {
          return true;
        }
      }
      case LIKE -> {
        // LIKE and ILIKE both: the index answers a prefix, case-insensitively when asked to
        if (call.getOperator() instanceof SqlLikeOperator
            && isLeafRef(call.getOperands().get(0), inputFields)
            && call.getOperands().get(1) instanceof RexLiteral pattern
            && isTextLiteral(pattern)
            && isPrefixPattern(RexLiteral.stringValue(pattern))) {
          String prefix = RexLiteral.stringValue(pattern);
          prefix = prefix.substring(0, prefix.length() - 1);
          if (couldStartANonTextValue(prefix)) {
            throw unsupported(
                "Cannot match %s against the prefix '%s', which a number, a boolean or null could"
                    + " also start with",
                leaves.getFirst(), prefix);
          }
          return true;
        }
      }
      default -> {}
    }
    throw unsupported(
        "Cannot filter %s with %s",
        String.join(", ", leaves), call.getOperator().getName().toLowerCase(Locale.ROOT));
  }

  /** The leaf references inside an expression, as a query author writes them. */
  private static List<String> leavesIn(RexNode node, List<RelDataTypeField> inputFields) {
    List<String> leaves = new ArrayList<>();
    node.accept(
        new RexVisitorImpl<Void>(true) {
          @Override
          public Void visitInputRef(RexInputRef ref) {
            if (isFlatObjectOrLeaf(inputFields.get(ref.getIndex()).getType())) {
              leaves.add(name(inputFields, ref.getIndex()));
            }
            return null;
          }

          @Override
          public Void visitCall(RexCall call) {
            if (isLeafRef(call, inputFields)) {
              leaves.add(leafName(call, inputFields));
              return null;
            }
            return super.visitCall(call);
          }
        });
    return leaves;
  }

  private static void rejectIfFlatObject(
      RelNode node, List<RelDataTypeField> inputFields, int index, String message) {
    if (index < inputFields.size() && isFlatObjectOrLeaf(inputFields.get(index).getType())) {
      throw unsupported(message, displayName(node, inputFields, index));
    }
  }

  private static String name(List<RelDataTypeField> inputFields, int index) {
    return StringUtils.unquoteIdentifier(inputFields.get(index).getName());
  }

  /**
   * The name to show for an input column. A command that sorts or groups by a leaf projects it into
   * a column of its own first, and that column has a generated name ({@code $f2}); name the leaf
   * that projection reads instead, which is what the query says.
   */
  private static String displayName(RelNode node, List<RelDataTypeField> inputFields, int index) {
    if (!node.getInputs().isEmpty() && node.getInput(0) instanceof Project project) {
      List<RelDataTypeField> below = inputFields(project);
      RexNode expression = project.getProjects().get(index);
      if (isLeafRef(expression, below)) {
        return leafName(expression, below);
      }
    }
    return name(inputFields, index);
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

  /** Whether the text is how JSON spells a number, a boolean or null. */
  static boolean couldSpellANonTextValue(String text) {
    if (text.equals("true") || text.equals("false") || text.equals("null")) {
      return true;
    }
    try {
      Double.parseDouble(text);
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  /** Whether some number, boolean or null could start with this prefix. */
  static boolean couldStartANonTextValue(String prefix) {
    return prefix.matches("[-+]?[0-9]*\\.?[0-9]*([eE][-+]?[0-9]*)?")
        || "true".startsWith(prefix)
        || "false".startsWith(prefix)
        || "null".startsWith(prefix);
  }

  static boolean isPrefixPattern(String pattern) {
    return pattern.endsWith("%")
        && !pattern.substring(0, pattern.length() - 1).matches(".*[%_\\\\].*");
  }

  private static IllegalArgumentException unsupported(String message, Object... args) {
    return new IllegalArgumentException(
        StringUtils.format(message, args)
            + ": a flat_object field can only be projected, or filtered by exact text, existence or"
            + " a leading prefix. See "
            + DOC_URL);
  }
}
