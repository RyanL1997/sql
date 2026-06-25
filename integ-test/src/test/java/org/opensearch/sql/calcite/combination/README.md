# PPL Combination + Pushdown Test Framework

Implements `docs/dev/ppl-combination-pushdown-test-framework.md` (see also the
`docs/dev/rfc-ppl-combination-pushdown-testing.md` RFC): systematically exercise *reasonable*
multi-command PPL pipelines and verify each command pushes down **as expected**, in a way that
auto-extends when commands change.

## Classes

| Class | Role |
|---|---|
| `PushdownShapeOracle` | Parses a physical explain plan into the set of pushed `PushDownType` tokens (and coordinator `Enumerable*` nodes) and verifies an expected token set **bidirectionally** — a missing token is a pushdown *loss*, an extra token an undeclared *gain*. Vocabulary derives from the real `PushDownType` enum. |
| `DifferentialComparator` | Compares results with pushdown ON vs OFF: absorbs tolerable divergence (float ULP, row ordering) via a schema-checked, order-insensitive, ULP-tolerant multiset match; surfaces genuine divergence (0-vs-null, `"null"`-vs-null, missing rows, schema drift). Also a `compareInOrder` variant for total-order-sort pipelines. |
| `CombinationModel` | The field-type eligibility model (incl. the `bank` index profile) that makes the oracle field-type-aware, so it does not expect a token for `sort <text-without-keyword>`. |
| `PipelineGenerator` | Generates *reasonable* 2–3 command pipelines via a field-availability-aware walk (never references a dropped field; no redundant adjacency). 100 over the `bank` profile. |
| `QueryResults` | Parses a PPL JSON response into the comparator's schema + rows. |
| `PushdownDifferentialTestCase` | IT base: load `bank`, run a query pushdown ON vs OFF, compare. |

## Tests

| Test | Lane | Covers |
|---|---|---|
| `CalcitePplCombinationShapeIT` | `:integTest` (live cluster) | the shape oracle on real `explainQueryYaml` output; the `sort`/field-type behavior |
| `CalcitePplDifferentialIT` | `:integTest` (live cluster) | curated divergence-free queries: pushdown on == off (incl. AVG ULP) |
| `CalcitePplGeneratedDifferentialIT` | `:integTest` (live cluster) | **all 100 generated pipelines** must be pushdown-invariant and not throw |
| `PushdownShapeOracleIT` | `:integTest` (no cluster) | oracle parsing + loss/gain detection; closed vocabulary over the golden corpus; command→token map over the big5/clickbench goldens |
| `DifferentialComparatorIT` | `:integTest` (no cluster) | the tolerable-vs-genuine divergence matrix + order-sensitive compare |
| `CombinationModelIT` | `:integTest` (no cluster) | field-type eligibility (only raw-text fields are unpushable) |
| `PipelineGeneratorIT` | `:integTest` (no cluster) | generator validity: 2–3 commands, field-availability, no adjacent duplicates |
| `CommandCoverageGateTest` | `:ppl:test` | the command set reflected from the generated parser `ruleNames` matches the declared manifest (the forcing function for new/renamed commands) |

## Run

```bash
./gradlew :integ-test:integTest --tests "org.opensearch.sql.calcite.combination.*"
./gradlew :ppl:test --tests "org.opensearch.sql.ppl.CommandCoverageGateTest"
```

## Status & remaining work

**Built and green:** both oracles on a live cluster (shape + differential), the differential
comparator (+ order-sensitive), the field-type model, the coverage gate, and the 2–3 command
generator (100 pipelines, all pushdown-invariant). The framework also surfaced a real bug, fixed on
`fix/sort-pushdown-text-keyword-guard`.

**Remaining toward the full design:** more index profiles for the generator; the ES|QL-style
error-classification oracle as a first-line check; the nightly lane + mutation (PIT) on the pushdown
rule classes for the adequacy numbers. See the design doc and the project tenets.
