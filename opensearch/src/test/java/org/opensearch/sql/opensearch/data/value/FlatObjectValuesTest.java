/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.opensearch.data.value;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.opensearch.sql.data.model.ExprValueUtils.booleanValue;
import static org.opensearch.sql.data.model.ExprValueUtils.collectionValue;
import static org.opensearch.sql.data.model.ExprValueUtils.doubleValue;
import static org.opensearch.sql.data.model.ExprValueUtils.integerValue;
import static org.opensearch.sql.data.model.ExprValueUtils.nullValue;
import static org.opensearch.sql.data.model.ExprValueUtils.stringValue;
import static org.opensearch.sql.data.model.ExprValueUtils.tupleValue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.calcite.runtime.variant.VariantValue;
import org.junit.jupiter.api.Test;
import org.opensearch.sql.data.model.ExprCollectionValue;
import org.opensearch.sql.data.model.ExprValue;
import org.opensearch.sql.opensearch.data.utils.ObjectContent;

class FlatObjectValuesTest {

  // The script on the data node receives the raw _source map. It must produce the same shape the
  // coordinator produces, or a pushed-down filter and a projected row would disagree.
  @Test
  void flattenForScript_matchesCoordinatorShape() {
    Map<String, Object> nested = Map.of("http", Map.of("response", Map.of("status_code", 503)));
    Map<String, Object> dotted = Map.of("http.response.status_code", 503);

    Object fromNested = FlatObjectValues.flattenForScript(nested);
    Object fromDotted = FlatObjectValues.flattenForScript(dotted);
    Object coordinator = FlatObjectValues.flatten(new ObjectContent(nested)).valueForCalcite();

    assertAll(
        () -> assertEquals(fromNested, fromDotted),
        () -> assertEquals(coordinator, fromNested),
        () -> assertEquals(1, ((Map<?, ?>) fromNested).size()),
        () ->
            assertEquals(
                503,
                OpenSearchExprFlatObjectValue.unwrap(
                    (VariantValue) ((Map<?, ?>) fromNested).get("http.response.status_code"))));
  }

  // Each leaf handed to Calcite is a VARIANT tagged with the leaf's own type.
  @Test
  void flattenForScript_leavesAreTypedVariants() {
    Map<String, Object> source = new HashMap<>();
    source.put("n", 12.5);
    source.put("s", "4");
    source.put("z", null);

    Map<?, ?> flattened = (Map<?, ?>) FlatObjectValues.flattenForScript(source);
    VariantValue n = (VariantValue) flattened.get("n");
    VariantValue s = (VariantValue) flattened.get("s");
    assertAll(
        () -> assertInstanceOf(OpenSearchVariantValue.class, n),
        () -> assertEquals("DOUBLE", n.getTypeString()),
        () -> assertEquals(12.5, OpenSearchExprFlatObjectValue.unwrap(n)),
        () -> assertEquals("VARCHAR", s.getTypeString()),
        () -> assertEquals("4", OpenSearchExprFlatObjectValue.unwrap(s)),
        // A null leaf is SQL NULL to Calcite, not a variant null.
        () -> assertNull(flattened.get("z")));
  }

  @Test
  void flattenForScript_nonObjectIsNull() {
    assertAll(
        () -> assertNull(FlatObjectValues.flattenForScript(null)),
        () -> assertNull(FlatObjectValues.flattenForScript("attributes.duration_ms=12.5")),
        () -> assertNull(FlatObjectValues.flattenForScript(List.of(1, 2))));
  }

  @Test
  void flatten_leavesKeepTheirTypeAndNullIsKept() {
    Map<String, Object> source = new HashMap<>();
    source.put("n", 12.5);
    source.put("i", 503);
    source.put("s", "4");
    source.put("z", null);
    ExprValue value = FlatObjectValues.flatten(new ObjectContent(source));
    assertAll(
        () -> assertInstanceOf(OpenSearchExprFlatObjectValue.class, value),
        () -> assertEquals(doubleValue(12.5), value.tupleValue().get("n")),
        () -> assertEquals(integerValue(503), value.tupleValue().get("i")),
        () -> assertEquals(stringValue("4"), value.tupleValue().get("s")),
        () -> assertEquals(nullValue(), value.tupleValue().get("z")));
  }

  @Test
  void flatten_nonObjectIsNullValue() {
    assertEquals(nullValue(), FlatObjectValues.flatten(new ObjectContent(5)));
  }

  // An array leaf stays an array; each element is typed on its own, so a number next to a string
  // is a number next to a string, not two strings.
  @Test
  void flatten_arrayStaysAnArrayWithTypedElements() {
    Map<String, Object> source =
        Map.of(
            "tags", List.of("a", "b"), "ports", List.of(80, 443), "mixed", List.of(1, "x", true));
    ExprValue value = FlatObjectValues.flatten(new ObjectContent(source));
    assertAll(
        () -> assertEquals(collectionValue(List.of("a", "b")), value.tupleValue().get("tags")),
        () -> assertEquals(collectionValue(List.of(80, 443)), value.tupleValue().get("ports")),
        () ->
            assertEquals(
                new ExprCollectionValue(
                    List.of(integerValue(1), stringValue("x"), booleanValue(true))),
                value.tupleValue().get("mixed")));
  }

  // An object inside an array keeps its boundaries as a tuple; flattening it into the parent
  // would merge the array's elements together.
  @Test
  void flatten_objectInsideArrayKeepsItsBoundaries() {
    Map<String, Object> source = Map.of("nested", List.of(Map.of("k", 1), Map.of("k", 2)));
    ExprValue value = FlatObjectValues.flatten(new ObjectContent(source));
    assertEquals(
        new ExprCollectionValue(
            List.of(
                tupleValue(Map.of("k", integerValue(1))),
                tupleValue(Map.of("k", integerValue(2))))),
        value.tupleValue().get("nested"));
  }

  @Test
  void flattenForScript_arrayBecomesAnArrayVariantOfTypedElements() {
    Map<?, ?> flattened =
        (Map<?, ?>) FlatObjectValues.flattenForScript(Map.of("mixed", List.of(1, "x")));
    VariantValue mixed = (VariantValue) flattened.get("mixed");
    assertAll(
        () -> assertInstanceOf(OpenSearchVariantValue.Array.class, mixed),
        () -> assertEquals("ARRAY", mixed.getTypeString()),
        () -> assertEquals(List.of(1, "x"), OpenSearchExprFlatObjectValue.unwrap(mixed)),
        () -> assertEquals("INTEGER", ((VariantValue) mixed.item(1)).getTypeString()),
        () -> assertEquals("VARCHAR", ((VariantValue) mixed.item(2)).getTypeString()));
  }
}
