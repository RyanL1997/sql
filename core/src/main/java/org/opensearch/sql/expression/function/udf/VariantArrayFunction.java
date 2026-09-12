/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.expression.function.udf;

import java.util.Collections;
import java.util.List;
import org.apache.calcite.adapter.enumerable.NotNullImplementor;
import org.apache.calcite.adapter.enumerable.NullPolicy;
import org.apache.calcite.adapter.enumerable.RexToLixTranslator;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.sql.type.SqlReturnTypeInference;
import org.apache.calcite.sql.type.SqlTypeName;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.opensearch.sql.expression.function.ImplementorUDF;
import org.opensearch.sql.expression.function.UDFOperandMetadata;

/**
 * {@code VARIANT_ARRAY(variant)}: a VARIANT as a PPL array, {@code ARRAY<ANY>} of plain values.
 *
 * <p>PPL's array functions -- {@code array_length}, {@code mvindex}, {@code mvjoin} and the rest --
 * are written for a list of plain Java values, so a VARIANT (a {@code flat_object} leaf) passed
 * where an array is expected is converted by this function rather than by a cast: an array leaf
 * yields its elements as plain values, and a single value yields an array of that one element, the
 * multivalue rule a single value follows. Inserted by the coercion of function arguments; not
 * user-facing.
 */
public class VariantArrayFunction extends ImplementorUDF {

  public VariantArrayFunction() {
    super(new VariantArrayImplementor(), NullPolicy.STRICT);
  }

  @Override
  public SqlReturnTypeInference getReturnTypeInference() {
    return opBinding -> {
      RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
      return typeFactory.createTypeWithNullability(
          typeFactory.createArrayType(
              typeFactory.createTypeWithNullability(
                  typeFactory.createSqlType(SqlTypeName.ANY), true),
              -1),
          true);
    };
  }

  @Override
  public UDFOperandMetadata getOperandMetadata() {
    return null;
  }

  /** The variant as a list of plain values; a single value as a list of one. */
  public static @Nullable List<@Nullable Object> array(@Nullable Object object) {
    Object value = VariantValueFunction.value(object);
    if (value == null) {
      return null;
    }
    if (value instanceof List<?> list) {
      @SuppressWarnings("unchecked")
      List<@Nullable Object> values = (List<@Nullable Object>) list;
      return values;
    }
    return Collections.singletonList(value);
  }

  public static class VariantArrayImplementor implements NotNullImplementor {
    @Override
    public Expression implement(
        RexToLixTranslator translator, RexCall call, List<Expression> translatedOperands) {
      return Expressions.call(
          VariantArrayFunction.class,
          "array",
          Expressions.convert_(translatedOperands.get(0), Object.class));
    }
  }
}
