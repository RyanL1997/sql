# Design: Scalable PPL Command-Combination + Pushdown Test Framework

**Status:** Draft for review · **Author:** Jialiang Liang · **Date:** 2026-06-24

> Closes the "single-command test" gap: systematically exercise *reasonable* multi-command
> PPL pipelines and verify each command pushes down **as expected**, in a way that
> **auto-extends** when a command is added or changed — without adding per-PR burden.

---

## 0. Project tenets (what every review must ask)

These are the durable principles. Every change to the framework — and every PR that touches
pushdown — is reviewed against them.

1. **Adequacy is measured, not assumed.** "Is it enough?" is never answered by a query count. It is
   answered by coverage of the *pushdown decision logic* — the ~18 named rule/`PushDownContext`/
   `PredicateAnalyzer` classes — via **branch coverage + interlock-state coverage + field-type-branch
   coverage**, and *proven* by **mutation kill-rate** and **historical-bug replay** (revert #5488/#5515,
   confirm the suite goes red).
2. **Expectations are declared, never recorded.** Expected pushdown is *computed from a hand-maintained
   declaration of intent* (`CommandSpec`/manifest). We never auto-record expected-from-actual — that
   would silently absorb behavior changes and destroy detection. (This is why there is no golden
   *recorder* for shape.)
3. **Detection is bidirectional.** The framework flags pushdown *gain* as loudly as pushdown *loss*.
   Any change to *what pushes* turns the suite red and forces a conscious one-line update. Pushdown
   behavior must never change silently — in either direction.
4. **You maintain intent, not artifacts.** Adding a command = one declarative entry; changing behavior
   = one line. Queries, expected tokens, correctness checks, and no-pushdown twins are generated or
   eliminated. The manifest diff *is* the documentation of a PR's pushdown impact.
5. **Combinations must be reasonable.** Generation is constrained by position + field-availability +
   idiomatic adjacency — never a cartesian product. An invalid pipeline is a generator bug, not a case.
6. **Two oracles, two failure modes.** Shape (computed tokens) catches pushdown loss/gain; differential
   (on-vs-off rows, ULP + multiset + exclusion list) catches wrong-DSL→wrong-rows. Neither alone
   suffices; correctness is self-checked, not golden.
7. **Robust to churn, sensitive to behavior.** Assertions key on token *prefixes* and `Enumerable*`
   *presence* — not digests or column ordinals. Refactors that don't change pushdown produce no red;
   behavior changes always do.

---

## 1. Problem statement

PPL tests today are overwhelmingly **single-command** in intent. Explain coverage is a set of
~338 hand-written golden files under `integ-test/.../expectedOutput/calcite/`, each pinning one
query's plan. Where multi-command queries exist, they are hand-picked, never enumerated.

The bugs this misses are **adjacency bugs** — an operator that pushes down correctly *alone*
breaks when a neighbour is present:

- **#5488** (`where … | dedup …`): the user filter merged with the bucket-non-null filter,
  `PPLSimplifyDedupRule` stopped firing, dedup fell back to an in-memory `ROW_NUMBER` window.
  Results stayed *correct*; pushdown was silently *lost* (a perf cliff).
- **#5515** (`… where ts > now()-5m | where state in (…)`): a SARG-eligible sibling folded a
  `Sarg` and re-typed the timestamp literal to `VARCHAR`; the emitted DSL lost ISO-8601
  normalization → HTTP 500 / wrong rows.

Both are invisible to single-command goldens, and the two failure *modes* are different:
#5488 = correct rows, lost pushdown; #5515 = pushdown happened, wrong DSL → wrong rows.
**A framework must catch both modes.**

### 1.1 Why golden files don't scale here

The combination space is quadratic+ and the corpus already strains the hand-written model
(338 + 203 no-pushdown + 64 v2 goldens, all hand-typed; there is **no record/regenerate mode**).
Expectations must be **derived or differential**, not hand-typed.

---

## 2. Goals / non-goals

**Goals**
- **G-A (reasonable combinations):** generate *valid, realistic* multi-command pipelines — not a
  cartesian product of clauses.
- **G-B (pushdown as expected):** assert which operators push to the shard vs run on the coordinator.
- **G-C (auto-catch):** a new/renamed/changed command fails the build until it has combination +
  pushdown coverage.

**Non-goals**
- Not a query *fuzzer* for parser/analyzer crashes (separate concern).
- Not v2-engine parity for new combination coverage (Calcite-only; v2 corpus mirrors stay as-is).
- Not the DataFusion/analytics-parquet route (separate backend, separate divergences — §6.3).

---

## 3. Settled decisions

