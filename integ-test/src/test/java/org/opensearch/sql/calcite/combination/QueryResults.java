/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.calcite.combination;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.opensearch.sql.calcite.combination.DifferentialComparator.Schema;

/**
 * Parses a PPL JSON query response ({@code {"schema":[...], "datarows":[...]}}) into the {@link
 * Schema} and row-list shapes the {@link DifferentialComparator} consumes.
 */
public final class QueryResults {

  private QueryResults() {}

  public static Schema schemaOf(JSONObject response) {
    JSONArray schema = response.getJSONArray("schema");
    List<String> names = new ArrayList<>();
    List<String> types = new ArrayList<>();
    for (int i = 0; i < schema.length(); i++) {
      JSONObject column = schema.getJSONObject(i);
      names.add(column.getString("name"));
      types.add(column.optString("type", ""));
    }
    return new Schema(names, types);
  }

  public static List<List<Object>> rowsOf(JSONObject response) {
    JSONArray datarows = response.getJSONArray("datarows");
    List<List<Object>> rows = new ArrayList<>();
    for (int i = 0; i < datarows.length(); i++) {
      JSONArray row = datarows.getJSONArray(i);
      List<Object> cells = new ArrayList<>();
      for (int j = 0; j < row.length(); j++) {
        cells.add(row.isNull(j) ? null : row.get(j));
      }
      rows.add(cells);
    }
    return rows;
  }
}
