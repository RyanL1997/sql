/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.calcite;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.opensearch.sql.calcite.utils.OpenSearchTypeFactory.TYPE_FACTORY;

import com.google.common.collect.ImmutableList;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.volcano.VolcanoPlanner;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.logical.LogicalAggregate;
import org.apache.calcite.rel.logical.LogicalFilter;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rel.logical.LogicalSort;
import org.apache.calcite.rel.logical.LogicalValues;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.util.ImmutableBitSet;
import org.junit.jupiter.api.Test;
import org.opensearch.sql.common.error.ErrorCode;
import org.opensearch.sql.common.error.ErrorReport;

/**
 * What a query may do with a flat_object field: read a leaf, or the whole field. Everything else is
 * rejected while the plan is built.
 *
 * <p>The plans here are built as the commands build them: the field is a column of type {@code
 * MAP<VARCHAR, VARCHAR>}, and a leaf of one is {@code ITEM(<that column>, '<path>')}.
 */
class FlatObjectScopeValidatorTest {

  private final RexBuilder rexBuilder = new RexBuilder(TYPE_FACTORY);
  private final RelOptCluster cluster = RelOptCluster.create(new VolcanoPlanner(), rexBuilder);

  /** A row of two columns: `service`, a keyword, and `attributes`, a flat_object field. */
  private final RelDataType rowType =
      TYPE_FACTORY
          .builder()
          .add("service", TYPE_FACTORY.createSqlType(SqlTypeName.VARCHAR))
          .add(
              "attributes",
              TYPE_FACTORY.createMapType(
                  TYPE_FACTORY.createSqlType(SqlTypeName.VARCHAR),
                  TYPE_FACTORY.createTypeWithNullability(
                      TYPE_FACTORY.createSqlType(SqlTypeName.VARCHAR), true),
                  true))
          .build();

  private RelNode scan() {
    return LogicalValues.createEmpty(cluster, rowType);
  }

  private RexNode field(int index) {
    return rexBuilder.makeInputRef(rowType.getFieldList().get(index).getType(), index);
  }

  /** {@code attributes.<path>}, as the resolver builds it. */
  private RexNode leaf(String path) {
    return rexBuilder.makeCall(SqlStdOperatorTable.ITEM, field(1), rexBuilder.makeLiteral(path));
  }

  private RelNode project(RexNode... expressions) {
    List<RexNode> list = List.of(expressions);
    List<String> names = new ArrayList<>();
    for (int i = 0; i < list.size(); i++) {
      names.add("c" + i);
    }
    return LogicalProject.create(scan(), ImmutableList.of(), list, names);
  }

  /** The field is found by its mapping type in production; the check itself takes the names. */
  private static final Set<String> FIELDS = Set.of("attributes");

  private void allowed(RelNode plan) {
    assertDoesNotThrow(() -> FlatObjectScopeValidator.validate(plan, FIELDS));
  }

  /** The message says what this query did; the rule and the doc link ride on the report. */
  private void rejected(RelNode plan, String what) {
    ErrorReport ex =
        assertThrows(ErrorReport.class, () -> FlatObjectScopeValidator.validate(plan, FIELDS));
    assertTrue(ex.getMessage().startsWith(what), ex.getMessage());
    assertEquals(ErrorCode.UNSUPPORTED_OPERATION, ex.getCode());
    assertTrue(ex.getSuggestion().contains("can only be read"), ex.getSuggestion());
    assertTrue(
        ex.getSuggestion().contains("supported-field-types/flat-object/"), ex.getSuggestion());
  }

  // ---- reading

  @Test
  void readingAFieldALeafOrAnotherColumn() {
    allowed(project(field(1)));
    allowed(project(leaf("namespace")));
    allowed(project(field(0), leaf("duration_ms")));
  }

  // A leaf is text, so converting it is the parse a caller would do anyway, and the value comes
  // from _source either way -- this is how a query asks for a leaf as a number.
  @Test
  void castingALeafIsReading() {
    RexNode asDouble =
        rexBuilder.makeCast(
            TYPE_FACTORY.createSqlType(SqlTypeName.DOUBLE), leaf("duration_ms"), true, true);
    allowed(project(asDouble));
  }

  @Test
  void aPlanThatDoesNotTouchTheFieldIsUntouched() {
    allowed(project(field(0)));
    allowed(LogicalSort.create(scan(), RelCollations.of(new RelFieldCollation(0)), null, null));
  }

  // ---- everything else

  @Test
  void computingAValueFromALeafIsRejected() {
    RexNode plusOne =
        rexBuilder.makeCall(
            SqlStdOperatorTable.PLUS,
            rexBuilder.makeCast(
                TYPE_FACTORY.createSqlType(SqlTypeName.DOUBLE), leaf("d"), true, true),
            rexBuilder.makeExactLiteral(BigDecimal.ONE));
    rejected(project(plusOne), "Cannot compute a value from attributes.d");
  }

  @Test
  void filteringByALeafIsRejected() {
    RexNode equals =
        rexBuilder.makeCall(
            SqlStdOperatorTable.EQUALS, leaf("namespace"), rexBuilder.makeLiteral("prod"));
    rejected(LogicalFilter.create(scan(), equals), "Cannot filter by attributes.namespace");
  }

  // A command that sorts or groups by a leaf projects it into a column of its own first, so the
  // check has to follow that column back to the leaf it was read from.
  @Test
  void sortingByALeafIsRejected() {
    RelNode projected = project(field(0), leaf("duration_ms"));
    rejected(
        LogicalSort.create(projected, RelCollations.of(new RelFieldCollation(1)), null, null),
        "Cannot sort by attributes.duration_ms");
    rejected(
        LogicalSort.create(
            project(field(1)), RelCollations.of(new RelFieldCollation(0)), null, null),
        "Cannot sort by attributes");
  }

  @Test
  void groupingByALeafIsRejected() {
    RelNode projected = project(field(0), leaf("status_code"));
    rejected(
        LogicalAggregate.create(
            projected, ImmutableList.of(), ImmutableBitSet.of(1), null, ImmutableList.of()),
        "Cannot stats by attributes.status_code");
  }

  @Test
  void aggregatingALeafIsRejected() {
    RelNode projected = project(field(0), leaf("duration_ms"));
    AggregateCall count =
        AggregateCall.create(
            SqlStdOperatorTable.COUNT,
            false,
            false,
            false,
            ImmutableList.of(),
            ImmutableList.of(1),
            -1,
            null,
            RelCollations.EMPTY,
            TYPE_FACTORY.createSqlType(SqlTypeName.BIGINT),
            "c");
    rejected(
        LogicalAggregate.create(
            projected, ImmutableList.of(), ImmutableBitSet.of(), null, ImmutableList.of(count)),
        "Cannot stats by attributes.duration_ms");
  }
}
