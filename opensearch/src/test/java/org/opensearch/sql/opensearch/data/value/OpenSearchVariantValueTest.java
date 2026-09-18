/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.opensearch.data.value;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.opensearch.sql.data.model.ExprValueUtils.booleanValue;
import static org.opensearch.sql.data.model.ExprValueUtils.doubleValue;
import static org.opensearch.sql.data.model.ExprValueUtils.integerValue;
import static org.opensearch.sql.data.model.ExprValueUtils.longValue;
import static org.opensearch.sql.data.model.ExprValueUtils.nullValue;
import static org.opensearch.sql.data.model.ExprValueUtils.stringValue;
import static org.opensearch.sql.data.model.ExprValueUtils.tupleValue;

import java.util.List;
import java.util.Map;
import org.apache.calcite.runtime.rtti.BasicSqlTypeRtti;
import org.apache.calcite.runtime.rtti.RuntimeTypeInformation.RuntimeSqlTypeName;
import org.apache.calcite.runtime.variant.VariantValue;
import org.junit.jupiter.api.Test;
import org.opensearch.sql.data.model.ExprCollectionValue;
import org.opensearch.sql.data.model.ExprValue;

/**
 * The value of a flat_object leaf as a query sees it: the value it was written with, carrying its
 * own type, and unwrapping back to that value when it reaches a row.
 */
class OpenSearchVariantValueTest {

  private static VariantValue variant(ExprValue leaf) {
    return OpenSearchExprFlatObjectValue.toVariant(leaf);
  }

  @Test
  void carriesTheLeafTypeAndUnwrapsToTheSameValue() {
    assertAll(
        () -> assertEquals("DOUBLE", variant(doubleValue(12.5)).getTypeString()),
        () -> assertEquals("VARCHAR", variant(stringValue("4")).getTypeString()),
        () -> assertEquals("INTEGER", variant(integerValue(500)).getTypeString()),
        () -> assertEquals("BIGINT", variant(longValue(7L)).getTypeString()),
        () -> assertEquals("BOOLEAN", variant(booleanValue(true)).getTypeString()),
        () -> assertNull(OpenSearchExprFlatObjectValue.toVariant(nullValue())),
        () -> assertEquals(12.5, OpenSearchExprFlatObjectValue.unwrap(variant(doubleValue(12.5)))),
        () -> assertEquals("4", OpenSearchExprFlatObjectValue.unwrap(variant(stringValue("4")))));
  }

  // A leaf written as a number and the same text are different values, as they are in _source.
  @Test
  void equalityFollowsValueAndType() {
    assertAll(
        () -> assertEquals(variant(integerValue(500)), variant(integerValue(500))),
        () -> assertNotEquals(variant(integerValue(500)), variant(stringValue("500"))),
        () -> assertNotEquals(variant(doubleValue(12.5)), variant(doubleValue(9.0))));
  }

  // Casting a leaf to the type it recorded returns the value unchanged; to another scalar type it
  // follows Calcite's variant rules (a numeric variant converts, a text one becomes null).
  @Test
  void castFollowsTheRecordedType() {
    VariantValue number = variant(integerValue(500));
    VariantValue text = variant(stringValue("500"));
    assertAll(
        () -> assertEquals(500, number.cast(new BasicSqlTypeRtti(RuntimeSqlTypeName.INTEGER))),
        () -> assertEquals(500.0, number.cast(new BasicSqlTypeRtti(RuntimeSqlTypeName.DOUBLE))),
        () -> assertEquals("500", text.cast(new BasicSqlTypeRtti(RuntimeSqlTypeName.VARCHAR))),
        () -> assertNull(text.cast(new BasicSqlTypeRtti(RuntimeSqlTypeName.INTEGER))));
  }

  // An array leaf keeps its elements, each with its own type, and unwraps to a plain list.
  @Test
  void arrayVariantKeepsTypedElements() {
    VariantValue mixed =
        variant(new ExprCollectionValue(List.of(integerValue(1), stringValue("x"))));
    assertAll(
        () -> assertEquals("ARRAY", mixed.getTypeString()),
        () ->
            assertEquals(
                "INTEGER",
                ((VariantValue) ((OpenSearchVariantValue.Array) mixed).getElements().get(0))
                    .getTypeString()),
        () -> assertEquals(List.of(1, "x"), OpenSearchExprFlatObjectValue.unwrap(mixed)));
  }

  // An object inside an array keeps its boundaries: key access, typed values, a plain map on the
  // way out.
  @Test
  void mapVariantKeepsTypedEntries() {
    VariantValue map = variant(tupleValue(Map.of("k", integerValue(1), "s", stringValue("x"))));
    assertAll(
        () -> assertEquals("MAP", map.getTypeString()),
        () -> assertEquals("INTEGER", ((VariantValue) map.item("k")).getTypeString()),
        () -> assertNull(map.item("missing")),
        () -> assertEquals(Map.of("k", 1, "s", "x"), OpenSearchExprFlatObjectValue.unwrap(map)));
  }
}
