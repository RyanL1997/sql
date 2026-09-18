/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.opensearch.data.value;

import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.Getter;
import org.apache.calcite.runtime.SqlFunctions;
import org.apache.calcite.runtime.rtti.RuntimeTypeInformation;
import org.apache.calcite.runtime.rtti.RuntimeTypeInformation.RuntimeSqlTypeName;
import org.apache.calcite.runtime.variant.VariantSqlValue;
import org.apache.calcite.runtime.variant.VariantValue;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A non-null VARIANT runtime value: the value of a flat_object leaf, carrying the type it was
 * written with.
 *
 * <p>This wrapper delegates every scalar variant operation to Calcite's implementation and adds an
 * order -- numbers first by numeric value, then everything else by its text form -- so that a
 * VARIANT column has a defined result wherever Calcite compares values; Calcite's own variant is
 * not {@link Comparable}.
 *
 * <p>Arrays and objects inside a flat_object are represented by {@link Array} and {@link MapValue}
 * rather than by Calcite's composite variants, because Calcite converts every element with one
 * declared element type while a flat_object array may hold a number next to a string. Here each
 * element is its own typed variant. Casting to {@code ARRAY<VARIANT>} yields the elements as the
 * variants they are, {@code ARRAY<T>} yields each element cast to T, and likewise for a map's
 * values; a scalar cast to an array becomes an array of one element.
 */
public class OpenSearchVariantValue extends VariantSqlValue implements Comparable<VariantValue> {

  private final VariantValue delegate;

  /** The Java value this variant wraps. */
  @Getter private final Object value;

  OpenSearchVariantValue(RoundingMode roundingMode, Object value, RuntimeTypeInformation type) {
    super(type.getTypeName());
    this.value = value;
    this.delegate = VariantSqlValue.create(roundingMode, value, type);
  }

  @Override
  public @Nullable Object cast(RuntimeTypeInformation type) {
    if (type.getTypeName() == RuntimeSqlTypeName.ARRAY) {
      // A single value is an array of one element; see the class comment.
      return new Array(Collections.singletonList(this)).cast(type);
    }
    return delegate.cast(type);
  }

  @Override
  public @Nullable Object item(Object index) {
    return delegate.item(index);
  }

  @Override
  public int compareTo(VariantValue other) {
    return compareValues(value, OpenSearchExprFlatObjectValue.unwrap(other));
  }

  @Override
  public boolean equals(@Nullable Object o) {
    return o instanceof OpenSearchVariantValue other
        ? delegate.equals(other.delegate)
        : delegate.equals(o);
  }

  @Override
  public int hashCode() {
    return delegate.hashCode();
  }

  @Override
  public String toString() {
    return delegate.toString();
  }

  /** The runtime type this variant carries, e.g. {@code INTEGER} or {@code VARCHAR}. */
  public RuntimeSqlTypeName getRuntimeType() {
    return RuntimeSqlTypeName.valueOf(getTypeString());
  }

  /**
   * The order shared by every variant this class produces: numbers first, by numeric value; then
   * everything else -- text, booleans, arrays, objects -- by its text form.
   */
  static int compareValues(@Nullable Object left, @Nullable Object right) {
    boolean leftIsNumber = left instanceof Number;
    boolean rightIsNumber = right instanceof Number;
    if (leftIsNumber && rightIsNumber) {
      return Double.compare(((Number) left).doubleValue(), ((Number) right).doubleValue());
    }
    if (leftIsNumber) {
      return -1;
    }
    if (rightIsNumber) {
      return 1;
    }
    return String.valueOf(left).compareTo(String.valueOf(right));
  }

  /**
   * An array inside a flat_object: each element is its own typed variant (or null). {@code ITEM}
   * takes a 1-based index, as Calcite's array indexing does. Casting to {@code ARRAY<VARIANT>}
   * yields the elements as they are; casting to an array of a scalar type casts each element to it
   * (an element that is not of that type becomes null); casting to a scalar type yields null, as
   * for any composite variant.
   */
  public static class Array extends VariantSqlValue implements Comparable<VariantValue> {

