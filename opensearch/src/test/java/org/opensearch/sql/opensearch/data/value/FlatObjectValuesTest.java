/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.opensearch.data.value;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.opensearch.sql.data.model.ExprValueUtils.nullValue;
import static org.opensearch.sql.data.model.ExprValueUtils.stringValue;
import static org.opensearch.sql.data.model.ExprValueUtils.tupleValue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
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
        () -> assertEquals(Map.of("http.response.status_code", "503"), fromNested),
        () -> assertEquals(fromNested, fromDotted),
        () -> assertEquals(coordinator, fromNested));
  }

  @Test
  void flattenForScript_nonObjectIsNull() {
    assertAll(
        () -> assertNull(FlatObjectValues.flattenForScript(null)),
        () -> assertNull(FlatObjectValues.flattenForScript("attributes.duration_ms=12.5")),
        () -> assertNull(FlatObjectValues.flattenForScript(List.of(1, 2))));
  }

  @Test
  void flatten_leavesAreStringsAndNullIsKept() {
    Map<String, Object> source = new java.util.HashMap<>();
    source.put("n", 12.5);
    source.put("s", "4");
    source.put("z", null);
    assertEquals(
        tupleValue(Map.of("n", stringValue("12.5"), "s", stringValue("4"))).tupleValue().get("n"),
        FlatObjectValues.flatten(new ObjectContent(source)).tupleValue().get("n"));
    assertEquals(
        nullValue(), FlatObjectValues.flatten(new ObjectContent(source)).tupleValue().get("z"));
  }

  @Test
  void flatten_nonObjectIsNullValue() {
    assertEquals(nullValue(), FlatObjectValues.flatten(new ObjectContent(5)));
  }
}