| # | Decision | Choice | Rationale |
|---|---|---|---|
| D1 | Test-runner path | **Path A — RandomizedRunner-native** | `:integTest` is JUnit4/RandomizedRunner, *not* JUnit Platform; Jupiter `@ParameterizedTest` is silently skipped (§4.1). Use carrotsearch `@ParametersFactory`; register no-pushdown lane in `CalciteNoPushdownIT`. Zero build change. |
| D2 | Shape-oracle assertion | **Programmatic token-set** | Parse `PushDownContext` tokens + `Enumerable*` ancestors; assert the expected `PushDownType` set. Robust to ordinal/operator-name churn, fully generatable, no goldens. |
| D3 | Pairwise (Tier-B) scope | **All ~39 pushdown-untested commands** | Only 3 commands are truly uncovered, but 36 are "docs-only" (correctness only, never pushdown-shape tested). §6.4. |
| D4 | Coverage manifest | **Checked-in** `command-coverage.yaml` | Explicit, reviewable diff when a command is added; clearer failure message than auto-derivation. |
| D5 | Golden recorder | **Not needed for new coverage** | Token-set shape + differential correctness are golden-free. Existing big5/clickbench goldens untouched. Optional targeted DSL-subset check only for a watchlist (§6.2.3). |
| D6 | Differential breadth | **Run all generated combos** (size-capped) | Delta-gating (only where tokens differ) risks missing row bugs where tokens match. Cap corpus size instead. |

---

## 4. Hard constraints discovered (ground truth)

### 4.1 `:integTest` is JUnit4/RandomizedRunner — Jupiter is silently skipped

`integ-test/build.gradle:676` does **not** call `useJUnitPlatform()` (only `integJdbcTest:570`
does). Every IT inherits `@RunWith(RandomizedRunner.class)` from `LuceneTestCase`. RandomizedRunner
collects methods via `LuceneJUnit3MethodProvider` (public, non-static, **zero-arg**, `test*`) and
`JUnit4MethodProvider` (`org.junit.Test` only). **Jupiter `@ParameterizedTest` matches neither →
collected as nothing, run as nothing, no failure.**

> **Latent bug (fix separately):** `FieldsCommandIT`'s 3 `@ParameterizedTest` cases and **all 9 of
> `security/FGACIndexScanningIT`** currently execute **zero** assertions in CI. Tracked as a
> follow-up; see §9.

**Consequence for us:** parameterize via carrotsearch `@ParametersFactory` (a `static
Iterable<Object[]>` + matching constructor) or codegen zero-arg `test*` methods. A Jupiter class
also cannot be added to `CalciteNoPushdownIT`'s JUnit4 `@Suite`.

### 4.2 Pushdown is observable and has a closed vocabulary

`PushDownType` (`opensearch/.../storage/scan/context/PushDownType.java`) is a **closed 10-value enum**:
`FILTER, PROJECT, AGGREGATION, SORT, LIMIT, SCRIPT, SORT_AGG_METRICS, RARE_TOP, SORT_EXPR, HIGHLIGHT`.

In the **physical** explain, a pushed op renders inside the scan as
`CalciteEnumerableIndexScan(… PushDownContext=[[TYPE->digest, …], OpenSearchRequestBuilder(sourceBuilder={…})])`.
An op that ran on the coordinator appears as an `Enumerable*` node **above** a bare scan
(`EnumerableCalc/Sort/Limit/Aggregate/Window/*Join`). These two signals are the shape oracle.

### 4.3 The command registry is the grammar (no Java enum)

The **active** grammar is `ppl/src/main/antlr/OpenSearchPPLParser.g4` (the antlr plugin's default
source set). The `language-grammar/` copy CLAUDE.md points to is **stale** (missing ~25 commands).
The de-facto registry = `pplCommands` (first-in-pipeline) + `commands` (after-pipe) rules; the
generated `OpenSearchPPLParser.ruleNames[]` (filtered to `*Command`) is the machine-readable form,
cross-checkable against `AstBuilder`'s 47 `visit*Command` methods.

### 4.4 Pushdown-on vs -off differs only in bounded, characterizable ways

The logical plan is byte-identical on/off; only the physical plan (and rarely the numeric output)
differs. The output-affecting differences are enumerable (§6.3) and either ULP-tolerable or
excludable.

---

## 5. Architecture overview

```
                         ┌──────────────────────────────────────────────┐
   Pipeline sources      │                 ORACLES (per pipeline)        │
   ─────────────────     │                                              │
   Tier A: corpus    ──► │  Shape oracle (G-B): computed-expected        │
     big5/clickbench/    │    PushDownType token set  ==  actual         │
     docs (harvested)    │    PushDownContext tokens, AND no Enumerable*  │
                         │    above scan for pushed ops                   │
   Tier B: generator ──► │                                              │
     pairwise/adjacency  │  Differential oracle (correctness): rows@ON   │
     over validity model │    ==(schema-eq, multiset, per-cell ULP) rows@OFF │
                         └──────────────────────────────────────────────┘
                                          ▲
                                          │ feeds expected tokens
   ┌──────────────────────────────────────┴───────────────────────────┐
   │  Pipeline validity model (G-A):  CommandSpec[] + PipelineState     │
   │    position constraints · field-availability · pushdownExpectation │
   └───────────────────────────────────────────────────────────────────┘
                                          ▲
                                          │ every command must have a spec
   ┌──────────────────────────────────────┴───────────────────────────┐
   │  Coverage gate (G-C):  ruleNames[] ∩ visit*Command  ⊆  manifest    │
   │    new/renamed command with no spec/coverage  →  build fails       │
   └───────────────────────────────────────────────────────────────────┘
```

