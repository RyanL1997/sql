/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.calcite;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelVisitor;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.rex.RexVisitorImpl;
import org.apache.calcite.sql.SqlKind;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.opensearch.sql.common.error.ErrorCode;
import org.opensearch.sql.common.error.ErrorReport;
import org.opensearch.sql.common.error.QueryProcessingStage;
import org.opensearch.sql.common.utils.StringUtils;

/**
 * Keeps a query to what a {@code flat_object} field can answer.
 *
 * <p>A flat_object gives its leaves no mapping of their own: the index files every leaf value as a
 * keyword term with the path folded in, and the engine reads the field from {@code _source}. So a
 * leaf can be read, and that is all PPL does with it today -- anything computed from a leaf would
 * have to open every record, which is the cost this field type exists to avoid. Such a query is
 * rejected here, while the plan is built, rather than answered slowly.
 *
 * <p>This runs over the finished plan rather than throwing from {@link QualifiedNameResolver} where
 * a leaf reference is built, the way an unsupported command is rejected from {@code visitX}. Field
 * resolution is not a safe place to reject from: {@code MapPathPreMaterializer}, which every plan
 * node passes through, wraps it in {@code catch (RuntimeException | AssertionError)} and logs at
 * debug, so a rejection raised there is dropped for {@code rename}, {@code fillnull}, {@code
 * replace}, {@code rare}, {@code top}, {@code lookup} and {@code join}, and the query goes on to
 * fail in pushdown instead. The finished plan is downstream of that.
 *
 * <p>A leaf is {@code ITEM(<flat_object column>, '<path>')}. Which columns those are is not read
 * off the plan -- the type they take there, a map of text to text, is also what {@code spath}
 * produces for an extracted document -- but comes from the mapping, recorded as each relation was
 * bound. A column that a projection derived from a leaf is tracked too, so that a command which
 * sorts or groups by a leaf, projecting it into a column of its own first, is caught.
 */
final class FlatObjectScopeValidator {

  private static final String DOC_URL =
      "https://docs.opensearch.org/latest/mappings/supported-field-types/flat-object/";

  private static final String SUGGESTION =
      "A flat_object field can only be read. Its leaves have no mapping of their own, so nothing"
          + " else can be answered without opening every record. See "
          + DOC_URL;

  private FlatObjectScopeValidator() {}

  /**
   * Throws if the plan does anything with one of the given flat_object fields other than read it.
   * The names come from {@link CalcitePlanContext#getFlatObjectFields()}, filled in as each
   * relation was bound.
   */
  static void validate(RelNode plan, Set<String> fields) {
    if (fields.isEmpty()) {
      return;
    }
    new RelVisitor() {
      @Override
      public void visit(RelNode node, int ordinal, @Nullable RelNode parent) {
        super.visit(node, ordinal, parent);
        check(node, fields);
      }
    }.go(plan);
  }

  private static void check(RelNode node, Set<String> fields) {
    List<RelDataTypeField> inputFields = inputFields(node);
    Set<Integer> leafColumns = leafColumns(node, fields);
    if (node instanceof Project project) {
      // Reading a leaf, or the whole field, is the one thing allowed.
      for (RexNode expression : project.getProjects()) {
        if (isRead(expression, inputFields, fields, leafColumns)) {
          continue;
        }
        List<String> leaves = leavesIn(expression, inputFields, fields, leafColumns);
        if (!leaves.isEmpty()) {
          throw unsupported("Cannot compute a value from %s", String.join(", ", leaves));
        }
      }
      return;
    }
    // Every other node: a leaf may pass through by position, but nothing the node does may use one.
    List<String> used = new ArrayList<>();
    node.accept(
        new RexShuttle() {
          @Override
          public RexNode visitInputRef(RexInputRef ref) {
            if (isLeafColumn(ref.getIndex(), inputFields, fields, leafColumns)) {
              used.add(displayName(node, inputFields, ref.getIndex(), fields));
            }
            return ref;
          }

          @Override
          public RexNode visitCall(RexCall call) {
            // a leaf is named by its path, not by the field the ITEM reads from
            if (isLeafRef(call, inputFields, fields)) {
              used.add(leafName(call, inputFields));
              return call;
            }
            return super.visitCall(call);
          }
        });
    if (node instanceof Sort sort) {
      sort.getCollation()
          .getFieldCollations()
          .forEach(
              key -> {
                if (isLeafColumn(key.getFieldIndex(), inputFields, fields, leafColumns)) {
                  used.add(displayName(node, inputFields, key.getFieldIndex(), fields));
                }
              });
    }
    if (node instanceof Aggregate aggregate) {
      List<Integer> positions = new ArrayList<>(aggregate.getGroupSet().asList());
      aggregate.getAggCallList().forEach(call -> positions.addAll(call.getArgList()));
      positions.forEach(
          position -> {
            if (isLeafColumn(position, inputFields, fields, leafColumns)) {
              used.add(displayName(node, inputFields, position, fields));
            }
          });
    }
    if (!used.isEmpty()) {
      throw unsupported(
          "Cannot %s by %s", nameOf(node), String.join(", ", new LinkedHashSet<>(used)));
    }
  }

  private static boolean isLeafColumn(
      int index, List<RelDataTypeField> inputFields, Set<String> fields, Set<Integer> leaves) {
    return leaves.contains(index) || isFlatObjectColumn(inputFields, index, fields);
  }

