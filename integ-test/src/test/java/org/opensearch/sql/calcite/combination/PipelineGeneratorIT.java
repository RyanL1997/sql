/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.calcite.combination;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

/**
 * Cluster-free validation that {@link PipelineGenerator} emits only <b>reasonable</b> pipelines:
 * well-formed, two commands, and — crucially — never referencing a field a prior command dropped.
 */
public class PipelineGeneratorIT {

  private static final List<String> PIPELINES =
      PipelineGenerator.generate("bank", CombinationModel.BANK);

  @Test
  public void generatesWellFormedTwoCommandPipelines() {
    assertFalse("generator produced no pipelines", PIPELINES.isEmpty());
    assertTrue(
        "expected a non-trivial number of pipelines, got " + PIPELINES.size(),
        PIPELINES.size() >= 8);
    for (String pipeline : PIPELINES) {
      assertTrue(pipeline, pipeline.startsWith("source=bank | "));
      assertEquals(
          "a two-command pipeline has exactly two pipes: " + pipeline,
          2,
          pipeline.chars().filter(c -> c == '|').count());
    }
  }

  @Test
  public void respectsFieldAvailabilityAfterStats() {
    // After `stats count() as c by male` only {male, c} survive; a following `where` must reference
    // the surviving aggregate output c, never a dropped field such as age.
    assertTrue(
        "expected a stats-then-where on the surviving aggregate column:\n" + PIPELINES,
        PIPELINES.contains("source=bank | stats count() as c by male | where c > 30"));
    assertFalse(
        "must NOT reference a field dropped by stats:\n" + PIPELINES,
        PIPELINES.stream().anyMatch(p -> p.contains("stats count() as c by male | where age")));
  }

  @Test
  public void doesNotEmitRedundantAdjacentDuplicates() {
    assertFalse(
        "no redundant A | A adjacency:\n" + PIPELINES,
        PIPELINES.stream().anyMatch(p -> p.matches("source=bank \\| (\\w+) .*\\| \\1 .*")));
  }
}
