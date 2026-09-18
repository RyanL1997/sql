/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.ppl.calcite;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.ImmutableList;
import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.plan.RelTraitDef;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelProtoDataType;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Statistic;
import org.apache.calcite.schema.Statistics;
import org.apache.calcite.schema.Table;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.test.CalciteAssert;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.Programs;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.Test;
import org.opensearch.sql.calcite.plan.FlatObjectScopeValidator;

/**
 * What a query may do with a flat_object leaf: project it, and filter it by text. Everything else
 * is rejected at planning time with the reason. The field is recognized by its Calcite type,
 * MAP&lt;VARCHAR, VARIANT&gt;.
 */
public class CalcitePPLFlatObjectScopeTest extends CalcitePPLAbstractTest {

  public CalcitePPLFlatObjectScopeTest() {
    super(CalciteAssert.SchemaSpec.SCOTT_WITH_TEMPORAL);
  }

  /** A table with a flat_object column, as OpenSearch registers one. */
  public static class TableWithFlatObject implements Table {
    protected final RelProtoDataType protoRowType =
        factory ->
            factory
                .builder()
                .add("SERVICE", SqlTypeName.VARCHAR)
                .add(
                    "ATTRS",
                    factory.createMapType(
                        factory.createSqlType(SqlTypeName.VARCHAR),
                        factory.createTypeWithNullability(
                            factory.createSqlType(SqlTypeName.VARIANT), true)))
                .build();

    @Override
    public RelDataType getRowType(RelDataTypeFactory typeFactory) {
      return protoRowType.apply(typeFactory);
    }

    @Override
    public Statistic getStatistic() {
      return Statistics.of(0d, ImmutableList.of(), RelCollations.createSingleton(0));
    }

    @Override
    public Schema.TableType getJdbcTableType() {
      return Schema.TableType.TABLE;
    }

    @Override
    public boolean isRolledUp(String column) {
      return false;
    }

    @Override
    public boolean rolledUpColumnValidInsideAgg(
        String column,
        SqlCall call,
        @Nullable SqlNode parent,
        @Nullable CalciteConnectionConfig config) {
      return false;
    }
  }

  @Override
  protected Frameworks.ConfigBuilder config(CalciteAssert.SchemaSpec... schemaSpecs) {
    final SchemaPlus rootSchema = Frameworks.createRootSchema(true);
    final SchemaPlus schema = CalciteAssert.addSchema(rootSchema, schemaSpecs);
    schema.add("OTEL", new TableWithFlatObject());
    return Frameworks.newConfigBuilder()
        .parserConfig(SqlParser.Config.DEFAULT)
        .defaultSchema(schema)
        .traitDefs((java.util.List<RelTraitDef>) null)
        .programs(Programs.heuristicJoinOrder(Programs.RULE_SET, true, 2));
  }

  private void allowed(String ppl) {
    RelNode root = getRelNode(ppl);
    FlatObjectScopeValidator.validate(root);
  }

  /**
   * Rejected either while the plan is built (a function that cannot take a leaf, refused by the
   * function resolver) or by the validator afterwards; the message carries the reason either way.
   */
  private void rejected(String ppl, String what) {
    Exception ex =
        assertThrows(
            Exception.class,
            () -> {
              RelNode root = getRelNode(ppl);
              FlatObjectScopeValidator.validate(root);
            });
    String msg = String.valueOf(ex.getMessage());
    assertTrue(msg, msg.contains(what));
    assertTrue(
        msg,
        msg.contains(
            "a flat_object field can only be projected, or filtered by exact text, existence or a"
                + " leading prefix"));
    assertTrue(
        msg,
        msg.contains(
            "https://opensearch.org/docs/latest/field-types/supported-field-types/flat-object/"));
  }

  // ---- allowed: projection

  @Test
  public void projectTheFieldAndALeaf() {
    allowed("source=OTEL | fields ATTRS");
    allowed("source=OTEL | fields SERVICE, ATTRS.duration_ms");
    allowed("source=OTEL | eval d = ATTRS.duration_ms | fields d");
    allowed("source=OTEL | rename ATTRS.duration_ms as d | fields d");
  }

