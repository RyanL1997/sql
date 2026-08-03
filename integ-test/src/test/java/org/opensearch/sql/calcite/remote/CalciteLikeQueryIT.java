/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.calcite.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.opensearch.sql.legacy.TestsConstants.TEST_INDEX_WILDCARD;
import static org.opensearch.sql.util.Capability.LIKE_CASE_SENSITIVITY;
import static org.opensearch.sql.util.MatcherUtils.rows;
import static org.opensearch.sql.util.MatcherUtils.verifyDataRows;
import static org.opensearch.sql.util.MatcherUtils.verifyNumOfRows;

import java.io.IOException;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import org.opensearch.sql.common.setting.Settings;
import org.opensearch.sql.ppl.LikeQueryIT;
import org.opensearch.sql.util.RequiresCapability;

public class CalciteLikeQueryIT extends LikeQueryIT {
  @Override
  public void init() throws Exception {
    super.init();
    enableCalcite();
  }

  @Override
  @Test
  public void test_convert_field_text_to_keyword() throws IOException {
    enabledOnlyWhenPushdownIsEnabled();
    super.test_convert_field_text_to_keyword();
  }

  @Test
  public void test_ilike_is_case_insensitive() throws IOException {
    String query =
        "source="
            + TEST_INDEX_WILDCARD
            + " | WHERE ILike(KeywordBody, 'test Wildcard%') | fields KeywordBody";
    JSONObject result = executeQuery(query);
    verifyDataRows(
        result,
        rows("test wildcard"),
        rows("test wildcard in the end of the text%"),
        rows("test wildcard in % the middle of the text"),
        rows("test wildcard %% beside each other"),
        rows("test wildcard in the end of the text_"),
        rows("test wildcard in _ the middle of the text"),
        rows("test wildcard __ beside each other"));
  }

  @Test
  @RequiresCapability(
      value = LIKE_CASE_SENSITIVITY,
      note = "case-sensitive LIKE (legacy=false) expects 0 rows; AE LIKE is case-insensitive.")
  public void test_the_default_3rd_option() throws IOException {
    // only work in v3
    String query =
        "source="
            + TEST_INDEX_WILDCARD
            + " | WHERE Like(KeywordBody, 'test Wildcard%') | fields KeywordBody";
    withSettings(
        Settings.Key.PPL_SYNTAX_LEGACY_PREFERRED,
        "true",
        () -> {
          try {
            JSONObject result = executeQuery(query);
            verifyDataRows(
                result,
                rows("test wildcard"),
                rows("test wildcard in the end of the text%"),
                rows("test wildcard in % the middle of the text"),
                rows("test wildcard %% beside each other"),
                rows("test wildcard in the end of the text_"),
                rows("test wildcard in _ the middle of the text"),
                rows("test wildcard __ beside each other"));
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
    withSettings(
        Settings.Key.PPL_SYNTAX_LEGACY_PREFERRED,
        "false",
        () -> {
          try {
            JSONObject result = executeQuery(query);
            verifyNumOfRows(result, 0);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
  }

  /**
   * TextBody is an analyzed text field with no keyword sub-field, so LIKE over it cannot be pushed
   * down exactly. With {@link Settings.Key#CALCITE_PUSHDOWN_LIKE_ON_TEXT_ENABLED} a wildcard query
   * matching a super-set is pushed instead and the exact LIKE is re-applied above the scan, so the
   * result must be identical to running without the setting.
   */
  @Test
  public void test_approximate_like_pushdown_on_text_field_preserves_results() throws IOException {
    enabledOnlyWhenPushdownIsEnabled();
    for (String pattern :
        List.of(
            "%ildcar%", // matches inside a token: a match query would find nothing here
            "test%", // anchored, but '%test wildcard...' has a leading '%' and must not match
            "%text", //
            "%test wild%", // straddles a token boundary: must not be approximated
            "%wildcard%")) {
      String query =
          "source="
              + TEST_INDEX_WILDCARD
              + " | WHERE Like(TextBody, '"
              + pattern
              + "') | sort TextBody | fields TextBody";
      JSONObject expected = executeQuery(query);
      withSettings(
          Settings.Key.CALCITE_PUSHDOWN_LIKE_ON_TEXT_ENABLED,
          "true",
          () -> {
            try {
              assertEquals(
                  "approximate push down changed the result of LIKE '" + pattern + "'",
                  expected.getJSONArray("datarows").toString(),
                  executeQuery(query).getJSONArray("datarows").toString());
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          });
    }
  }

  @Test
  public void test_approximate_like_pushdown_on_text_field_pushes_wildcard() throws IOException {
    enabledOnlyWhenPushdownIsEnabled();
    String query =
        "source=" + TEST_INDEX_WILDCARD + " | WHERE Like(TextBody, '%ildcar%') | fields TextBody";
    // Without the setting the predicate is only enforced by a script query, which the inverted
    // index cannot help with: every candidate document is scanned.
    String before = explainQueryToString(query);
    assertTrue(before, before.contains("SCRIPT->ILIKE"));
    assertFalse(before, before.contains("*ildcar*"));

    withSettings(
        Settings.Key.CALCITE_PUSHDOWN_LIKE_ON_TEXT_ENABLED,
        "true",
        () -> {
          try {
            String explain = explainQueryToString(query);
            // The wildcard super-set query is pushed as a cheap pre-filter ...
            assertTrue(explain, explain.contains("*ildcar*"));
            // ... while the exact predicate is still enforced. The first rule firing leaves it as
            // a residual filter above the scan; the second firing is refused a repeated
            // approximation and pushes it exactly instead, as a script. The two end up conjoined,
            // so the wildcard narrows the candidate set the script has to evaluate.
            assertTrue(explain, explain.contains("SCRIPT->ILIKE"));
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
  }
}
