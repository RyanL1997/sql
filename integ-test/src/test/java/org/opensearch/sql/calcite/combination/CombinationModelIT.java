/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.calcite.combination;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.Test;
import org.opensearch.sql.calcite.combination.CombinationModel.FieldType;

/**
 * Validates the field-type eligibility model that keeps the shape oracle from false-positiving on
 * legitimately-unpushable combinations.
 */
public class CombinationModelIT {

  @Test
  public void rawTextFieldsAreNotNativelyPushable() {
    assertFalse(
        "address is raw text (no .keyword)",
        CombinationModel.pushesNatively(CombinationModel.BANK, "address"));
    assertFalse(
        "employer is raw text (no .keyword)",
        CombinationModel.pushesNatively(CombinationModel.BANK, "employer"));
    assertFalse(
        "email is raw text (no .keyword)",
        CombinationModel.pushesNatively(CombinationModel.BANK, "email"));
  }

  @Test
  public void keywordNumericDateAndTextWithKeywordArePushable() {
    for (String field :
        new String[] {"firstname", "city", "state", "gender", "age", "birthdate", "male"}) {
      assertTrue(
          field + " should push natively",
          CombinationModel.pushesNatively(CombinationModel.BANK, field));
    }
  }

  @Test
  public void onlyRawTextFieldsAreTheUnpushableSet() {
    Set<String> unpushable =
        CombinationModel.BANK.keySet().stream()
            .filter(f -> !CombinationModel.pushesNatively(CombinationModel.BANK, f))
            .collect(Collectors.toCollection(TreeSet::new));
    assertEquals(new TreeSet<>(Set.of("address", "email", "employer")), unpushable);
  }

  @Test
  public void textWithKeywordIsDistinctFromRawText() {
    assertEquals(FieldType.TEXT_WITH_KEYWORD, CombinationModel.BANK.get("state"));
    assertEquals(FieldType.TEXT, CombinationModel.BANK.get("address"));
  }
}
