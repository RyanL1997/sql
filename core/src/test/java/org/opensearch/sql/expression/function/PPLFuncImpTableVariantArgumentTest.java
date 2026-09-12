/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.expression.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.opensearch.sql.calcite.utils.OpenSearchTypeFactory.TYPE_FACTORY;

import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.Test;

/** How a VARIANT argument (a flat_object leaf) reaches a function, by the kind of parameter. */
public class PPLFuncImpTableVariantArgumentTest {

  private final RexBuilder builder = new RexBuilder(TYPE_FACTORY);

  private RexNode variantRef() {
    return builder.makeInputRef(TYPE_FACTORY.createSqlType(SqlTypeName.VARIANT, true), 0);
  }

  private static String operatorName(RexNode node) {
    return ((RexCall) node).getOperator().getName();
  }

  // A function that declares no parameter types (mvappend) works on plain values, so the variant
  // is handed to it as the value it holds, typed ANY.
  @Test
  public void untypedFunctionReceivesTheVariantsPlainValue() {
    RexNode call =
        PPLFuncImpTable.INSTANCE.resolve(
            builder, BuiltinFunctionName.MVAPPEND, variantRef(), builder.makeLiteral("c"));
    RexNode first = ((RexCall) call).getOperands().get(0);
    assertEquals("VARIANT_VALUE", operatorName(first));
    assertEquals(SqlTypeName.ANY, first.getType().getSqlTypeName());
    assertTrue(first.getType().isNullable());
  }

  // A function that takes an array (array_length) receives the variant as an array of plain
  // values.
  @Test
  public void arrayFunctionReceivesTheVariantAsAnArray() {
    RexNode call =
        PPLFuncImpTable.INSTANCE.resolve(builder, BuiltinFunctionName.ARRAY_LENGTH, variantRef());
    RexNode first = ((RexCall) call).getOperands().get(0);
    assertEquals("VARIANT_ARRAY", operatorName(first));
    assertEquals(SqlTypeName.ARRAY, first.getType().getSqlTypeName());
  }

  // A function that takes a scalar (abs) receives a plain CAST to that type, never a SAFE_CAST.
  @Test
  public void scalarFunctionReceivesAPlainCast() {
    RexNode call = PPLFuncImpTable.INSTANCE.resolve(builder, BuiltinFunctionName.ABS, variantRef());
    RexNode first = ((RexCall) call).getOperands().get(0);
    assertEquals(SqlKind.CAST, first.getKind());
    assertEquals(
        SqlTypeName.VARIANT, ((RexCall) first).getOperands().get(0).getType().getSqlTypeName());
  }

  // typeof is registered without a type checker too, but the variant is what it reports on.
  @Test
  public void typeofKeepsTheVariant() {
    RexNode call =
        PPLFuncImpTable.INSTANCE.resolve(builder, BuiltinFunctionName.TYPEOF, variantRef());
    assertEquals("TYPEOF", operatorName(call));
    assertEquals(
        SqlTypeName.VARIANT, ((RexCall) call).getOperands().get(0).getType().getSqlTypeName());
  }

  // Lambda functions are untyped too, but their array argument is already an ARRAY<VARIANT> by
  // the time they are resolved, so nothing is unwrapped; the rule only acts on VARIANT operands.
  @Test
  public void nonVariantOperandsOfAnUntypedFunctionAreLeftAlone() {
    RexNode call =
        PPLFuncImpTable.INSTANCE.resolve(
            builder,
            BuiltinFunctionName.MVAPPEND,
            builder.makeLiteral("a"),
            CoercionUtils.castVariantToArrayOfVariants(builder, variantRef()));
    for (RexNode operand : ((RexCall) call).getOperands()) {
      assertTrue(
          operand.getKind() != SqlKind.OTHER_FUNCTION
              || !operatorName(operand).equals("VARIANT_VALUE"));
    }
  }
}
