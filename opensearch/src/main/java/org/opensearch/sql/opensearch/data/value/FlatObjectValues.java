/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.opensearch.data.value;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.experimental.UtilityClass;
import org.opensearch.sql.data.model.ExprCollectionValue;
import org.opensearch.sql.data.model.ExprNullValue;
import org.opensearch.sql.data.model.ExprTupleValue;
import org.opensearch.sql.data.model.ExprValue;
import org.opensearch.sql.opensearch.data.utils.Content;
import org.opensearch.sql.opensearch.data.utils.ObjectContent;

/**
 * Turns a flat_object subtree into the one shape the engine presents it as: a single-level map from
 * dotted leaf path to a value that keeps the type it was written with.
 *
 * <p>A flat_object declares no sub-fields, so its shape is known only from the document, and two
 * producers may write the same logical path differently: {@code {"a.b": 1}} and {@code {"a": {"b":
 * 1}}}. OpenSearch indexes both as the one term {@code a.b=1}; flattening makes both spellings
 * resolve to the same entry, which is what a dotted path expression looks up. Each leaf keeps its
 * {@code _source} type -- {@code 12.5} is a number, {@code "4"} is text -- which the index has
 * discarded but the document still records. Arrays are kept as a single leaf.
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
    LinkedHashMap<String, ExprValue> leaves = new LinkedHashMap<>();
    flattenInto(content, "", leaves, 0);
    return new OpenSearchExprFlatObjectValue(leaves);
  }

  /**
   * Flatten a flat_object value read from {@code _source} on a data node, in the form a script
   * consumes: a {@code Map<String, Object>} whose values are VARIANTs.
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

  /**
   * A leaf value, typed by the value itself exactly as an unmapped field is. An array stays an
   * array with each element typed on its own; an object inside an array keeps its boundaries as a
   * tuple, since flattening it into the parent would merge the array's elements together.
   */
  private static ExprValue leaf(Content value, int depth) {
    if (value.isArray()) {
      List<ExprValue> elements = new ArrayList<>();
      value.array().forEachRemaining(element -> elements.add(leaf(element, depth)));
      return new ExprCollectionValue(elements);
    }
    if (value.isObject() && depth < MAX_DEPTH) {
      LinkedHashMap<String, ExprValue> entries = new LinkedHashMap<>();
      flattenInto(value, "", entries, depth + 1);
      return ExprTupleValue.fromExprValueMap(entries);
    }
    return OpenSearchExprValueFactory.parseContent(value);
  }

  private static void flattenInto(
      Content content, String prefix, LinkedHashMap<String, ExprValue> out, int depth) {
    content
        .map()
        .forEachRemaining(
            entry -> {
              String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
              Content value = entry.getValue();
              if (value.isObject() && depth < MAX_DEPTH) {
                flattenInto(value, key, out, depth + 1);
              } else {
                out.put(key, leaf(value, depth));
              }
            });
  }
}
