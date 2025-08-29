/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.expression.function.udf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

public class RexOffsetFunctionTest {

  private final RexOffsetFunction function = new RexOffsetFunction();

  @Test
  public void testCalculateOffsets_SimpleExample() {
    String text = "user@domain.com";
    String pattern = "(?<username>\\w+)@(?<domain>\\w+)\\.(?<tld>\\w+)";

    String result = RexOffsetFunction.calculateOffsets(text, pattern, 1);
    // Groups appear in pattern order: username, domain, tld
    assertEquals("username=0-3&domain=5-10&tld=12-14", result);
  }

  @Test
  public void testCalculateOffsets_SingleNamedGroup() {
    String text = "hello world";
    String pattern = "(?<word>\\w+)";

    String result = RexOffsetFunction.calculateOffsets(text, pattern, 1);
    assertEquals("word=0-4", result);
  }

  @Test
  public void testCalculateOffsets_TwoGroups() {
    String text = "abc123";
    String pattern = "(?<letters>[a-z]+)(?<numbers>\\d+)";

    String result = RexOffsetFunction.calculateOffsets(text, pattern, 1);
    assertEquals("letters=0-2&numbers=3-5", result);
  }

  @Test
  public void testCalculateOffsets_NoMatches() {
    String text = "This text has no digits";
    String pattern = "(?<digit>\\d+)";

    String result = RexOffsetFunction.calculateOffsets(text, pattern, 1);
    assertNull(result);
  }

  @Test
  public void testCalculateOffsets_NullInputs() {
    // Null text
    String result = RexOffsetFunction.calculateOffsets(null, "(?<test>\\w+)", 1);
    assertNull(result);

    // Null pattern
    result = RexOffsetFunction.calculateOffsets("test text", null, 1);
    assertNull(result);

    // Both null
    result = RexOffsetFunction.calculateOffsets(null, null, 1);
    assertNull(result);
  }

  @Test
  public void testCalculateOffsets_InvalidPattern() {
    String text = "test string";
    String invalidPattern = "[unclosed";

    String result = RexOffsetFunction.calculateOffsets(text, invalidPattern, 1);
    assertNull(result);
  }

  @Test
  public void testCalculateOffsets_EmptyString() {
    String text = "";
    String pattern = "(?<word>\\w+)";

    String result = RexOffsetFunction.calculateOffsets(text, pattern, 1);
    assertNull(result);
  }

  @Test
  public void testCalculateOffsets_PatternWithoutNamedGroups() {
    String text = "test123";
    String pattern = "(\\w+)(\\d+)";

    String result = RexOffsetFunction.calculateOffsets(text, pattern, 1);
    assertNull(result);
  }

  @Test
  public void testCalculateOffsets_SingleCharacterMatch() {
    String text = "a";
    String pattern = "(?<char>[a-z])";

    String result = RexOffsetFunction.calculateOffsets(text, pattern, 1);
    assertEquals("char=0-0", result);
  }

  @Test
  public void testCalculateOffsets_DigitsPattern() {
    String text = "year 2023 month 12";
    String pattern = "(?<year>\\d{4}).*(?<month>\\d{2})";

    String result = RexOffsetFunction.calculateOffsets(text, pattern, 1);
    assertEquals("year=5-8&month=16-17", result);
  }

  @Test
  public void testCalculateOffsets_EmailExample() {
    String text = "email: john@example.org";
    String pattern = "(?<name>\\w+)@(?<domain>\\w+)\\.(?<ext>\\w+)";

    String result = RexOffsetFunction.calculateOffsets(text, pattern, 1);
    assertEquals("name=7-10&domain=12-18&ext=20-22", result);
  }

  @Test
  public void testReturnTypeInference() {
    assertNotNull(function.getReturnTypeInference(), "Return type inference should not be null");
  }

  @Test
  public void testOperandMetadata() {
    assertNotNull(function.getOperandMetadata(), "Operand metadata should not be null");
  }

  @Test
  public void testFunctionConstructor() {
    RexOffsetFunction testFunction = new RexOffsetFunction();
    assertNotNull(testFunction, "Function should be properly initialized");
  }

  @Test
  public void testCalculateOffsets_MaxMatchMultiple() {
    String text = "880 Holmes Lane";
    String pattern = "(?<digit>\\d)";

    String result = RexOffsetFunction.calculateOffsets(text, pattern, 2);
    assertEquals("digit=0-0&digit=1-1", result);
  }

  @Test
  public void testCalculateOffsets_MaxMatchUnlimited() {
    String text = "880 Holmes Lane";
    String pattern = "(?<digit>\\d)";

    String result = RexOffsetFunction.calculateOffsets(text, pattern, 0);
    assertEquals("digit=0-0&digit=1-1&digit=2-2", result);
  }

  @Test
  public void testCalculateOffsets_MaxMatchExceedsAvailable() {
    String text = "880";
    String pattern = "(?<digit>\\d)";

    String result = RexOffsetFunction.calculateOffsets(text, pattern, 10);
    assertEquals("digit=0-0&digit=1-1&digit=2-2", result);
  }
}
