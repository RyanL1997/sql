/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.opensearch.data.value;

import java.util.Map;
import lombok.experimental.UtilityClass;
import org.opensearch.sql.data.model.ExprNullValue;
import org.opensearch.sql.data.model.ExprStringValue;
import org.opensearch.sql.data.model.ExprTupleValue;
import org.opensearch.sql.data.model.ExprValue;
import org.opensearch.sql.opensearch.data.utils.Content;
import org.opensearch.sql.opensearch.data.utils.ObjectContent;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns a flat_object subtree into the one shape the engine presents it as: a single-level map from
 * dotted leaf path to string value.
 *
 * <p>A flat_object declares no sub-fields, so its shape is known only from the document, and two
 * producers may write the same logical path differently: {@code {"a.b": 1}} and {@code {"a": {"b":
 * 1}}}. OpenSearch indexes both as the one term {@code a.b=1}; flattening makes both spellings
 * resolve to the same entry, which is what a dotted path expression looks up. Every leaf becomes a
 * string, mirroring how the index stores it (a keyword term), so a flat_object behaves like the map
 * that {@code json_extract_all} produces. Arrays are kept as a single leaf.
 *
 * <p>This is the only place that logic lives. The coordinator calls it when it builds a row from
 * {@code _source}, and a pushed-down script calls it on the data node when it reads the same field,
 * so a filter, sort or aggregation evaluated either way sees the same map.
 */
@UtilityClass
public class FlatObjectValues {

  /**
   * Depth guard. A flat_object exists so that documents can escape the mapping depth limit, so
   * nothing upstream bounds this recursion. Levels beyond the cap are kept as one leaf rather than
   * dropped.
   */
  static final int MAX_DEPTH = 20;

  /**
   * Flatten a parsed flat_object value.
   *
   * @param content the field's value as parsed from {@code _source}
   * @return a tuple keyed by dotted leaf path, or null if the value is not an object (a scalar or
   *     array where the mapping promised an object, e.g. a wildcard over conflicting mappings)
   */
  public static ExprValue flatten(Content content) {
    if (content == null || content.isNull() || !content.isObject()) {
      return ExprNullValue.of();
    }
    ExprTupleValue result = ExprTupleValue.empty();
    flattenInto(content, "", result, 0);
    return result;
  }

  /**
   * Flatten a flat_object value read from {@code _source} on a data node, in the form a script
   * consumes: a {@code Map<String, Object>} whose values are strings.
   *
   * @param source the field's {@code _source} value, as returned by {@code
   *     SourceLookup#extractValue}; expected to be a map
   * @return the flattened map, or null if the value is absent or not an object
   */
  public static Object flattenForScript(Object source) {
    if (!(source instanceof Map)) {
      return null;
    }
    return flatten(new ObjectContent(source)).valueForCalcite();
  }

  private static void flattenInto(Content content, String prefix, ExprTupleValue out, int depth) {
    content
        .map()
        .forEachRemaining(
            entry -> {
              String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
              Content value = entry.getValue();
              if (value.isObject() && depth < MAX_DEPTH) {
                flattenInto(value, key, out, depth + 1);
              } else if (value.isNull()) {
                out.tupleValue().put(key, ExprNullValue.of());
              } else {
                out.tupleValue().put(key, new ExprStringValue(textOf(value)));
              }
            });
  }

  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * The text form of a leaf, decided by its type rather than by trying a string cast and catching
   * the failure -- a numeric leaf is the common case, and an exception per leaf per document is the
   * dominant cost on the data node. Arrays and objects become compact JSON on both the coordinator
   * (a Jackson node) and the data node (a plain map), so the two paths agree.
   */
  private static String textOf(Content value) {
    if (value.isString()) {
      return value.stringValue();
    }
    if (value.isNumber() || value.isBoolean()) {
      return String.valueOf(value.objectValue());
    }
    Object raw = value.objectValue();
    if (raw instanceof JsonNode node) {
      return node.toString();
    }
    return JSON.writeValueAsString(raw);
  }
}
