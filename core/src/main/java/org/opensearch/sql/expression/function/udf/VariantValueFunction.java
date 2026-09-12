/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.expression.function.udf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.calcite.adapter.enumerable.NotNullImplementor;
import org.apache.calcite.adapter.enumerable.NullPolicy;
import org.apache.calcite.adapter.enumerable.RexToLixTranslator;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.runtime.rtti.BasicSqlTypeRtti;
import org.apache.calcite.runtime.rtti.GenericSqlTypeRtti;
import org.apache.calcite.runtime.rtti.RuntimeTypeInformation;
import org.apache.calcite.runtime.rtti.RuntimeTypeInformation.RuntimeSqlTypeName;
import org.apache.calcite.runtime.variant.VariantValue;
import org.apache.calcite.sql.type.SqlReturnTypeInference;
import org.apache.calcite.sql.type.SqlTypeName;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.opensearch.sql.expression.function.ImplementorUDF;
import org.opensearch.sql.expression.function.UDFOperandMetadata;

/**
 * {@code VARIANT_VALUE(variant)}: the plain value a VARIANT holds, typed ANY.
 *
 * <p>A VARIANT (a {@code flat_object} leaf) carries its own runtime type, and a typed function
 * receives it cast to the parameter type it declares. A function that declares no parameter types
 * -- {@code mvappend}, {@code coalesce}, {@code array}, the {@code json_*} functions -- works on
 * plain Java values instead, and would otherwise see the variant as one opaque object. This
 * function hands it the value itself: an array as a list, an object as a map, a scalar as itself,
 * each element likewise. It is inserted by the function resolver and is not user-facing.
 */
public class VariantValueFunction extends ImplementorUDF {

  public VariantValueFunction() {
    super(new VariantValueImplementor(), NullPolicy.STRICT);
  }

  @Override
  public SqlReturnTypeInference getReturnTypeInference() {
    return opBinding ->
        opBinding
            .getTypeFactory()
            .createTypeWithNullability(
                opBinding.getTypeFactory().createSqlType(SqlTypeName.ANY), true);
  }

  @Override
  public UDFOperandMetadata getOperandMetadata() {
    return null;
  }

  private static final RuntimeTypeInformation ARRAY_OF_VARIANT =
      new GenericSqlTypeRtti(
          RuntimeSqlTypeName.ARRAY, new BasicSqlTypeRtti(RuntimeSqlTypeName.VARIANT));
  private static final RuntimeTypeInformation MAP_OF_VARIANT =
      new GenericSqlTypeRtti(
          RuntimeSqlTypeName.MAP,
          new BasicSqlTypeRtti(RuntimeSqlTypeName.VARCHAR),
          new BasicSqlTypeRtti(RuntimeSqlTypeName.VARIANT));

  /**
   * The plain value of a variant, element by element, using only what Calcite's variant API
   * exposes. Anything that is not a variant is returned as it is.
   */
  public static @Nullable Object value(@Nullable Object object) {
    if (!(object instanceof VariantValue variant)) {
      return object;
    }
    RuntimeSqlTypeName type = RuntimeSqlTypeName.valueOf(variant.getTypeString());
    switch (type) {
      case ARRAY, MULTISET -> {
        List<?> elements = (List<?>) variant.cast(ARRAY_OF_VARIANT);
        if (elements == null) {
          return null;
        }
        List<@Nullable Object> values = new ArrayList<>(elements.size());
        for (Object element : elements) {
          values.add(value(element));
        }
        return values;
      }
      case MAP, ROW -> {
        Map<?, ?> entries = (Map<?, ?>) variant.cast(MAP_OF_VARIANT);
        if (entries == null) {
          return null;
        }
        Map<Object, @Nullable Object> values = new LinkedHashMap<>();
        entries.forEach((key, element) -> values.put(value(key), value(element)));
        return values;
      }
      case NULL -> {
        return null;
      }
      default -> {
        return variant.cast(new BasicSqlTypeRtti(type));
      }
    }
  }

  public static class VariantValueImplementor implements NotNullImplementor {
    @Override
    public Expression implement(
        RexToLixTranslator translator, RexCall call, List<Expression> translatedOperands) {
      return Expressions.call(
          VariantValueFunction.class,
          "value",
          Expressions.convert_(translatedOperands.get(0), Object.class));
    }
  }
}