  /**
   * The output column indices of {@code node}'s inputs that came off a flat_object field: a leaf a
   * projection read, or the field itself passed through. A projection renames what it produces, so
   * a command one level up cannot recognize either by name.
   */
  private static Set<Integer> leafColumns(RelNode node, Set<String> fields) {
    Set<Integer> leaves = new HashSet<>();
    int offset = 0;
    for (RelNode input : node.getInputs()) {
      if (input instanceof Project project) {
        List<RelDataTypeField> below = inputFields(project);
        List<RexNode> projects = project.getProjects();
        for (int i = 0; i < projects.size(); i++) {
          RexNode expression = stripCasts(projects.get(i));
          boolean fromField =
              isLeafRef(expression, below, fields)
                  || (expression instanceof RexInputRef ref
                      && isFlatObjectColumn(below, ref.getIndex(), fields));
          if (fromField) {
            leaves.add(offset + i);
          }
        }
      }
      offset += input.getRowType().getFieldCount();
    }
    return leaves;
  }

  /**
   * Whether the expression only reads a leaf or the whole field. A cast of a leaf counts: a leaf is
   * text, so converting it is the parse a caller would do anyway, the value comes from {@code
   * _source} either way, and it is how a query asks for a leaf as a number.
   */
  /**
   * Unwraps the casts a read may wear. {@code isRead} and {@link #leafColumns} have to agree on
   * what counts as reading a leaf: a column one of them lets through while the other does not track
   * is a column that reaches pushdown, where a script over a flat_object cannot be built.
   */
  private static RexNode stripCasts(RexNode node) {
    RexNode read = node;
    while (read instanceof RexCall cast
        && (cast.getKind() == SqlKind.CAST || cast.getKind() == SqlKind.SAFE_CAST)) {
      read = cast.getOperands().get(0);
    }
    return read;
  }

  private static boolean isRead(
      RexNode node, List<RelDataTypeField> inputFields, Set<String> fields, Set<Integer> leaves) {
    if (node instanceof RexInputRef) {
      return true;
    }
    RexNode read = stripCasts(node);
    return isLeafRef(read, inputFields, fields)
        || (read instanceof RexInputRef ref && leaves.contains(ref.getIndex()));
  }

  /** {@code ITEM(<flat_object column>, '<path>')}. */
  private static boolean isLeafRef(
      RexNode node, List<RelDataTypeField> inputFields, Set<String> fields) {
    return node instanceof RexCall call
        && call.getKind() == SqlKind.ITEM
        && call.getOperands().get(0) instanceof RexInputRef ref
        && isFlatObjectColumn(inputFields, ref.getIndex(), fields)
        && call.getOperands().get(1) instanceof RexLiteral;
  }

  private static boolean isFlatObjectColumn(
      List<RelDataTypeField> inputFields, int index, Set<String> fields) {
    return index >= 0
        && index < inputFields.size()
        && fields.contains(StringUtils.unquoteIdentifier(inputFields.get(index).getName()));
  }

  /** The leaf references inside an expression, named as the query writes them. */
  private static List<String> leavesIn(
      RexNode node, List<RelDataTypeField> inputFields, Set<String> fields, Set<Integer> leaves) {
    Set<String> found = new LinkedHashSet<>();
    node.accept(
        new RexVisitorImpl<Void>(true) {
          @Override
          public Void visitInputRef(RexInputRef ref) {
            if (isLeafColumn(ref.getIndex(), inputFields, fields, leaves)) {
              found.add(name(inputFields, ref.getIndex()));
            }
            return null;
          }

          @Override
          public Void visitCall(RexCall call) {
            if (isLeafRef(call, inputFields, fields)) {
              found.add(leafName(call, inputFields));
              return null;
            }
            return super.visitCall(call);
          }
        });
    return List.copyOf(found);
  }

  private static List<RelDataTypeField> inputFields(RelNode node) {
    List<RelDataTypeField> fields = new ArrayList<>();
    for (RelNode input : node.getInputs()) {
      fields.addAll(input.getRowType().getFieldList());
    }
    return fields;
  }

  private static String name(List<RelDataTypeField> inputFields, int index) {
    return StringUtils.unquoteIdentifier(inputFields.get(index).getName());
  }

  private static String leafName(RexCall item, List<RelDataTypeField> inputFields) {
    return name(inputFields, ((RexInputRef) item.getOperands().get(0)).getIndex())
        + "."
        + RexLiteral.stringValue((RexLiteral) item.getOperands().get(1));
  }

  /**
   * The name to show for an input column. A command that sorts or groups by a leaf projects it into
   * a column of its own first, and that column has a generated name ({@code $f2}); name the leaf,
   * or the field, that projection reads instead, which is what the query says.
   */
  private static String displayName(
      RelNode node, List<RelDataTypeField> inputFields, int index, Set<String> fields) {
    if (!node.getInputs().isEmpty() && node.getInput(0) instanceof Project project) {
      List<RelDataTypeField> below = inputFields(project);
      RexNode expression = stripCasts(project.getProjects().get(index));
      if (isLeafRef(expression, below, fields)) {
        return leafName((RexCall) expression, below);
      }
      if (expression instanceof RexInputRef ref
          && isFlatObjectColumn(below, ref.getIndex(), fields)) {
        return name(below, ref.getIndex());
      }
    }
    return name(inputFields, index);
  }

  /** The command as a query author names it, for the message. */
  private static String nameOf(RelNode node) {
    return switch (node) {
      case Sort ignored -> "sort";
      case Aggregate ignored -> "stats";
      case Filter ignored -> "filter";
      default -> node.getRelTypeName().toLowerCase(Locale.ROOT);
    };
  }

  private static ErrorReport unsupported(String message, Object... args) {
    return ErrorReport.wrap(new IllegalArgumentException(StringUtils.format(message, args)))
        .code(ErrorCode.UNSUPPORTED_OPERATION)
        .stage(QueryProcessingStage.ANALYZING)
        .location("while checking what a flat_object field can answer")
        .suggestion(SUGGESTION)
        .build();
  }
}
