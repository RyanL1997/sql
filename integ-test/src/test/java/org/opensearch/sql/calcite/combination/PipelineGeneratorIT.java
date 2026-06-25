/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.calcite.combination;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Cluster-free validation that {@link PipelineGenerator} emits only <b>reasonable</b> pipelines:
 * well-formed, 2..3 commands, never referencing a field a prior command dropped, and never
 * repeating a command adjacently.
 */
public class PipelineGeneratorIT {

  private static final List<String> PIPELINES =
      PipelineGenerator.generate("bank", CombinationModel.BANK);

  @Test
  public void generatesWellFormedTwoOrThreeCommandPipelines() {
    assertFalse("generator produced no pipelines", PIPELINES.isEmpty());
    assertTrue(
        "expected a non-trivial number of pipelines, got " + PIPELINES.size(),
        PIPELINES.size() >= 8);
    for (String pipeline : PIPELINES) {
      assertTrue(pipeline, pipeline.startsWith("source=bank | "));
      int commands = commandKeywords(pipeline).size();
      assertTrue("a pipeline has 2..3 commands: " + pipeline, commands == 2 || commands == 3);
    }
  }

  @Test
  public void includesThreeCommandPipelines() {
    assertTrue(
        "expected at least one three-command pipeline:\n" + PIPELINES,
        PIPELINES.stream().anyMatch(p -> commandKeywords(p).size() == 3));
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
  public void neverRepeatsACommandAdjacently() {
    for (String pipeline : PIPELINES) {
      List<String> keywords = commandKeywords(pipeline);
      for (int i = 1; i < keywords.size(); i++) {
        assertFalse(
            "adjacent duplicate command in: " + pipeline,
            keywords.get(i).equals(keywords.get(i - 1)));
      }
    }
  }

  /** The leading keyword of each piped command segment (excluding the leading {@code source=}). */
  private static List<String> commandKeywords(String pipeline) {
    String[] segments = pipeline.split("\\|");
    List<String> keywords = new ArrayList<>();
    for (int i = 1; i < segments.length; i++) {
      keywords.add(segments[i].trim().split("\\s+")[0]);
    }
    return keywords;
  }
}