  // ---- allowed: text filters

  @Test
  public void filterByText() {
    allowed("source=OTEL | where ATTRS.namespace = 'prod' | stats count()");
    allowed("source=OTEL | where 'prod' = ATTRS.namespace | fields SERVICE");
    allowed("source=OTEL | where isnotnull(ATTRS.error.type) | fields SERVICE");
    allowed("source=OTEL | where isnull(ATTRS.error.type) | fields SERVICE");
    allowed("source=OTEL | where like(ATTRS.namespace, 'ns-0%') | fields SERVICE");
    allowed(
        "source=OTEL | where ATTRS.namespace = 'prod' and SERVICE = 'checkout' or"
            + " isnotnull(ATTRS.k) | fields SERVICE");
  }

  // ---- rejected: everything that would open every record

  // The index files a number and its text form under the same term, so a lookup cannot tell them
  // apart; only reading the record could, and that is what this type costs.
  @Test
  public void textThatCouldAlsoSpellANumberBooleanOrNull() {
    rejected(
        "source=OTEL | where ATTRS.status_code = '503' | fields SERVICE",
        "Cannot compare ATTRS.status_code with '503'");
    rejected(
        "source=OTEL | where ATTRS.flag = 'true' | fields SERVICE",
        "Cannot compare ATTRS.flag with 'true'");
    rejected(
        "source=OTEL | where like(ATTRS.status_code, '5%') | fields SERVICE",
        "Cannot match ATTRS.status_code against the prefix '5'");
    // text that cannot be read as a number, a boolean or null is fine
    allowed("source=OTEL | where ATTRS.duration_ms = 'n/a' | fields SERVICE");
    allowed("source=OTEL | where like(ATTRS.namespace, 'ns-%') | fields SERVICE");
  }

  @Test
  public void numericFilter() {
    rejected(
        "source=OTEL | where ATTRS.duration_ms > 50 | fields SERVICE",
        "Cannot filter ATTRS.duration_ms with >");
    rejected(
        "source=OTEL | where ATTRS.duration_ms = 4 | fields SERVICE",
        "Cannot filter ATTRS.duration_ms with =");
  }

  @Test
  public void negatedOrInfixTextFilter() {
    rejected(
        "source=OTEL | where not ATTRS.namespace = 'prod' | fields SERVICE",
        "Cannot filter ATTRS.namespace with <>");
    rejected(
        "source=OTEL | where ATTRS.namespace != 'prod' | fields SERVICE",
        "Cannot filter ATTRS.namespace with <>");
    rejected(
        "source=OTEL | where like(ATTRS.namespace, '%prod') | fields SERVICE",
        "Cannot filter ATTRS.namespace with ilike");
  }

  @Test
  public void aggregateOverALeaf() {
    rejected(
        "source=OTEL | stats avg(ATTRS.duration_ms)", "Cannot apply avg to a flat_object leaf");
    rejected(
        "source=OTEL | stats count() by ATTRS.status_code", "Cannot group by ATTRS.status_code");
  }

  @Test
  public void sortByALeaf() {
    rejected(
        "source=OTEL | sort ATTRS.duration_ms | fields SERVICE",
        "Cannot sort by ATTRS.duration_ms");
  }

  @Test
  public void expressionOverALeaf() {
    rejected(
        "source=OTEL | eval d = ATTRS.duration_ms + 1 | fields d",
        "Cannot evaluate an expression over ATTRS.duration_ms");
    rejected(
        "source=OTEL | eval d = cast(ATTRS.duration_ms as double) | fields d",
        "Cannot evaluate an expression over ATTRS.duration_ms");
  }

  @Test
  public void aLeafRenamedThenAggregatedIsStillALeaf() {
    rejected(
        "source=OTEL | eval s = ATTRS.status_code | stats count() by s",
        "Cannot group by ATTRS.status_code");
  }

  @Test
  public void otherFieldsAreUnaffected() {
    allowed("source=OTEL | where SERVICE = 'x' | stats count() by SERVICE | sort SERVICE");
  }
}
