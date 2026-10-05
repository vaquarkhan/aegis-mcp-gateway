<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->
# Scope changes from 0.1 to 2.0

This document records how the scope of Aegis 2.0 differs from 0.1: what was removed deliberately and
why, what remains deferred, how 2.0 behaves differently, and the known limitations operators should
account for. The commitments were taken from the 0.1 design documents (in Git history) and compared with
the 2.0 code. Fixed issues are recorded in [DEFECT-LOG.md](DEFECT-LOG.md), and the complete per-tool
comparison of all 33 engines and 156 0.1 tools is in [COVERAGE-0.1-VS-2.0.md](COVERAGE-0.1-VS-2.0.md).

---

## 1. Read-only SQL tools

0.1 had a `ReadOnlyGuard` SPI and a `SqlReadonlyGuard` so an engine could assert that a statement was
read-only. The first 2.0 adapter configurations did not carry an equivalent: five tools were classified
`READ` while forwarding a free-text `sql` argument to an endpoint that executes DDL or DML.

| Config | Tool | Endpoint |
| --- | --- | --- |
| `calcite.yaml` | `calcite_run_sql_readonly` | `POST /sql` |
| `calcite.yaml` | `calcite_explain_sql` | `POST /explain` |
| `doris.yaml` | `doris_run_sql_readonly` | `POST /api/query/default_cluster/information_schema` |
| `druid.yaml` | `druid_run_sql_readonly` | `POST /druid/v2/sql` |
| `hive.yaml` | `hive_run_sql_readonly` | `POST /sql` |

`READ` tools are registered by default and need no write group, no approval and no dry-run receipt. A
model steered by prompt injection could therefore send `DROP TABLE orders` through a tool whose name says
`readonly`, and every control in the chain would allow it, because the chain governs the tool's declared
class, not the meaning of a string argument.

`SchemaValidator` cannot express the constraint either: it supports `type`, `properties`, `required`,
`additionalProperties`, `enum`, `const`, `minLength`/`maxLength`, `minimum`/`maximum`, `items`,
`minItems`/`maxItems`, and deliberately no `pattern`.

**Current behaviour.** A built-in `sql-readonly` governance hook inspects a named argument and denies
anything that is not a single read-only statement, fail-closed, and is wired into all five
configurations ([DEFECT-LOG.md](DEFECT-LOG.md) D1). See [FEATURES.md](FEATURES.md) F13c and
[CONFIGURATION.md](CONFIGURATION.md). The hook is a keyword and shape guard, not a SQL parser, so it
reduces this risk rather than eliminating it. An operator who needs certainty classifies the tool
`MUTATE` and takes the approval, or gives the source a read-only database credential.

**Later change.** The table above shows the state at the time of the comparison. The Hive and Calcite
HTTP endpoints it lists are routes those products do not serve, and both configurations now use the
`jdbc` source through the engine's own JDBC driver ([DEFECT-LOG.md](DEFECT-LOG.md) D8). Their
`jdbc_run_query` tool keeps the `sql-readonly` hook. Doris and Druid remain HTTP tools with the hook.

---

## 2. Reference configuration depth

The four deep 0.1 adapters carried more of each engine's surface than the first 2.0 example
configurations. Current state:

| Engine | 0.1 tools | 2.0 initial | 2.0 current | Not yet covered |
| --- | --- | --- | --- | --- |
| Flink | 19 | 4 | **17** | `upload_jar` (multipart), `run_sql_readonly` and `run_sql_ddl_dml` (SQL Gateway needs a session lifecycle) |
| Iceberg | 12 | 5 | **7** | `expire_snapshots`, `remove_orphan_files`, `rewrite_data_files` (engine procedures, not catalog REST); `commit_transaction` (unbounded change set); `dry_run_maintenance` superseded by built-in dry run |
| Spark | 5 | 3 | **9** | `run_sql_readonly` is served by the Spark Connect source instead, which is tested against a real Spark 4.0.1 server |
| Kafka | 8 | 4 HTTP + 4 Admin | **10 HTTP + 4 Admin** | `alter_config`, `reset_offsets`, `delete_records`, `inspect_dlq` need the Java Admin source extended; on the roadmap, not claimed |

This table covers four of the thirty-three engines. [COVERAGE-0.1-VS-2.0.md](COVERAGE-0.1-VS-2.0.md)
covers all of them and is enforced by `Coverage01VsTwoTest`. It also records a backend change that tool
counts do not show: 0.1's `spark/submit_batch` and `spark/kill_application` targeted Apache Livy and
YARN, while the 2.0 tools of similar name target the Spark standalone Master REST submission server
([DEFECT-LOG.md](DEFECT-LOG.md) D7).

