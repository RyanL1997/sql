# RFC: Scalable PPL command-combination + pushdown verification testing

**Status:** Draft for discussion · **Author:** @RyanL1997 · **Date:** 2026-06-25

> Full design: [`docs/dev/ppl-combination-pushdown-test-framework.md`](./ppl-combination-pushdown-test-framework.md).
> Proof-of-concept branches (on this fork): `poc/ppl-combination-pushdown-tests` (framework),
> `fix/sort-pushdown-text-keyword-guard` (a real bug the framework surfaced).

## 1. Problem

PPL pushdown tests are overwhelmingly **single-command** in intent. Explain coverage is ~338
hand-written golden files, almost all one command. The bugs this misses are **adjacency bugs** — an
operator that pushes down fine *alone* breaks when a *neighbour* is present. The two motivating
fixes are exactly this class:

- **#5488** (`where … | dedup …`): a filter-merge defeated `PPLSimplifyDedupRule`; dedup silently
  fell back to an in-memory window — correct rows, **lost pushdown** (a perf cliff).
- **#5515** (`range AND IN/Sarg` on a timestamp): a Sarg fold re-typed the literal; the emitted DSL
  lost ISO-8601 normalization — **wrong rows / HTTP 500**.

These are two distinct failure modes (lost pushdown vs wrong DSL→wrong rows), and single-command
goldens structurally cannot catch either. There is no systematic combination coverage, no mechanism
to ensure a *new* command gets combination/pushdown coverage, and no record/regenerate mode — every
golden is hand-typed.

## 2. Proposal

A framework that exercises **reasonable** multi-command pipelines and verifies each command pushes
down **as expected**, structured so a new/changed command is caught automatically. Four pieces:

1. **Shape oracle** — parse the physical explain into the set of pushed `PushDownType` tokens and
   verify it **bidirectionally**: a missing token is a pushdown *loss* (#5488 class), an extra token
   an undeclared *gain*. Expectations are **computed** from a command→token map and the field types
   (field-type-aware), never recorded — so a behavior change always turns the suite red.
2. **Differential oracle** — run a pipeline with pushdown **on vs off** and assert identical results
   (schema-checked, order-insensitive multiset, per-cell ULP tolerance), with a documented exclusion
   list for legitimate divergences. This is the academic **NoREC** oracle, applied intra-engine.
3. **Reasonable-combination generator** — a field-availability-aware validity model that emits valid
   pipelines (never referencing a dropped field; no redundant adjacency) — *not* a cartesian product.
4. **Coverage gate** — reflect the active grammar's `OpenSearchPPLParser.ruleNames`; a new/renamed
   command fails the build until it is declared, forcing combination + pushdown coverage.

### Why this design (prior art)

- **Trino** `BaseConnectorTest` (`isFullyPushedDown()` / `isNotFullyPushedDown(NodeClass)` /
  `skipResultsCorrectnessCheckForPushdown()`) is our **shape oracle**, including the shape/result
  decoupling.
- **SQLancer NoREC** (optimizer on-vs-off result differential, 51 optimization bugs found) is our
  **differential oracle** — and being intra-engine it sidesteps the cross-dialect objection PQS
  raises against differential testing.
- **Elastic ES|QL** (our closest sibling) validates the corpus + random pipeline generator; we borrow
  its cheap **error-classification** oracle (a valid pipeline must not throw). ES|QL verifies pushdown
  shape only via separate optimizer-rule unit tests — our integrated token oracle on the live explain
  unifies all three.

## 3. What's already built (proof of concept)

A working POC on `poc/ppl-combination-pushdown-tests`, production-shaped and green:

- **Classes:** `PushdownShapeOracle`, `DifferentialComparator`, `CombinationModel` (field-type
  eligibility), `PipelineGenerator`, `QueryResults`, `PushdownDifferentialTestCase`.
- **Tests (all passing):** `CommandCoverageGateTest` (ppl); `PushdownShapeOracleIT` (parser validated
  on **367 real goldens** + the command→token map on **90 benchmark queries**); `DifferentialComparatorIT`;
  `CombinationModelIT`; `CalcitePplCombinationShapeIT` (shape oracle on a **live cluster**);
  `CalcitePplDifferentialIT` (differential on a live cluster, incl. AVG ULP); `PipelineGeneratorIT`;
  and **`CalcitePplGeneratedDifferentialIT`** — the generator's **100 pipelines** (20 two-command +
  80 three-command), each verified pushdown-invariant on a live cluster with **zero per-pipeline test
  code**. Adding a command template or an index field expands coverage automatically.

## 4. The framework already found (and we fixed) real bugs

Pointing the framework's lens at current `main` surfaced a family of latent pushdown bugs sharing one
root cause — the keyword-subfield guard the dedup path has was **missing** in the sort, sort-expr and
aggregate-terms paths. Fixed on `fix/sort-pushdown-text-keyword-guard`:

- `sort <text-expr-key>` sorted on the raw analyzed field → wrong order / HTTP error (the genuine
  bug); now sorts on `.keyword` or declines cleanly.
- `sort`/`stats by` on a `text`-without-`.keyword` field relied on a swallowed exception; now declines
  explicitly.

Verified with no regressions: `CalciteSortCommandIT` (30), `CalcitePPLAggregationIT` (100),
`CalciteExplainIT` (258, 0 failures, no golden moved). This is the ROI: the framework catches this
class automatically and continuously.

## 5. Project tenets (what every review asks)

1. Adequacy is **measured** (branch + interlock + field-type coverage; mutation kill-rate;
   historical-bug replay), not a query count.
2. Expectations are **declared, never recorded** — or detection is destroyed.
3. Detection is **bidirectional** (a pushdown *gain* is as loud as a loss).
4. You maintain **intent, not artifacts** — one declarative line, not hundreds of goldens.
5. Combinations must be **reasonable** (field-availability + position), never cartesian.
6. **Two oracles, two failure modes** (shape for loss/gain; differential for wrong rows).
7. **Robust to churn, sensitive to behavior** (token presence, not digests/ordinals).

## 6. Execution model

Tiered, not one CI job: cheap forcing-functions gate every PR (`ppl` coverage gate; cluster-free
oracle/map checks; a small live smoke subset); the full generated sweep + mutation/coverage run
nightly. The generator produces cases at runtime from the manifest, so adding coverage adds **no
files**.

## 7. Open questions / next steps

- Generator depth and breadth (more index profiles; order-sensitive comparison for total-order sorts).
- Adopt the ES|QL error-classification oracle as a first-line generator check (partially in the
  generated-differential IT already).
- Wire the nightly lane + mutation (PIT) on the pushdown rule classes for the adequacy numbers.
- Land the bug fix (`fix/sort-pushdown-text-keyword-guard`) as its own PR upstream.

**Feedback welcome** on: the two-oracle split, the coverage-gate-as-forcing-function approach, and how
aggressively to grow the generator vs curate a corpus.
