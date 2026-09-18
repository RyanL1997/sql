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
import java.util.Objects;
import lombok.Getter;
import org.apache.calcite.runtime.rtti.RuntimeTypeInformation;
import org.apache.calcite.runtime.rtti.RuntimeTypeInformation.RuntimeSqlTypeName;
import org.apache.calcite.runtime.variant.VariantSqlValue;
import org.apache.calcite.runtime.variant.VariantValue;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The value of a {@code flat_object} leaf: whatever the document wrote, carrying the type it was
 * written with, so that a projected row shows {@code 12.5} as a number and {@code "4"} as text.
 *
 * <p>A leaf is only ever projected or looked up in the index, so this is a carrier: it holds the
 * value and its runtime type and delegates the scalar variant operations to Calcite's
 * implementation. Arrays and objects inside a flat_object are represented by {@link Array} and
 * {@link MapValue} rather than by Calcite's composite variants, because Calcite converts every
 * element with one declared element type while a flat_object array may hold a number next to a
 * string; here each element is its own typed variant.
 */
public class OpenSearchVariantValue extends VariantSqlValue {

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
    return delegate.cast(type);
  }

  @Override
  public @Nullable Object item(Object index) {
    return delegate.item(index);
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

  /** An array inside a flat_object: each element is its own typed variant (or null). */
  public static class Array extends VariantSqlValue {

    @Getter private final List<@Nullable VariantValue> elements;

    Array(List<@Nullable VariantValue> elements) {
      super(RuntimeSqlTypeName.ARRAY);
      this.elements = List.copyOf(elements);
    }

    @Override
    public @Nullable Object cast(RuntimeTypeInformation type) {
      return null;
    }

    @Override
    public @Nullable Object item(Object index) {
      return null;
    }

    /** The elements as plain Java values, the way a projected row needs them. */
    List<@Nullable Object> unwrapAll() {
      List<@Nullable Object> result = new ArrayList<>(elements.size());
      for (VariantValue element : elements) {
        result.add(element == null ? null : OpenSearchExprFlatObjectValue.unwrap(element));
      }
      return result;
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
   */
  public static class MapValue extends VariantSqlValue {

    @Getter private final Map<String, @Nullable VariantValue> entries;

    MapValue(Map<String, @Nullable VariantValue> entries) {
      super(RuntimeSqlTypeName.MAP);
      this.entries = new LinkedHashMap<>(entries);
    }

    @Override
    public @Nullable Object cast(RuntimeTypeInformation type) {
      return null;
    }

    @Override
    public @Nullable Object item(Object index) {
      return index instanceof String key ? entries.get(key) : null;
    }

    /** The entries as plain Java values, the way a projected row needs them. */
    Map<String, @Nullable Object> unwrapAll() {
      Map<String, @Nullable Object> result = new LinkedHashMap<>();
      entries.forEach(
          (key, element) ->
              result.put(
                  key, element == null ? null : OpenSearchExprFlatObjectValue.unwrap(element)));
      return result;
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