The Flink tools added in 2.0 include the high-risk ones (`run_jar`, `stop_job`, `rescale_job`). 0.1
controlled jar execution with `MCP_FLINK_JAR_UPLOAD_ALLOW_DIRS`, noting that running an arbitrary jar on
a Flink cluster is remote code execution by design. In 2.0 the jar is a **typed resource**
(`flink:jar:<jarId>`, `spark:jar:<appResource>`), so an operator allow-lists the jars that may run by
policy rather than allow-listing a directory on disk, and an unlisted jar is `POLICY_DENIED` at step 3
before any approval is requested.

Tests call the endpoints rather than only loading the configuration:
`ExampleConfigsTest.flinkCoversTheDeeperJobAndJarSurface` (9 new reads, rescale through approval, stop
with a `TOOL` dry run that reads the job then stops it, and both the refused and the approved jar) and
`ExampleConfigsTest.sparkIcebergAndKafkaCoverTheirDeeperSurfaceToo`. What is not yet expressible is named
in the table above and in the configurations themselves.

---

## 3. Removed deliberately

Each of these was in 0.1 and is intentionally absent from 2.0.

| 0.1 feature | Reason |
| --- | --- |
| `SemanticCache` (exact-match TTL cache for READ tools) | A result cache keyed on arguments serves one caller's result to another, which bypasses exposure, scope and policy for the second caller. It also makes the audit record describe a backend call that never happened. A cache that is correct under governance has to be keyed by the full canonical action **and** the caller, at which point it rarely hits. |
| `Mutator` / `ArgumentSanitizeMutator` (trimmed string arguments before validation) | Rewriting arguments before the chain means the argument digest an approval is bound to, and the digest in the audit record, describe something the caller did not send. 2.0 validates and refuses instead of repairing. |
| `Interceptor` / `Mutator` / `Validator` / `Observer` plugin SPI with `priority()` | A pluggable ordered chain lets a deployment reorder security checks, and the 0.1 production chain already hard-coded steps 1 to 10 for deterministic security ordering. 2.0 fixes the order and gives extensions exactly one slot (`GovernanceHook`, step 8) plus a read-only observer slot (`DecisionListener`). |
| `Decision.severity` (`INFO`/`WARN`/`ERROR`) | The severity levels had no specified effect on a decision, so 2.0 does not carry them. |
| `TaxonomyRouter` / `RetrievalRouter`, intent-based `tools/list` pruning, per-engine `taxonomyClass` | The matching algorithm was not specified in 0.1. Choosing which tool to call is the client's job, and pruning a list is not a security control: hiding a tool does not stop a caller naming it. 2.0 filters `tools/list` by exposure only, which is enforced. |
| Per-adapter thread pools (core 4, max 32, queue 128) | 2.0 has no execution pool. A call runs on the request thread with a total deadline enforced by `BoundedHttp`, so there is no shared queue for one slow engine to fill. |
| `TokenBudget` (daily LLM token cap), `BUDGET_EXCEEDED` for token spend | 2.0 budgets **calls**, weighted by tool class (`rateLimits.budgetPerHour`, costs READ 1 / MUTATE 5 / DESTRUCTIVE 20), and keeps the `BUDGET_EXCEEDED` code for that. Model-token accounting needs the model's own usage numbers, which a tool gateway does not see. Named as a non-goal in [ARCHITECTURE.md](ARCHITECTURE.md) §13. |
| Docker image, Helm chart | Back in 2.0 as a `Dockerfile` and `charts/aegis` (a StatefulSet, so each replica has its own audit journal volume). The image is built in CI; no image is published yet. |
| Email pseudonymisation in output (`PERSON_N`) | Not carried forward. 2.0 default redaction covers secret shapes only. An operator can add an email pattern to `output.redact`, which replaces the match rather than mapping it to a stable pseudonym. Stable pseudonyms need per-response state that the audit record would then have to explain; this is not built. |

Audit chain verification is available both offline, through the `--verify-audit` CLI, and over HTTP at
`GET /aegis/v1/audit/verify`, which is restricted to an audit-verifier group and returns only a boolean
result.

---

## 4. Deferred

These were future work in 0.1 and remain future work. They are listed in [ROADMAP.md](ROADMAP.md) and
are not claimed by any document:

- CIMD (client identity metadata document) authentication. 2.0 does not implement it.
- SPIRE integration. 2.0 verifies file-based X.509-SVIDs over mutual TLS (`identity.type: spiffe`) but
  does not use the SPIRE Workload API or JWT-SVIDs.
- Cedar as a policy engine. 2.0 ships the built-in rule policy and OPA over HTTP.
- RFC 8693 token exchange inside Knox. On the own host, `credentials.type: token-exchange` exchanges the
  verified caller JWT for a per-caller backend token (verified against Keycloak 26.8.0,
  [evidence](evidence/TOKEN-EXCHANGE-KEYCLOAK.md)); inside Knox no verified inbound token reaches Aegis,
  so such a source refuses every call there.
