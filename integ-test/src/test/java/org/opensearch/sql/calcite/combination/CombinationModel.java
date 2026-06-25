/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.calcite.combination;

import java.util.Map;

/**
 * The field-type model that makes the shape oracle's expectations field-type-aware. Whether a
 * {@code sort}, {@code dedup} or {@code stats ... by} on a field pushes down depends on the field's
 * type: OpenSearch can sort/terms-aggregate a {@code keyword}, numeric, date or {@code
 * text}-with-a-{@code .keyword}-subfield field natively, but NOT a raw {@code text} field (no
 * fielddata). The oracle must therefore expect <b>no</b> token for {@code sort
 * <text-without-keyword>}, or it would false-positive on a legitimately-unpushable combination.
 *
 * <p>The {@link #BANK} profile is the canonical eligibility driver — one index that exercises every
 * branch (keyword / text-only / text+keyword / numeric / date / boolean).
 */
public final class CombinationModel {

  private CombinationModel() {}

  /**
   * {@code TEXT} = a {@code text} field with no {@code .keyword} subfield (cannot push natively).
   */
  public enum FieldType {
    KEYWORD,
    TEXT,
    TEXT_WITH_KEYWORD,
    NUMERIC,
    DATE,
    BOOLEAN
  }

  /**
   * Field types of {@code opensearch-sql_test_index_bank} (see {@code bank_index_mapping.json}).
   */
  public static final Map<String, FieldType> BANK =
      Map.ofEntries(
          Map.entry("account_number", FieldType.NUMERIC),
          Map.entry("balance", FieldType.NUMERIC),
          Map.entry("age", FieldType.NUMERIC),
          Map.entry("birthdate", FieldType.DATE),
          Map.entry("firstname", FieldType.KEYWORD),
          Map.entry("lastname", FieldType.KEYWORD),
          Map.entry("city", FieldType.KEYWORD),
          Map.entry("address", FieldType.TEXT),
          Map.entry("email", FieldType.TEXT),
          Map.entry("employer", FieldType.TEXT),
          Map.entry("state", FieldType.TEXT_WITH_KEYWORD),
          Map.entry("gender", FieldType.TEXT_WITH_KEYWORD),
          Map.entry("male", FieldType.BOOLEAN));

  /** A field is natively sortable / terms-aggregatable only if it does not require fielddata. */
  public static boolean nativelyPushable(FieldType type) {
    return type != FieldType.TEXT;
  }

  /**
   * Whether {@code sort <field>} / {@code stats ... by <field>} pushes natively on the given index.
   *
   * @throws IllegalArgumentException if the field is not in the index profile
   */
  public static boolean pushesNatively(Map<String, FieldType> index, String field) {
    FieldType type = index.get(field);
    if (type == null) {
      throw new IllegalArgumentException("unknown field '" + field + "' in index profile");
    }
    return nativelyPushable(type);
  }
}
