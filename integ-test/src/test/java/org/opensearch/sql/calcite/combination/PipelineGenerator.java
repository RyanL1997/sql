/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.sql.calcite.combination;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.opensearch.sql.calcite.combination.CombinationModel.FieldType;

/**
 * Generates <b>reasonable</b> multi-command PPL pipelines — not a cartesian product. Each candidate
 * command is applied to the currently-available schema and only emitted if its referenced fields
 * still exist (and have a compatible type), so a pipeline never references a field that a prior
 * {@code stats}/{@code fields} dropped, and no two <i>adjacent</i> commands repeat. The command set
 * is restricted to ones whose <i>row set</i> is deterministic ({@code where}, {@code stats}, {@code
 * eval}, {@code fields}, {@code sort}), so the differential oracle can compare results
 * order-insensitively without false positives from unstable {@code head}/{@code dedup}.
 *
 * <p>This is the validity model of the design doc in miniature: a depth-bounded walk threading the
 * available-field set through each command's schema transform. {@link #generate(String, Map)}
 * produces pipelines of 2..{@value #DEFAULT_MAX_COMMANDS} commands.
 */
public final class PipelineGenerator {

  private PipelineGenerator() {}

  public static final int DEFAULT_MAX_COMMANDS = 3;

  /** A rendered segment and the schema it leaves available to the next command. */
  public record Segment(String text, Map<String, FieldType> available) {}

  /** A command that may render a segment given the currently-available fields, or not apply. */
  @FunctionalInterface
  private interface Command {
    Optional<Segment> apply(Map<String, FieldType> available);
  }

  private record NamedSegment(String command, Segment segment) {}

  // Grouping fields preferred low-cardinality first; raw text is never groupable/sortable natively.
  private static final FieldType[] GROUPABLE_PREFERENCE = {
    FieldType.BOOLEAN,
    FieldType.TEXT_WITH_KEYWORD,
    FieldType.KEYWORD,
    FieldType.DATE,
    FieldType.NUMERIC
  };

  private static final Map<String, Command> COMMANDS = new LinkedHashMap<>();

  static {
    // where <numeric> > 30 — preserves schema.
    COMMANDS.put(
        "where",
        available ->
            firstOfType(available, FieldType.NUMERIC)
                .map(n -> new Segment("where " + n + " > 30", available)));

    // stats count() as c by <groupable> — collapses schema to {group key, c}.
    COMMANDS.put(
        "stats",
        available ->
            firstGroupable(available)
                .map(
                    g -> {
                      Map<String, FieldType> next = new LinkedHashMap<>();
                      next.put(g, available.get(g));
                      next.put("c", FieldType.NUMERIC);
                      return new Segment("stats count() as c by " + g, next);
                    }));

    // eval x = <numeric> + 1 — adds a numeric column.
    COMMANDS.put(
        "eval",
        available ->
            firstOfType(available, FieldType.NUMERIC)
                .map(
                    n -> {
                      Map<String, FieldType> next = new LinkedHashMap<>(available);
                      next.put("x", FieldType.NUMERIC);
                      return new Segment("eval x = " + n + " + 1", next);
                    }));

    // fields <up to two available fields> — drops to the kept set.
    COMMANDS.put(
        "fields",
        available -> {
          List<String> keep = available.keySet().stream().sorted().limit(2).toList();
          if (keep.isEmpty()) {
            return Optional.empty();
          }
          Map<String, FieldType> next = new LinkedHashMap<>();
          keep.forEach(k -> next.put(k, available.get(k)));
          return Optional.of(new Segment("fields " + String.join(", ", keep), next));
        });

    // sort <natively-pushable field> — does not change the row set.
    COMMANDS.put(
        "sort",
        available -> firstGroupable(available).map(f -> new Segment("sort " + f, available)));
  }

  /**
   * Generate valid pipelines of 2..{@value #DEFAULT_MAX_COMMANDS} commands over an index profile.
   */
  public static List<String> generate(String index, Map<String, FieldType> schema) {
    return generate(index, schema, DEFAULT_MAX_COMMANDS);
  }

  /** Generate valid pipelines of 2..{@code maxCommands} commands over an index profile. */
  public static List<String> generate(
      String index, Map<String, FieldType> schema, int maxCommands) {
    List<String> pipelines = new ArrayList<>();
    extend(new ArrayDeque<>(), schema, maxCommands, index, pipelines);
    return pipelines.stream().distinct().toList();
  }

  private static void extend(
      Deque<NamedSegment> chain,
      Map<String, FieldType> available,
      int remaining,
      String index,
      List<String> out) {
    if (chain.size() >= 2) {
      String body = chain.stream().map(s -> s.segment().text()).collect(Collectors.joining(" | "));
      out.add("source=" + index + " | " + body);
    }
    if (remaining == 0) {
      return;
    }
    String last = chain.isEmpty() ? null : chain.peekLast().command();
    for (Map.Entry<String, Command> entry : COMMANDS.entrySet()) {
      if (entry.getKey().equals(last)) {
        continue; // no redundant adjacent A | A
      }
      Optional<Segment> segment = entry.getValue().apply(available);
      if (segment.isEmpty()) {
        continue;
      }
      chain.addLast(new NamedSegment(entry.getKey(), segment.get()));
      extend(chain, segment.get().available(), remaining - 1, index, out);
      chain.removeLast();
    }
  }

  private static Optional<String> firstOfType(Map<String, FieldType> schema, FieldType type) {
    return schema.entrySet().stream()
        .filter(e -> e.getValue() == type)
        .map(Map.Entry::getKey)
        .sorted()
        .findFirst();
  }

  private static Optional<String> firstGroupable(Map<String, FieldType> schema) {
    for (FieldType preferred : GROUPABLE_PREFERENCE) {
      Optional<String> field = firstOfType(schema, preferred);
      if (field.isPresent()) {
        return field;
      }
    }
    return Optional.empty();
  }
}
