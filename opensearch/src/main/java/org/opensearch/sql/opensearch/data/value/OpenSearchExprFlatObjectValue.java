/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.opensearch.data.value;

import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.apache.calcite.runtime.rtti.BasicSqlTypeRtti;
import org.apache.calcite.runtime.rtti.RuntimeTypeInformation.RuntimeSqlTypeName;
import org.apache.calcite.runtime.variant.VariantValue;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.opensearch.sql.calcite.utils.OpenSearchTypeFactory;
import org.opensearch.sql.data.model.ExprCollectionValue;
import org.opensearch.sql.data.model.ExprTupleValue;
import org.opensearch.sql.data.model.ExprValue;
import org.opensearch.sql.data.type.ExprCoreType;
import org.opensearch.sql.data.type.ExprType;
import org.opensearch.sql.data.utils.ComparableLinkedHashMap;

/**
 * The value of a flat_object field: a tuple keyed by dotted leaf path whose leaves keep the type
 * they were written with in {@code _source}.
 *
 * <p>Inside the engine this is an ordinary tuple. Handed to Calcite it becomes a map whose values
 * are {@link VariantValue}s, the runtime representation of the {@code VARIANT} column type the
 * field is declared as, so that {@code TYPEOF}, casts and comparisons on a leaf dispatch on the
 * leaf's own type rather than on text.
 */
public class OpenSearchExprFlatObjectValue extends ExprTupleValue {

  /** The rounding mode Calcite itself uses when it constructs a VARIANT from a value. */
  private static final RoundingMode ROUNDING_MODE =
      OpenSearchTypeFactory.TYPE_FACTORY.getTypeSystem().roundingMode();

  public OpenSearchExprFlatObjectValue(LinkedHashMap<String, ExprValue> leaves) {
    super(leaves);
  }

  @Override
  public Object valueForCalcite() {
    ComparableLinkedHashMap<String, Object> result = new ComparableLinkedHashMap<>();
    for (Entry<String, ExprValue> entry : tupleValue().entrySet()) {
      result.put(entry.getKey(), toVariant(entry.getValue()));
    }
    return result;
  }

  /**
   * Wrap a leaf as the VARIANT runtime value Calcite expects, tagged with the leaf's type. A null
   * leaf is handed to Calcite as SQL NULL rather than as a variant null, so that nulls-first /
   * nulls-last ordering and IS NULL apply to it as to any other column.
   */
  static @Nullable VariantValue toVariant(ExprValue leaf) {
    if (leaf.isNull()) {
      return null;
    }
    if (leaf instanceof ExprCollectionValue array) {
      List<@Nullable VariantValue> elements = new ArrayList<>();
      for (ExprValue element : array.collectionValue()) {
        elements.add(toVariant(element));
      }
      return new OpenSearchVariantValue.Array(elements);
    }
    if (leaf instanceof ExprTupleValue object) {
      // An object inside an array keeps its boundaries; see OpenSearchVariantValue.MapValue.
      Map<String, @Nullable VariantValue> entries = new LinkedHashMap<>();
      object.tupleValue().forEach((key, element) -> entries.put(key, toVariant(element)));
      return new OpenSearchVariantValue.MapValue(entries);
    }
    return new OpenSearchVariantValue(
        ROUNDING_MODE, leaf.valueForCalcite(), new BasicSqlTypeRtti(runtimeTypeOf(leaf.type())));
  }

  /**
   * The Java value inside a VARIANT. For a value this class produced it is held directly; for any
   * other variant, casting it to its own runtime type returns the wrapped value unchanged, and a
   * variant null casts to null.
   */
  public static @Nullable Object unwrap(VariantValue variant) {
    if (variant instanceof OpenSearchVariantValue ours) {
      return ours.getValue();
    }
    if (variant instanceof OpenSearchVariantValue.Array array) {
      return array.unwrapAll();
    }
    if (variant instanceof OpenSearchVariantValue.MapValue map) {
      return map.unwrapAll();
    }
    RuntimeSqlTypeName runtimeType = RuntimeSqlTypeName.valueOf(variant.getTypeString());
    if (runtimeType == RuntimeSqlTypeName.NULL) {
      return null;
    }
    return variant.cast(new BasicSqlTypeRtti(runtimeType));
  }

  private static RuntimeSqlTypeName runtimeTypeOf(ExprType type) {
    if (type instanceof ExprCoreType coreType) {
      switch (coreType) {
        case BYTE:
          return RuntimeSqlTypeName.TINYINT;
        case SHORT:
          return RuntimeSqlTypeName.SMALLINT;
        case INTEGER:
          return RuntimeSqlTypeName.INTEGER;
        case LONG:
          return RuntimeSqlTypeName.BIGINT;
        case FLOAT:
          return RuntimeSqlTypeName.REAL;
        case DOUBLE:
          return RuntimeSqlTypeName.DOUBLE;
        case BOOLEAN:
          return RuntimeSqlTypeName.BOOLEAN;
        case STRING:
          return RuntimeSqlTypeName.VARCHAR;
        default:
          break;
      }
    }
    throw new IllegalStateException("flat_object leaf has unexpected type " + type.typeName());
  }
}
