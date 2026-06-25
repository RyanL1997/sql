# PPL Combination + Pushdown Test Framework

Implements `docs/dev/ppl-combination-pushdown-test-framework.md`: systematically exercise *reasonable*
multi-command PPL pipelines and verify each command pushes down **as expected**, in a way that
auto-extends when commands change.

## Classes

| Class | Role |
|---|---|
| `PushdownShapeOracle` | Parses a physical explain plan into the set of pushed `PushDownType` tokens (and coordinator `Enumerable*` nodes) and verifies an expected token set **bidirectionally** — a missing token is a pushdown *loss*, an extra token an undeclared *gain*. Vocabulary derives from the real `PushDownType` enum. |
| `DifferentialComparator` | Compares results with pushdown ON vs OFF: absorbs tolerable divergence (float ULP, row ordering) via a schema-checked, order-insensitive, ULP-tolerant multiset match; surfaces genuine divergence (0-vs-null, `"null"`-vs-null, missing rows, schema drift). |
| `CombinationModel` | The field-type eligibility model (incl. the `bank` index profile) that makes the oracle field-type-aware, so it does not expect a token for `sort <text-without-keyword>`. |

## Tests

| Test | Lane | Covers |
|---|---|---|
| `CalcitePplCombinationShapeIT` | `:integTest` (live cluster) | the shape oracle on real `explainQueryYaml` output; the `sort`/field-type behavior |
| `PushdownShapeOracleIT` | `:integTest` (no cluster) | oracle parsing + loss/gain detection; closed vocabulary over the golden corpus; command→token map over the big5/clickbench goldens |
| `DifferentialComparatorIT` | `:integTest` (no cluster) | the tolerable-vs-genuine divergence matrix |
| `CombinationModelIT` | `:integTest` (no cluster) | field-type eligibility (only raw-text fields are unpushable) |
| `CommandCoverageGateTest` | `:ppl:test` | the command set reflected from the generated parser `ruleNames` matches the declared manifest (the forcing function for new/renamed commands) |

## Run

```bash
./gradlew :integ-test:integTest --tests "org.opensearch.sql.calcite.combination.*"
./gradlew :ppl:test --tests "org.opensearch.sql.ppl.CommandCoverageGateTest"
```

## Status & remaining work

Built and validated: the shape oracle (live + corpus), the differential comparator, the field-type
model, and the coverage gate. Remaining toward the full design: wire `DifferentialComparator` to two
live result sets in an IT (pushdown on/off), and the Tier-B pairwise pipeline generator. See the
design doc for the plan and the project tenets.
