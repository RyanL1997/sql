/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.opensearch.data.value;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.opensearch.sql.data.model.ExprValueUtils.booleanValue;
import static org.opensearch.sql.data.model.ExprValueUtils.collectionValue;
import static org.opensearch.sql.data.model.ExprValueUtils.doubleValue;
import static org.opensearch.sql.data.model.ExprValueUtils.integerValue;
import static org.opensearch.sql.data.model.ExprValueUtils.longValue;
import static org.opensearch.sql.data.model.ExprValueUtils.nullValue;
import static org.opensearch.sql.data.model.ExprValueUtils.stringValue;
import static org.opensearch.sql.data.model.ExprValueUtils.tupleValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.calcite.runtime.rtti.BasicSqlTypeRtti;
import org.apache.calcite.runtime.rtti.GenericSqlTypeRtti;
import org.apache.calcite.runtime.rtti.RuntimeTypeInformation.RuntimeSqlTypeName;
import org.apache.calcite.runtime.variant.VariantValue;
import org.junit.jupiter.api.Test;
import org.opensearch.sql.data.model.ExprCollectionValue;
import org.opensearch.sql.data.model.ExprValue;

class OpenSearchVariantValueTest {

  private static VariantValue variant(ExprValue leaf) {
    return OpenSearchExprFlatObjectValue.toVariant(leaf);
  }

  // Numbers first, by numeric value across numeric types; then everything else by its text.
  @Test
  void ordersNumbersFirstThenText() {
    List<VariantValue> values =
        new ArrayList<>(
            List.of(
                variant(stringValue("n/a")),
                variant(doubleValue(12.5)),
                variant(stringValue("4")),
                variant(integerValue(4)),
                variant(longValue(100L)),
                variant(stringValue("12.5"))));
    Collections.sort(values, (a, b) -> ((OpenSearchVariantValue) a).compareTo(b));
    assertEquals(
        List.of(4, 12.5, 100L, "12.5", "4", "n/a"),
        values.stream().map(OpenSearchExprFlatObjectValue::unwrap).toList());
  }

  @Test
  void compareToAgreesWithCalciteVariantsToo() {
    OpenSearchVariantValue ours = (OpenSearchVariantValue) variant(integerValue(4));
    VariantValue theirs =
        org.apache.calcite.runtime.variant.VariantSqlValue.create(
            java.math.RoundingMode.HALF_EVEN,
            12.5,
            new BasicSqlTypeRtti(RuntimeSqlTypeName.DOUBLE));
    assertTrue(ours.compareTo(theirs) < 0);
  }

  // Grouping on the coordinator hashes the keys: equal values of equal type are one bucket, and a
  // number and the text that spells it are not.
  @Test
  void equalityFollowsValueAndType() {
    assertAll(
        () -> assertEquals(variant(integerValue(4)), variant(integerValue(4))),
        () ->
            assertEquals(variant(integerValue(4)).hashCode(), variant(integerValue(4)).hashCode()),
        () -> assertNotEquals(variant(integerValue(4)), variant(stringValue("4"))));
  }

  @Test
  void carriesTheLeafTypeAndUnwrapsToTheSameValue() {
    assertAll(
        () -> assertEquals("INTEGER", variant(integerValue(503)).getTypeString()),
        () -> assertEquals("DOUBLE", variant(doubleValue(12.5)).getTypeString()),
        () -> assertEquals("VARCHAR", variant(stringValue("4")).getTypeString()),
        () -> assertEquals("BOOLEAN", variant(booleanValue(true)).getTypeString()),
        () -> assertEquals(503, OpenSearchExprFlatObjectValue.unwrap(variant(integerValue(503)))),
        () -> assertEquals("4", OpenSearchExprFlatObjectValue.unwrap(variant(stringValue("4")))),
        () -> assertNull(variant(nullValue())));
  }

  // Calcite's own cast rule, preserved by delegation: a numeric variant converts between numeric
  // types; a text variant cast to a number is null rather than parsed.
  @Test
  void castFollowsTheRecordedType() {
    BasicSqlTypeRtti toDouble = new BasicSqlTypeRtti(RuntimeSqlTypeName.DOUBLE);
    assertAll(
        () -> assertEquals(4.0, variant(integerValue(4)).cast(toDouble)),
        () -> assertEquals(12.5, variant(doubleValue(12.5)).cast(toDouble)),
        () -> assertNull(variant(stringValue("4")).cast(toDouble)),
        () -> assertNull(variant(stringValue("n/a")).cast(toDouble)));
  }

  // Arrays: 1-based item access like Calcite's arrays, no cast to a scalar, ordered after numbers.
  @Test
  void arrayVariantIndexesFromOneAndSortsAfterNumbers() {
    VariantValue array = variant(collectionValue(List.of(80, 443)));
    OpenSearchVariantValue number = (OpenSearchVariantValue) variant(integerValue(9));
    assertAll(
        () -> assertEquals("ARRAY", array.getTypeString()),
        () -> assertEquals(80, OpenSearchExprFlatObjectValue.unwrap((VariantValue) array.item(1))),
        () -> assertNull(array.item(3)),
        () -> assertNull(array.item("x")),
        () -> assertNull(array.cast(new BasicSqlTypeRtti(RuntimeSqlTypeName.DOUBLE))),
        () -> assertEquals(List.of(80, 443), OpenSearchExprFlatObjectValue.unwrap(array)),
        () -> assertTrue(number.compareTo(array) < 0),
        () -> assertEquals(variant(collectionValue(List.of(80, 443))), array));
  }