**Two oracles, by design** — they cover the two failure modes:
- **Shape oracle** catches pushdown **loss** (#5488: correct rows, op fell to coordinator).
- **Differential oracle** catches wrong-DSL → **wrong rows** (#5515).

Both are **golden-free**: shape expectations are *computed* from the validity model; correctness is
*self-checked* pushdown-on against pushdown-off.

---

## 6. Detailed design

### 6.1 Pipeline validity model (G-A — "reasonable, not cartesian")

Each command is declared **once** as a `CommandSpec`; the generator threads a `PipelineState`
left-to-right and only emits a step if it is valid in the current state.

```java
record CommandSpec(
    String name,
    Position position,                 // FIRST_ONLY | PIPE_ONLY | EITHER  (from pplCommands vs commands)
    SchemaTransform schema,            // see enum
    List<FieldReq> fieldRequirements,  // arg slot -> required TypeClass
    Set<String> idiomaticPredecessors, // soft weights for realism
    Set<String> idiomaticSuccessors,
    PushdownExpectation pushdown) {}   // PUSHED(PushDownType...) | BREAKS_PUSHDOWN | NEUTRAL

enum SchemaTransform { ADDS, DROPS, RENAMES, PRESERVES, COLLAPSES, REPLACES, MERGES, MULTIPLIES_ROWS, TRANSFORM_IN_PLACE }
record FieldReq(int argSlot, TypeClass required) {}  // STRING|NUMERIC|TIMESTAMP|MULTIVALUE|STRUCT|ANY

final class PipelineState {
    Set<FieldRef> availableFields;  // current visible schema (name + type)
    boolean schemaOpaque;           // after transpose/describe -> block field-ref commands
    String lastCommand;             // for redundant-adjacency dedup
    int rootCount;                  // enforce exactly one FIRST command
}
```

**Generation rules** (these are what make pipelines *reasonable*):
1. **Position gate** — position 0 ∈ {FIRST_ONLY, EITHER}; positions >0 ∈ {PIPE_ONLY, EITHER}.
2. **Field-availability gate** — every `FieldReq` must resolve against `availableFields` with a
   compatible `TypeClass`. *This single rule kills the dominant invalidity class* ("stats dropped
   the field", "fields hid it", "rename removed the old name").
3. **Apply transform** — mutate `availableFields` per `SchemaTransform` (COLLAPSES → `{group keys} ∪
   {agg aliases}`, DROPS → intersect/subtract, ADDS → union, RENAMES → swap, etc.).
4. **Redundant-adjacency dedup** — forbid `sort|sort`, `reverse|reverse`, `dedup|dedup` (same keys),
   `where|where`, `fields|fields`.
5. **Hard invariants** (real bugs, not preferences): `flatten f`/`expand f` must not follow a
   `fields` that hides `f`'s subfields; a timestamp `bin` must eventually feed `stats … by <binned>`.
6. **Pushdown annotation** — tag each step with its `PushdownExpectation`; the shape oracle then
   expects: ops before the first `BREAKS_PUSHDOWN` op are pushed; everything at/after a breaker is on
   the coordinator (an `Enumerable*` node).

The full per-command position + schema-effect + pushdown table is in **Appendix A**.

### 6.2 Shape oracle (G-B) — programmatic token set

`PushdownShapeOracle.assertShape(query, expectedTokens)`:
1. `physical = explainQueryYaml(query)` (add a thin `explainPhysical(query)` helper to
   `PPLIntegTestCase` returning the physical block).
2. Extract the scan's `PushDownContext=[[ … ]]`, split on `, `, take each token's prefix before
   `->` → the **actual** `PushDownType` set (+ note `LITERAL_AGG` inside `AGGREGATION` = dedup).
3. Collect `Enumerable*` node types appearing **above** the scan.
4. Assert: `actualTokens == expectedTokens` **and** no `Enumerable{Calc,Sort,Limit,Aggregate,Window}`
   ancestor exists for any op `expectedTokens` claims pushed.

Match on **token prefixes**, never full digests (digests embed non-deterministic
`rel#`/`RelSubset#` ids).

> **The `PushDownContext` token is the PRIMARY signal; the `Enumerable*` node is only secondary
> corroboration.** Runtime-validated on a live cluster: `sort address` (text-no-keyword) correctly
> does not push — but the coordinator sort node is **`CalciteEnumerableTopK`** (sort folded with the
> implicit query-size limit), *not* `EnumerableSort`. The OpenSearch-SQL custom physical nodes
> (`CalciteEnumerableTopK`/`…IndexScan`/`…NestedAggregate`/`…GraphLookup`) are `Calcite`-prefixed, so
> a naive `\bEnumerable…` scan misses them, and `CalciteEnumerableTopK` can be *either* pushed or
> coordinator depending on whether the scan absorbed it. Therefore the oracle's load-bearing check is
> **token presence/absence in `PushDownContext`** (step 2); the ancestor-node check (step 3) is a
> best-effort secondary signal over the plain `Enumerable{Calc,Sort,Limit,Aggregate,Window}` set
> only.

#### 6.2.1 Computing `expectedTokens` (the algorithm)

Simulate `PushDownContext` over the pipeline: an ordered token list `T` plus flags
`{limit, agg, topK, measureOrder, project}`. The **flag interlock is where adjacency bugs live**:

| Flag set by | Blocks later |
|---|---|
| `isLimitPushed` (LIMIT) | Filter · Aggregate · Dedup — *this is #5488's guard* |
| `isAggregatePushed` (AGGREGATION) | Filter · Aggregate · Dedup · SortExpr; **enables** SortAggMetrics · RareTop |
| `isTopKPushed` (SORT+LIMIT) | further Sort, conditionally SortExpr |
| `isMeasureOrderPushed` (SORT_AGG_METRICS) | Sort |

The full left-to-right algorithm is in **Appendix B**. Non-obvious mappings: `where`→`FILTER` or
`SCRIPT` (by script count); relevance fns → `FILTER` and **always pushed** (even pushdown-off);
`dedup`→`AGGREGATION`+`LITERAL_AGG`; `rare/top`→`RARE_TOP` on top of a pushed `AGGREGATION`;
`eventstats`/`streamstats`→ no token, expect `EnumerableWindow`.

> **`expectedTokens` MUST be field-type-aware — or the shape oracle false-positives.** `sort`/`dedup`/
> `stats … by` on a **`text` field with no `.keyword` subfield** (e.g. `bank.address`) legitimately
> **cannot** push (OpenSearch can't sort/terms-aggregate a raw analyzed field) — so the *correct*
> expectation is **no token**, with the op on the coordinator. The algorithm therefore consumes the
> index's field types and declines to expect a token for an ineligible `(command, field-type)` pair.
> This is exactly why the latent-bug hunt's `sort address` / `stats by address` findings are *not*
> shape-oracle regressions (the no-pushdown outcome is correct); the genuine bug in that family —
> `eval x=a+b | sort x, <text-field>` emitting a sort on the raw analyzed field → HTTP 400 / wrong
> order — is caught instead by the **error-classification / differential** oracle, not the shape oracle.
> Field-type-branch coverage (tenet 1) is what exercises both arms of each eligibility guard.

#### 6.2.2 Bootstrapping/validating the algorithm

Before trusting it on generated pipelines, **run the algorithm against the ~276 existing corpus
goldens** and assert it predicts their `PushDownContext` token sets. This reuses the existing goldens
as the algorithm's own test suite and catches algorithm bugs up front.

#### 6.2.3 Optional DSL-subset check (watchlist only)

For a small watchlist of high-risk ops (`FILTER`/`AGGREGATION` on date/timestamp fields — the
#5515 surface), additionally assert a **subset** of the `sourceBuilder` JSON (e.g. `range` bounds
carry `format:"date_time"`, ISO-8601 endpoints). This is targeted, not a full golden. In practice
the differential oracle already catches #5515 (it changed results), so this is belt-and-suspenders.

### 6.2 ← (oracle ordering note) The shape oracle runs in the **pushdown-ON** lane; in the
no-pushdown lane it asserts the *inverse* (tokens absent, `Enumerable*` present), reusing
`isPushdownDisabled()` to switch expectations.

### 6.3 Differential correctness oracle

`DifferentialOracle.assertSameRows(query)`:
1. Run `query` with `plugins.calcite.pushdown.enabled=true` → `rowsOn`, `schemaOn`.
2. Run with `=false` (via `withSettings(Key.CALCITE_PUSHDOWN_ENABLED, false, …)`) → `rowsOff`, `schemaOff`.
3. Assert `schemaOn == schemaOff` (name+type, ordered) — a **precondition** that surfaces coercion bugs.
4. Assert rows equal via a **new** comparator = `assertJsonRowsEqualIgnoreOrder`'s order-insensitive
   multiset **+** `closeTo`'s per-cell ULP tolerance (`max(1e-10, 4 ULP)`), since `avg` decomposes to
   `SUM0/COUNT` when pushdown is off.

**Exclusion ruleset** (skip these shapes — the divergence is legitimate, not a bug):

| # | Exclude | Reason |
|---|---|---|
| X1 | `sum(...)` over a possibly-empty/all-null bucket | `0` (push) vs `null` (no-push), issue #3408 |
| X2 | `eval <case-with-range> | stats … by <that field>` | range-bucket key `"null"` vs `null` |
| X3 | `head`/`top`/`rare`/`dedup` **not** preceded by a total-order `sort` | legitimately non-deterministic row selection |
| X4–X6 | DataFusion/analytics-parquet route as an oracle arm | algorithmic (not last-bit) divergence; percentile ~3%, query_string→0 rows |

Outside these, Calcite-on vs Calcite-off results are expected identical → the oracle is **sound**
(no false positives) while still catching #5488-style tie loss and #5515-style DSL corruption (both
manifest as a different/missing row set that no tolerance absorbs).

### 6.4 Tier A — corpus replay (realistic pipelines, free)

Three ready corpora of real multi-command pipelines:

| Corpus | Queries | Index | Commands exercised | Notes |
|---|---|---|---|---|
| big5 | 58 | `big5` | 11 | strip `/* DSL */` header via `sanitize()` |
| clickbench | 43 | `hits` | 7 | skip q29 (no Calcite), q30 (memory) |
| docs (doctest) | 608 | 24 indices | **48** | skip ` ```ppl ignore ` fences |

Generalize `PPLBig5IT` into a `CalcitePplCorpusReplayIT` that enumerates `.ppl`/harvested files via
`@ParametersFactory` and runs **both** oracles (shape via computed tokens; differential ON/OFF) on
each. A `CorpusHarvester` extracts the docs PPL fences (reuse `markdown_parser` logic) into
`integ-test/.../resources/docs/queries/*.ppl`.

### 6.5 Tier B — pairwise adjacency generator (the bug class)

Both PRs are "operator A adjacent to operator B." Generate `<root> | … | A | B | …` for ordered
pairs from the command catalogue, constrained by the validity model (§6.1). **Scope: all ~39
commands with zero pushdown-shape coverage** (3 truly uncovered: `lookup`, `ad`, `graphlookup`; +
36 docs-only). Prioritise the pushdown-capable × neighbour pairs first (that is the exact
flag-interlock surface).

Generalize the #5505 `sourceViews` seam into a **fragment registry**:

```java
record PplFragment(String text, EnumSet<PushDownType> expectedTokens,
                   Position pos /*PREFIX|SUFFIX|EITHER*/, Set<IndexProfile> requires) {}
```

**Index driver:** the **bank** index alone hits every pushdown-eligibility branch (text-only,
text+`.keyword`, keyword, date, numeric, boolean) — see Appendix C for the full index shortlist
(time_test_data for date buckets, nested_simple for partial-push, worker/work_information for joins,
occupation for rare/top).

`lookup`/`ad`/`graphlookup` need fixtures (dimension index, ML state, graph data). If fixtures are
out of scope initially, assert **parse + plan-shape only** for those three and defer their
differential-correctness.

### 6.6 Coverage gate (G-C — auto-catch)

Two-part gate split along module/resource availability:

- **Parser side** — `ppl/src/test/.../CommandCoverageGateTest.java` (plain unit test, parser on
  classpath): reflect `OpenSearchPPLParser.ruleNames[]` → `*Command` set, cross-check `AstBuilder`
  `visit*Command`, and assert every command has an entry in the checked-in
  `ppl/src/test/resources/command-coverage.yaml` manifest (with a small alias map for
  `rareTopCommand`→{top,rare}, label-based `searchCommand`/`fillnullCommand`). A command in
  `ruleNames[]` but absent from the manifest → **fail** with "new/renamed command X has no
  coverage; add it to command-coverage.yaml".
- **Corpus side** — `integ-test/.../coverage/CorpusCoverageGateTest.java`: assert every manifest
  command resolves to ≥1 combination case (corpus or generator) and ≥1 pushdown expectation
  (a `PushDownType` set or an explicit `NO_PUSHDOWN`).

The manifest is the single source the generator + oracles also consume. Guard against the stale
grammar by asserting `ruleNames`-derived count ≥ the `commands`-rule alt count.

### 6.7 Test-runner integration (Path A)

- New ITs `extends PPLIntegTestCase` (no `org.junit.jupiter.*` annotations).
- Parameterize generated pipelines via carrotsearch `@ParametersFactory` (surfaces each case as a
  distinct test) **or** codegen zero-arg `test*` methods.
- Pushdown-off lane: register the combination IT class in `CalciteNoPushdownIT`'s
  `@Suite.SuiteClasses`; branch oracle expectations on `isPushdownDisabled()`.
- The differential oracle flips pushdown **in-process** via `withSettings(...)`, so it doesn't
  depend on the suite rerun.
- **Goal-C guardrail:** add a meta-test (or CI test-count assertion) that fails if a generated-
  package class declares Jupiter annotations on a non-`useJUnitPlatform()` lane — the exact
  silent-skip mode `FGACIndexScanningIT` exhibits today.

---

## 7. Code placement

| Component | Module / path |
|---|---|
| Coverage gate (parser) | `ppl/src/test/java/.../CommandCoverageGateTest.java` + `ppl/src/test/resources/command-coverage.yaml` |
| Coverage gate (corpus) | `integ-test/src/test/java/.../calcite/coverage/CorpusCoverageGateTest.java` |
| Validity model + generator | `integ-test/.../calcite/combination/{CommandSpec,PipelineState,PplFragments,IndexProfile,PipelineGenerator}.java` |
| Shape oracle | `integ-test/.../calcite/combination/PushdownShapeOracle.java` |
| Differential oracle | `integ-test/.../calcite/combination/DifferentialOracle.java` |
| Combination IT entrypoint | `integ-test/.../calcite/combination/CalcitePplCombinationIT.java` (+ add to `CalciteNoPushdownIT`) |
| Corpus replay + harvester | `integ-test/.../calcite/corpus/{CalcitePplCorpusReplayIT,CorpusHarvester}.java` |

Module boundaries: `integ-test` may import `PushDownType` (depends on `opensearch`); the parser-side
gate stays in `ppl`. No cluster code in `ppl`.

---

## 8. Developer experience (how you leverage it)

### 8.1 What you maintain vs what's generated

You maintain a **declaration of intent** (the `CommandSpec`/manifest). The framework derives every
testable artifact from it. "Updating a test case" becomes "updating one line of declared behavior" —
and only when you *intentionally* change behavior.

| You do this | Manual work | Automatic |
|---|---|---|
| Add a new command | **1 `CommandSpec` entry** (forced by the coverage gate) | generator pairs it with every valid neighbour × field type × interlock state; shape oracle computes its tokens; differential checks correctness |
| Intentionally change pushdown (gain or lose) | **Flip 1 field** in that command's spec | all existing pipelines re-derive expected tokens; new combos appear; everything re-checks |
| Unintentionally change pushdown | **Nothing** — suite goes red, names the exact command×state | the safety net |
| Refactor internals without changing behavior | **Nothing** | oracle keys on token *prefixes* + `Enumerable*` *presence*, not digests/ordinals |

### 8.2 A day in the life

You pick up a `[Performance]` issue: `… | eval latency = end - start | where latency > 500` isn't
pushed (the #3387 class). You add project-script pushdown — 20 minutes of real work. Then:

1. **Local pre-flight (no cluster, seconds):** `:ppl:test` coverage gate + the algorithm
   bootstrap-validation — green. Then the combination smoke subset.
2. **Red where you didn't expect** — a `sort | dedup` pipeline you never touched:
   `PUSHDOWN LOST — expected [AGGREGATION] not pushed … fell back to ROW_NUMBER window — the #5488
   signature`. Your filter change loosened a guard `dedup` also relied on, three commands away. You'd
   have shipped it; instead it's named on your screen before you open a PR. You fix the guard.
3. **Red where you *meant* it** — `PUSHDOWN CHANGED (capability gain?) — undeclared [SCRIPT] now
   pushed; declare it`. You flip **one line** in `command-coverage.yaml`
   (`where.onComputedField: BREAKS → PUSHED(SCRIPT)`). Green — and the generator now emits new
   `eval | where` combinations across every field type, coverage you never hand-wrote.
4. **The differential oracle saves you from yourself** — an auto-generated combo shows
   `ROWS DIFFER (on vs off): 998 vs 1000` — your script's divide-by-zero diverges from the
   coordinator path. You fix the null handling. (This is the #5515 failure mode, caught with no
   golden.)
5. **The PR** carries a **one-line manifest diff** as its pushdown-impact documentation — no
   regenerated goldens, no `$8` ordinals. Per-PR gates green; nightly runs the full sweep.

Weeks later a teammate renames a physical operator in a refactor — **nothing turns red** (behavior
unchanged → tokens unchanged). A new contributor adds command `rex2` and forgets pushdown — the
**build stops them** at the coverage gate with a clear message; they add one entry and the generator
takes it from there.

**Net:** you wrote a production fix + one manifest line; you got regression detection three commands
away, auto-generated new-capability coverage, a golden-free correctness check, a one-line reviewable
PR, immunity to unrelated refactors, and a build that forces the next person to keep it honest.

### 8.3 Execution model (how/where it runs)

Not one CI job — a **tiered model**: cheap forcing-functions gate every PR; exhaustive generation
runs nightly. (Running the full pairwise sweep per-PR is too slow — each query is a cluster explain,
doubled by the differential.)

| Layer | Where | Cost | Blocking? | Catches |
|---|---|---|---|---|
| Coverage gate (`ruleNames[]` ⊆ manifest) | `:ppl:test` (no cluster) | ms | **Yes, per-PR** | new/renamed command without a spec |
| Algorithm bootstrap-validation (vs existing goldens) | unit-level (no cluster) | sec | **Yes, per-PR** | a bug in the expected-token logic |
| Combination **smoke subset** (pushdown-capable pairs on `bank`) | existing `:integTest` + `CalciteNoPushdownIT` | min | **Yes, per-PR** | the common adjacency regressions |
| **Full** Tier-A + Tier-B sweep + differential | new nightly scheduled workflow (+ on-demand label) | 10s min | No (files an issue) | the long tail of combinations |
| Mutation (PIT) + JaCoCo on rule classes | weekly / pre-release | min–hr | No | the adequacy numbers (tenet 1) |

Two requirements make it CI-safe: **determinism** (pairwise is enumerative, variation keyed on
index/field, never RNG → reproducible failures) and **cost control** (per-PR runs a fixed smoke
subset; the unbounded generation lives in the shardable nightly). The generator produces cases at
runtime from the manifest, so adding coverage **never adds files** — zero per-case CI maintenance.

---

## 9. Prior art & precedent (for the RFC)

Our two-oracle design is well-precedented — but **no single sibling does all of it**, which is the
gap this framework fills for a piped language.

| System | Combination generation | Pushdown assertion | Borrow / validates |
|---|---|---|---|
| **Trino** `BaseConnectorTest` | hand + benchmark (TPC-H) | **plan-shape**: `assertThat(query).isFullyPushedDown()` / `isNotFullyPushedDown(NodeClass)` (asserts a residual node remains) / `skipResultsCorrectnessCheckForPushdown()` | **validates our shape oracle** (token presence + `Enumerable*` residual = their `isNotFullyPushedDown(NodeClass)`); **validates decoupling** shape vs result (their `skip…` = our `enabledOnlyWhenPushdownIsEnabled`) |
| **ES\|QL** (closest sibling) | **random depth-bounded pipeline generator** + hand-curated **csv-spec** corpus | csv-spec result tests + *separate* optimizer-rule unit tests; the generator's oracle is **error-classification** (expected error = pass, unexpected = fail, max-depth = pass) | **ADOPT the error-classification oracle** (cheap, no ground truth); csv-spec = our Tier-A corpus; **docs-from-tests** harness |
| **SQLancer NoREC** | property-based random | **result differential**: optimized vs non-optimizable rewrite, compare result sets (not plans) | **validates our differential oracle** — NoREC is *intra-engine* optimizer on/off; found 51 optimization bugs |
| **SQLancer PQS** | property-based random | synthesized-expected (pivot-row containment); **rejects *cross-engine* differential** (dialect mismatch) | optional future ground-truth oracle; its objection is *cross-engine* — does **not** apply to our intra-engine on/off |
| **Calcite / Spark** | hand | golden plan-string (`DiffRepository`) / explain assertions | our existing golden corpus tier |

**What it validates (primary-sourced):**
- Shape oracle = Trino's `isFullyPushedDown` / `isNotFullyPushedDown(NodeClass)` pattern.
- Differential oracle = NoREC — and because it's *intra-engine* (only the optimizer is toggled, same
  dialect), it is immune to the cross-engine objection PQS raises against differential testing.
- The shape-decoupled-from-result split is literally Trino's `skipResultsCorrectnessCheckForPushdown()`.

**What we add beyond any single sibling:** ES|QL (the closest sibling) has the corpus + random
generator but verifies pushdown *shape* only via separate optimizer-rule unit tests — not an
integrated oracle on the real physical plan. Our **computed-expected token oracle on the live
explain** unifies Trino's shape assertion + NoREC's differential + ES|QL's generator into one
framework keyed on the PPL command grammar.

**Changes to adopt from the research:**
1. **Add the ES|QL error-classification oracle** as a cheap first-line generator oracle: a pipeline the
   validity model deems *valid* that throws an unexpected error / HTTP 500 / NPE = **fail**; an
   *expected* validation error = pass. No ground truth, no second run — catches crashes on
   weird-but-valid combos for free, *before* the shape/differential oracles run.
2. **Frame the differential as intra-engine (NoREC)** in the RFC to preempt the "differential testing
   is unsound for DBMSs" objection (which targets cross-dialect comparison, not optimizer-toggle).
3. *(Future/optional)* a **PQS-style synthesized-expected** oracle for ground-truth correctness
   without a second execution.

Citations: Trino `BaseConnectorTest` (trinodb/trino, `testing/trino-testing`); ES|QL random generator
(elastic/elasticsearch#98768) + csv-spec (`x-pack/plugin/esql/qa/testFixtures`); NoREC (arXiv
2007.08292); PQS (OSDI '20, arXiv 2001.04174).

> Research caveat: the deep-research run's automated verification was rate-limited on the Trino and
> NoREC claims (0-0 = under-verified, not refuted); both were subsequently confirmed directly from the
> primary sources cited above.

---

## 10. Risks & open questions

- **Expected-token algorithm fidelity.** Mitigation: bootstrap-validate against existing goldens
  (§6.2.2); the differential oracle is an independent backstop.
- **Generator combinatorial blow-up.** Cap with: pairwise (not n-way) by default, validity pruning,
  and a per-run size budget. Log any truncation (no silent caps).
- **Fixtures for `lookup`/`ad`/`graphlookup`** — provision dimension/ML/graph data, or assert
  plan-shape only initially. *Open: which?*
- **STDDEV/VAR decomposition** may exceed ULP like AVG — needs a targeted probe; if so, add to
  ULP-tolerated or exclusion list. *Open.*
- **Manifest exemptions** — `describe`/`show`/`kmeans`/`ad`/`ml` may be terminal/standalone and
  exempt from the *combination* requirement (still need a pushdown entry). *Open: confirm the list.*
- **#3408 (empty-sum 0-vs-null)** may get fixed upstream; key exclusion X1 on a flag/version, not
  hardcoded.

---

## 11. Rollout plan (phased)

1. **P0 — Foundations & validate.** Add `explainPhysical()` helper; write `PushdownShapeOracle` +
   the expected-token algorithm; **bootstrap-validate against the 276 existing goldens**. Write the
   ULP+multiset differential comparator. *(No new coverage yet — proves the oracles.)*
2. **P1 — Corpus tier.** `CorpusHarvester` + `CalcitePplCorpusReplayIT` over big5/clickbench/docs,
   running both oracles. Immediate broad, realistic coverage.
3. **P2 — Validity model + coverage gate.** `CommandSpec` table for all 50 commands;
   `CommandCoverageGateTest` (parser side) wired into the `ppl` unit build (the forcing function).
4. **P3 — Pairwise generator (Tier B).** Fragment registry + `PipelineGenerator`; cover the ~39
   pushdown-untested commands, pushdown-capable pairs first.
5. **P4 — Hardening.** Fixtures for lookup/ad/graphlookup; STDDEV/VAR probe; CI test-count guardrail.
6. **Separate quick win:** fix the silently-skipped Jupiter ITs (`FieldsCommandIT`,
   `FGACIndexScanningIT`) — convert to RandomizedRunner-native or a JUnit-Platform task.

---

## Appendix A — Command position + schema-effect + pushdown (abridged)

Position from grammar `pplCommands` (FIRST) vs `commands` (pipe). `graphLookup`/`union` are dual-listed.

| command | pos | schema effect | pushdown |
|---|---|---|---|
| search/source | FIRST | REPLACES | FILTER (relevance/range always push) |
| where | pipe | PRESERVES | FILTER or SCRIPT |
| fields(+/-) / table | pipe | DROPS | PROJECT (bare fields only) |
| eval | pipe | ADDS | NEUTRAL (computed part on coordinator) |
| rename | pipe | RENAMES | NEUTRAL |
| stats / chart / timechart | pipe | COLLAPSES | AGGREGATION (+PROJECT) |
| eventstats / streamstats | pipe | ADDS | BREAKS (EnumerableWindow) |
| dedup (keepempty=false) | pipe | PRESERVES | AGGREGATION+LITERAL_AGG (MAP/ARRAY/nested → breaks) |
| sort (field) | pipe | PRESERVES | SORT (text needs `.keyword`) |
| sort (expr) | pipe | PRESERVES | SORT_EXPR (CHAR/APPROX/INT) |
| head | pipe | PRESERVES | LIMIT (sets isLimitPushed) |
| rare / top | pipe | COLLAPSES | RARE_TOP (requires prior AGGREGATION) |
| bin | pipe | TRANSFORM_IN_PLACE | NEUTRAL (date width_bucket can ride AGG) |
| highlight | pipe | ADDS col | HIGHLIGHT (always push) |
| parse/grok/rex/spath | pipe | ADDS | BREAKS |
| expand/mvexpand/flatten | pipe | MULTIPLIES/ADDS | BREAKS |
| join/append/union/multisearch | EITHER/pipe | MERGES | BREAKS (per-side scan pushes independently) |
| lookup / graphLookup | pipe/EITHER | MERGES/ADDS | own physical node |
| transpose | pipe | REPLACES (opaque) | BREAKS |
| reverse / fillnull / replace / convert / regex / trendline / nomv / mvcombine / addtotals / addcoltotals / appendcol / appendpipe / fieldformat / patterns / kmeans / ad / ml | pipe | various | mostly BREAKS/NEUTRAL |

## Appendix B — Expected-token algorithm (pseudocode)

```
T=[]; flags={limit,agg,topK,measureOrder,project}=false
for cmd in pipeline:
  fields(bare)       : if real & !identity -> T+=PROJECT; project=true
  eval/rename/parse/rex/spath/grok/fillnull/... : (no token; expect EnumerableCalc for computed)
  where(native)      : if !limit && !agg && scriptCount==0 -> T+=FILTER
                       elif !limit && !agg && scriptCount>0 -> T+=SCRIPT
                       else (no token; expect EnumerableCalc)
  where(relevance)   : T+=FILTER                       # always, even no-pushdown
  highlight          : T+=HIGHLIGHT                     # always
  head N [from M]    : if startFrom+M < maxResultWindow:
                          if prior SORT* -> topK=true   # CalciteEnumerableTopK, no separate EnumerableLimit
                          T+=LIMIT; limit=true
                       else -> PushDownUnSupportedException (no push)
  sort field         : if !topK && !measureOrder && fieldsOnly -> T+=SORT
  sort expr          : if !agg && type in {CHAR,APPROX,INT} && !allSimple -> T+=SORT_EXPR
  sort <measure>     : if agg && single-collation && fieldsOnly -> T+=SORT_AGG_METRICS; measureOrder=true
  stats/chart/timechart : if !limit && !agg && distinct-proj-no-over -> T+=[PROJECT?,AGGREGATION]; agg=true
  dedup f (kE=false) : if !limit && !agg && f∉{MAP,ARRAY,nested}:
                          if prior-where -> WITH_FILTER (T+=FILTER first)
                          T+=AGGREGATION(LITERAL_AGG); agg=true
                       else (no token; expect EnumerableWindow)
  rare/top by g      : if agg -> T+=RARE_TOP   else (no token; EnumerableWindow)
  eventstats/streamstats : (no token; EnumerableWindow)
  join/append/union/multisearch : (no scan token; Enumerable*Join/Union; sub-pipelines computed separately)
# final implicit:
T += LIMIT (QUERY_SIZE_LIMIT, default 10000)
return T
```

## Appendix C — Index shortlist (pushdown-eligibility branches)

| index | branches exercised |
|---|---|
| **bank** (primary) | numeric range/Sarg; **date** Sarg (#5515); keyword term; text-only → SCRIPT/no-push; text+`.keyword`; boolean. Has `bank_extended` for the #5505 view seam. |
| account (+extended) | 1000 docs → LIMIT/HEAD + result-window edges; default index of existing goldens |
| time_test_data (+2, +null) | date-bucket + Sarg-on-timestamp; bin/span/timechart; null-bucket |
| events (+null) | timechart/per_second; double metrics; SORT_AGG_METRICS |
| nested_simple | partial-pushdown (`isnull(nested)` must NOT push) |
| worker + work_information | join / SMJ sort-pushdown |
| occupation (+top_rare) | RARE_TOP |
| strings | SCRIPT-pushdown on text |

---

*Source investigation (file:line evidence) archived at `/tmp/ta_deepdive/*.md`.*
