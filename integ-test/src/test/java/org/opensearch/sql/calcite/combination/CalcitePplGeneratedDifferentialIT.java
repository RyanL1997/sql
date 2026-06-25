/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.calcite.combination;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.opensearch.sql.legacy.TestsConstants.TEST_INDEX_BANK;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.opensearch.sql.calcite.combination.DifferentialComparator.Result;

/**
 * The generative capstone: every pipeline {@link PipelineGenerator} emits is run through the live
 * differential oracle. Two properties are checked per pipeline:
 *
 * <ul>
 *   <li>pushdown ON and OFF agree (the differential oracle), and
 *   <li>a valid pipeline does not throw an unexpected error (an error-classification check).
 * </ul>
 *
 * This is the scalable coverage payoff: adding a command template to {@link PipelineGenerator} (or
 * a field to the index profile) expands what is exercised here with no new test code.
 */
public class CalcitePplGeneratedDifferentialIT extends PushdownDifferentialTestCase {

  @Test
  public void generatedPipelinesArePushdownInvariant() {
    List<String> pipelines = PipelineGenerator.generate(TEST_INDEX_BANK, CombinationModel.BANK);
    assertFalse("generator produced no pipelines", pipelines.isEmpty());

    List<String> problems = new ArrayList<>();
    for (String query : pipelines) {
      try {
        Result result = comparePushdownOnVsOff(query);
        if (!result.equal()) {
          problems.add(query + "  ->  " + result.reason());
        }
      } catch (Exception e) {
        problems.add(query + "  -> threw " + e.getClass().getSimpleName() + ": " + e.getMessage());
      }
    }
    assertTrue(
        pipelines.size()
            + " generated pipelines; divergent or failing:\n  "
            + String.join("\n  ", problems),
        problems.isEmpty());
  }
}