  // An object inside an array: key access, typed values, unwraps to a plain map.
  @Test
  void mapVariantKeepsTypedEntries() {
    VariantValue map = variant(tupleValue(Map.of("k", integerValue(1), "s", stringValue("x"))));
    assertAll(
        () -> assertEquals("MAP", map.getTypeString()),
        () -> assertEquals("INTEGER", ((VariantValue) map.item("k")).getTypeString()),
        () -> assertNull(map.item("missing")),
        () -> assertEquals(Map.of("k", 1, "s", "x"), OpenSearchExprFlatObjectValue.unwrap(map)));
  }

  // Cast to ARRAY<VARIANT> the elements stay the variants they are; cast to an array of a scalar
  // type each element is cast to it, and an element that is not of that type becomes null.
  @Test
  void arrayVariantCastKeepsElementsAsVariantsOrCastsEach() {
    VariantValue mixed =
        variant(new ExprCollectionValue(List.of(integerValue(1), stringValue("x"))));
    GenericSqlTypeRtti arrayOfVariant =
        new GenericSqlTypeRtti(
            RuntimeSqlTypeName.ARRAY, new BasicSqlTypeRtti(RuntimeSqlTypeName.VARIANT));
    GenericSqlTypeRtti arrayOfDouble =
        new GenericSqlTypeRtti(
            RuntimeSqlTypeName.ARRAY, new BasicSqlTypeRtti(RuntimeSqlTypeName.DOUBLE));
    List<?> asVariants = (List<?>) mixed.cast(arrayOfVariant);
    assertAll(
        () -> assertEquals(2, asVariants.size()),
        () -> assertEquals("INTEGER", ((VariantValue) asVariants.get(0)).getTypeString()),
        () -> assertEquals("VARCHAR", ((VariantValue) asVariants.get(1)).getTypeString()),
        () -> assertEquals(java.util.Arrays.asList(1.0, null), mixed.cast(arrayOfDouble)),
        () -> assertNull(mixed.cast(new BasicSqlTypeRtti(RuntimeSqlTypeName.DOUBLE))));
  }

  // A single value cast to an array is an array of that one element -- the multivalue rule for a
  // scalar -- so a leaf that is sometimes an array and sometimes a value reads the same way.
  @Test
  void scalarVariantCastsToAnArrayOfOne() {
    VariantValue scalar = variant(doubleValue(12.5));
    GenericSqlTypeRtti arrayOfVariant =
        new GenericSqlTypeRtti(
            RuntimeSqlTypeName.ARRAY, new BasicSqlTypeRtti(RuntimeSqlTypeName.VARIANT));
    GenericSqlTypeRtti arrayOfVarchar =
        new GenericSqlTypeRtti(
            RuntimeSqlTypeName.ARRAY, new BasicSqlTypeRtti(RuntimeSqlTypeName.VARCHAR));
    assertAll(
        () -> assertEquals(List.of(scalar), scalar.cast(arrayOfVariant)),
        () -> assertEquals(java.util.Arrays.asList((Object) null), scalar.cast(arrayOfVarchar)),
        () -> assertEquals(12.5, scalar.cast(new BasicSqlTypeRtti(RuntimeSqlTypeName.DOUBLE))));
  }

  // A map's values follow the same rule as an array's elements.
  @Test
  void mapVariantCastFollowsTheDeclaredValueType() {
    VariantValue map = variant(tupleValue(Map.of("k", integerValue(1), "s", stringValue("x"))));
    GenericSqlTypeRtti mapOfVariant =
        new GenericSqlTypeRtti(
            RuntimeSqlTypeName.MAP,
            new BasicSqlTypeRtti(RuntimeSqlTypeName.VARCHAR),
            new BasicSqlTypeRtti(RuntimeSqlTypeName.VARIANT));
    GenericSqlTypeRtti mapOfInteger =
        new GenericSqlTypeRtti(
            RuntimeSqlTypeName.MAP,
            new BasicSqlTypeRtti(RuntimeSqlTypeName.VARCHAR),
            new BasicSqlTypeRtti(RuntimeSqlTypeName.INTEGER));
    Map<?, ?> asVariants = (Map<?, ?>) map.cast(mapOfVariant);
    Map<?, ?> asIntegers = (Map<?, ?>) map.cast(mapOfInteger);
    assertAll(
        () -> assertEquals("INTEGER", ((VariantValue) asVariants.get("k")).getTypeString()),
        () -> assertEquals(1, asIntegers.get("k")),
        () -> assertNull(asIntegers.get("s")),
        () -> assertNull(map.cast(new BasicSqlTypeRtti(RuntimeSqlTypeName.VARCHAR))));
  }
}
