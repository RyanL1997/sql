/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.calcite.combination;

import java.io.IOException;
import org.json.JSONObject;
import org.opensearch.sql.calcite.combination.DifferentialComparator.Result;
import org.opensearch.sql.common.setting.Settings;
import org.opensearch.sql.legacy.SQLIntegTestCase;
import org.opensearch.sql.ppl.PPLIntegTestCase;

/**
 * Base for differential-oracle integration tests: loads the {@code bank} index with Calcite enabled
 * and compares a query's results with pushdown ON vs OFF on the same cluster, restoring the default
 * afterwards. The comparison is schema-checked, order-insensitive and ULP-tolerant via {@link
 * DifferentialComparator}.
 */
public abstract class PushdownDifferentialTestCase extends PPLIntegTestCase {

  @Override
  public void init() throws Exception {
    super.init();
    enableCalcite();
    loadIndex(Index.BANK);
  }

  /**
   * Run {@code query} with pushdown ON then OFF (restoring the default), and compare the results.
   */
  protected Result comparePushdownOnVsOff(String query) throws IOException {
    JSONObject on = runWithPushdown(true, query);
    JSONObject off;
    try {
      off = runWithPushdown(false, query);
    } finally {
      setPushdownEnabled(true);
    }
    return DifferentialComparator.compare(
        QueryResults.schemaOf(on),
        QueryResults.schemaOf(off),
        QueryResults.rowsOf(on),
        QueryResults.rowsOf(off));
  }

  private JSONObject runWithPushdown(boolean enabled, String query) throws IOException {
    setPushdownEnabled(enabled);
    return executeQuery(query);
  }

  private void setPushdownEnabled(boolean enabled) throws IOException {
    updateClusterSettings(
        new SQLIntegTestCase.ClusterSetting(
            "transient",
            Settings.Key.CALCITE_PUSHDOWN_ENABLED.getKeyValue(),
            String.valueOf(enabled)));
  }
}