    @Getter private final List<@Nullable VariantValue> elements;

    Array(List<@Nullable VariantValue> elements) {
      super(RuntimeSqlTypeName.ARRAY);
      this.elements = Collections.unmodifiableList(new ArrayList<>(elements));
    }

    @Override
    public @Nullable Object cast(RuntimeTypeInformation type) {
      if (type.getTypeName() != RuntimeSqlTypeName.ARRAY) {
        return null;
      }
      RuntimeTypeInformation elementType = type.asGeneric().getTypeArgument(0);
      if (elementType.getTypeName() == RuntimeSqlTypeName.VARIANT) {
        return new ArrayList<>(elements);
      }
      List<@Nullable Object> result = new ArrayList<>(elements.size());
      for (VariantValue element : elements) {
        result.add(element == null ? null : element.cast(elementType));
      }
      return result;
    }

    @Override
    public @Nullable Object item(Object index) {
      if (!(index instanceof Number number)) {
        return null;
      }
      return SqlFunctions.arrayItem(elements, number.intValue(), 1, true);
    }

    /** The elements as plain Java values, the way a row or a bucket key needs them. */
    List<@Nullable Object> unwrapAll() {
      List<@Nullable Object> result = new ArrayList<>(elements.size());
      for (VariantValue element : elements) {
        result.add(element == null ? null : OpenSearchExprFlatObjectValue.unwrap(element));
      }
      return result;
    }

    @Override
    public int compareTo(VariantValue other) {
      return compareValues(unwrapAll(), OpenSearchExprFlatObjectValue.unwrap(other));
    }

    @Override
    public boolean equals(@Nullable Object o) {
      return o instanceof Array other && elements.equals(other.elements);
    }

    @Override
    public int hashCode() {
      return elements.hashCode();
    }

    @Override
    public String toString() {
      return elements.toString();
    }
  }

  /**
   * An object nested inside a flat_object array: a map from key to typed variant (or null).
   * Top-level objects never take this form -- they are flattened into the field's own map -- so
   * this only appears as an array element, where flattening would lose the element boundaries.
   * Casting to {@code MAP<K, VARIANT>} yields the values as they are; casting to a map of a scalar
   * value type casts each value to it; casting to anything else yields null.
   */
  public static class MapValue extends VariantSqlValue implements Comparable<VariantValue> {

    @Getter private final Map<String, @Nullable VariantValue> entries;

    MapValue(Map<String, @Nullable VariantValue> entries) {
      super(RuntimeSqlTypeName.MAP);
      this.entries = new LinkedHashMap<>(entries);
    }

    @Override
    public @Nullable Object cast(RuntimeTypeInformation type) {
      if (type.getTypeName() != RuntimeSqlTypeName.MAP) {
        return null;
      }
      RuntimeTypeInformation valueType = type.asGeneric().getTypeArgument(1);
      Map<String, @Nullable Object> result = new LinkedHashMap<>();
      entries.forEach(
          (key, element) ->
              result.put(
                  key,
                  element == null || valueType.getTypeName() == RuntimeSqlTypeName.VARIANT
                      ? element
                      : element.cast(valueType)));
      return result;
    }

    @Override
    public @Nullable Object item(Object index) {
      return index instanceof String key ? entries.get(key) : null;
    }

    /** The entries as plain Java values, the way a row or a bucket key needs them. */
    Map<String, @Nullable Object> unwrapAll() {
      Map<String, @Nullable Object> result = new LinkedHashMap<>();
      entries.forEach(
          (key, element) ->
              result.put(
                  key, element == null ? null : OpenSearchExprFlatObjectValue.unwrap(element)));
      return result;
    }

    @Override
    public int compareTo(VariantValue other) {
      return compareValues(unwrapAll(), OpenSearchExprFlatObjectValue.unwrap(other));
    }

    @Override
    public boolean equals(@Nullable Object o) {
      return o instanceof MapValue other && entries.equals(other.entries);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(entries);
    }

    @Override
    public String toString() {
      return entries.toString();
    }
  }
}