- Shared catalog pins and circuit breakers across replicas. `aegis-state-postgres` shares approval and
  receipt nonces, approval requests and rate windows across gateway processes. It is integration-tested
  against Amazon RDS for PostgreSQL 18.3 ([evidence](evidence/POSTGRES-RDS-VALIDATION.md)) and with two
  gateway processes against PostgreSQL 17.6 ([evidence](evidence/POSTGRES-TWO-GATEWAYS.md)). Catalog
  pins and breakers are still per process. A signed `catalog.manifest` gives every replica the same pins.
- Signed or Merkle verifiable reconciliation proof. 2.0 ships HMAC dry-run receipts.
- MCP Tasks.
- Audit seal and rotation.

---

## 5. Comparison with 0.1

| Property | 0.1 | 2.0 |
| --- | --- | --- |
| Resources, prompts, completion | The design did not state whether `resources/read` traversed the chain (`ResourceDef.redact` was a boolean) | `ContentGovernor` runs the same numbered chain for `resources/read`, `prompts/get` and `completion/complete`, with the same deny codes, and rebuilds results from spec fields only. Prompts are pinned like tools |
| Caller credentials | The caller's outbound `Authorization` header travelled on the identity object; the 0.1 proposal records pass-through as 0.1's behaviour | `CallerIdentity` has no credential field. Backend credentials resolve per target, after every control has passed |
| Approval separation of duties | Compared `CallerIdentity.subject` only | Compares actor, effective subject and the whole actor chain after NFKC normalisation and case folding, refuses to let a delegated caller approve or accept a quarantined definition, shows the approver redacted arguments and the dry-run preview, and journals every decision before it takes effect (`SeparationOfDutiesTest`) |
| Token binding | `Approval.mint` signed tool, scope (default `*`), expiry and nonce, without caller or arguments | Approval token and dry-run receipt bound to the caller, gateway name, tool, class, source, typed resources, argument digest and the pinned definition digest, so neither survives a definition change or moves to another gateway |
| OAuth scopes | Not enforced | Optional per-route required scopes with a 403 `insufficient_scope` step-up challenge (`ScopeEnforcementTest`) |
| Egress | Allow list plus metadata and link-local deny | Every DNS answer checked, not only the first; IPv4-mapped, NAT64, 6to4 and Teredo IPv6 classified by the embedded IPv4; URLs found at any nesting depth in arguments; user-info in a URL refused |
| Chain order | Pluggable interceptors with `priority()` over a hard-coded chain | One fixed chain. Extensions get one slot, and a hook cannot introduce a new deny code |
| Catalog integrity | Tool digests, trust-on-first-use | Tool **and** prompt digests, with a quarantine state, an operator accept path, and a class that can only rise |
| Commit semantics | Approval nonce single-use | Approval and receipt nonces consumed atomically, then intent journaled **before** execution; a journal failure denies (`AUDIT_UNAVAILABLE`) |
| 2026-07-28 round trips | Not addressed | Multi round-trip requests governed as first-class: the client's answers are screened and the retry is governed again |
| Extension controls | Before/after hooks on tool calls | Hooks over all four governed operations, with `ENFORCE`/`AUDIT`/`OFF` modes, conditions, and an out-of-process `remote` type needing no Java |
| Adapter surface | 33 Maven modules of Java adapters that built URLs by string concatenation | **25 deployable adapter configurations and 116 tools**. HTTP adapters validate schema/path/resource rules; Hive and Calcite use the JDBC converter; ZooKeeper uses its client-protocol converter. The inventory is enforced by `AdapterConfigsTest` and `DocumentationClaimsTest`. |

---

## 6. Known limitations

- The `sql-readonly` hook is a keyword and shape guard, not a SQL parser. Classify a SQL tool `MUTATE`, or
  give its source a read-only database credential, where certainty is required (section 1).
- Contract tests prove route rendering, not product compatibility. The contract route harness shows that
  Aegis parses, renders and governs a configured request; it does not show that the target product or
  release implements the route. Compatibility requires the target-specific row in
  [evidence/CLAIMS-MATRIX.md](evidence/CLAIMS-MATRIX.md), as described in [VALIDATION.md](VALIDATION.md).
- Catalog pins and circuit breakers are per gateway process (section 4).

---

## 7. Flink reference configuration

Two Flink configuration issues found against a real Apache Flink 1.20.1 cluster, the jar allow-list
pattern and the rescale route, are recorded in [DEFECT-LOG.md](DEFECT-LOG.md) as D4 and D5; D5 includes
the operator guidance on the `503` rescale response and its effect on the circuit breaker.
