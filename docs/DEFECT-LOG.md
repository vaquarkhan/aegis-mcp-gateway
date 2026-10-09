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
# Fixed issues and known limitations

## Scope

This file records defects found in Aegis 2.0 before release and how each was fixed. A defect is
behaviour or documentation that was wrong; a capability that is not built yet is a feature and is on
the [roadmap](ROADMAP.md). Each entry lists its severity, its status and, where one exists, the
test that covers the fix. Entries marked **reconstructed** were recovered from the code, the design
documents and the tests, so their dates are approximate; entries marked **recorded** were logged when
they were found.

## Severity

| Severity | Meaning |
| --- | --- |
| **High** | a control did not do what it claimed, or a dangerous call was reachable |
| **Medium** | a governed tool could not work at all, or a documented claim was false |
| **Low** | documentation, test harness, or limited behavioural impact with no control bypassed |

## Governance behaviour

### D1. SQL arguments on READ tools are now checked by a read-only guard

| | |
| --- | --- |
| **Severity** | High |
| **Status** | Recorded, fixed |
| **Found by** | Design review against the 0.1 design documents |
| **Evidence** | [DESIGN-REVIEW-AND-GAPS.md](DESIGN-REVIEW-AND-GAPS.md) §1 |

Several adapter configurations exposed a `run_sql_readonly` tool as class `READ`, taking a free-form
`sql` argument. A `READ` tool needs no write unlock, no approval and no dry-run receipt, so such a tool
could forward a statement such as `DELETE FROM orders` without any of those controls.

**Fix.** `SqlReadOnlyHook`, a governance hook bound to the SQL-bearing tools, rejects anything that is not
a single read statement. Tested by `SqlReadOnlyHookTest` and by
`AdapterConfigsTest.sqlToolsClassifiedReadCannotBeUsedToWrite`, which drives every SQL tool in every
configuration with a list of write statements and asserts that each is denied.

### D2. `resources/read`, `prompts/get` and `completion/complete` are now governed like tool calls

| | |
| --- | --- |
| **Severity** | High |
| **Status** | Reconstructed, fixed |
| **Found by** | Design review of the 2.0 interceptor chain against the 0.1 design documents |
| **Evidence** | `ContentGovernor`, `ContentGovernorTest` (11 tests), `ContentHooksTest` (8 tests) |

MCP has four governed operations, not one. An agent that cannot call a tool can often still read a
resource, and the 0.1 design did not define whether `resources/read` traversed the chain.

**Fix.** `ContentGovernor` runs the same numbered chain, with the same deny codes, for all four
operations, and rebuilds results from spec fields only so a backend cannot inject content into the
envelope. Prompts are digest-pinned like tools.

### D3. Extension hooks now cover all four operations and use only the fixed deny codes

| | |
| --- | --- |
| **Severity** | Medium |
| **Status** | Recorded, fixed |
| **Found by** | Design review of the extension SPI |
| **Evidence** | `GovernanceHook`, `HookBinding`, `HookRunner`, `RemoteHook`, [adr/ADR-001-A2A-AS-TOOL-SOURCE.md](adr/ADR-001-A2A-AS-TOOL-SOURCE.md) |

Hooks previously fired only on tool calls, so they could not express a policy about resources, and they
could return free-form codes, which made the deny taxonomy unreliable for dashboards and clients.

**Fix.** Hooks cover all four operations, with `ENFORCE` / `AUDIT` / `OFF` modes and conditions, and a
hook cannot introduce a new wire code. `RemoteHook` lets an out-of-process policy participate without
Java code.

## Shipped configurations

### D4. The Flink jar allow-list pattern now matches Flink's jar id format

| | |
| --- | --- |
| **Severity** | Medium |
| **Status** | Recorded, fixed |
| **Found by** | Real-engine run of the Flink tutorial against Apache Flink 1.20.1 |
| **Evidence** | [TUTORIAL-FLINK.md](TUTORIAL-FLINK.md) part 6 |

`adopters/configurations/flink/flink.yaml` restricted `flink_run_jar` with
`resources: ["flink:jar:approved-*"]`. Flink stores an uploaded jar as `<uuid>_<original file name>` and
that whole string is the jar id, for example:

```
5b1d5322-6074-4d01-9dfd-6912db31eee5_approved-StateMachineExample.jar
```

The pattern matched no real jar id, so every `flink_run_jar` call was `POLICY_DENIED` regardless of
which jar an operator allow-listed, and the tool could not be used.

**Fix.** The pattern is now `flink:jar:*_approved-*.jar`, and `ExampleConfigsTest` uses jar ids in
Flink's real format.

### D5. The `flink_rescale_job` path is corrected to `/rescaling`

| | |
| --- | --- |
| **Severity** | Medium |
| **Status** | Recorded, fixed |
| **Found by** | Real-engine run of the Flink tutorial against Apache Flink 1.20.1 |
| **Evidence** | [TUTORIAL-FLINK.md](TUTORIAL-FLINK.md) part 5.6 |

The path was `/jobs/{jobId}/rescale`. Flink's route is `/jobs/{jobId}/rescaling`. Against the real
cluster:

```
PATCH /jobs/c3303b4a.../rescale     -> HTTP 404  Not found
PATCH /jobs/c3303b4a.../rescaling   -> HTTP 503  Rescaling is temporarily disabled. See FLINK-12312.
```

The cluster reports the same through `GET /config`, which returns `"web-rescale": false`.

The route also affects the circuit breaker. Aegis treats 5xx as a backend failure, and backend failures
count against the circuit breaker for that source. With the default `failureThreshold` of 5, five calls
to a permanently unavailable tool open the breaker, and the other Flink tools on the same source then
return `BREAKER_OPEN`.

**Fix.** The path is corrected. A comment next to the tool tells operators to remove it unless their
cluster serves the route, and the tutorial demonstrates the 503 and explains the breaker interaction.

### D6. Flink savepoint status is now read from the operation, not the status field

| | |
| --- | --- |
| **Severity** | Medium |
| **Status** | Recorded, fixed: the tool returns the whole response, the configuration explains why, and the Burr example checks `operation` and the job state |
| **Found by** | Real-engine run of the Burr framework example, followed by a check of the job state |
| **Evidence** | [TUTORIAL-FLINK.md](TUTORIAL-FLINK.md) part 5.5, `adopters/frameworks/burr/aegis_burr_agent.py` |

This is backend behaviour rather than an Aegis bug. It is recorded because an agent that reads only the
status field draws the wrong conclusion.

`GET /jobs/{id}/savepoints/{triggerId}` returns `status` and `operation`. `status.id: COMPLETED` means
the asynchronous operation finished; success or failure is in `operation`. Both of these responses came
from the same cluster:

```json
{"status":{"id":"COMPLETED"},"operation":{"location":"file:/.../savepoint-599e7a-6be098d502ec"}}
{"status":{"id":"COMPLETED"},"operation":{"failure-cause":{"class":"java.util.concurrent.CompletionException", ...}}}
```

In the failing case the job was still `RUNNING` and no savepoint was taken.

**Configuration.** Narrowing the response with `response: {pointer: /status}` would hide the failure, so
the tool returns the whole response, with the reason in a comment in the configuration. The Burr example
has a `confirm` step that reads `operation` and re-checks the job state rather than relying on
`ALLOWED`.

In the recorded failure the savepoint directory value ended in a space, and Windows cannot create a
directory whose name ends in a space. The argument matched its schema: schema validation checks the
shape of a value, not whether the value is correct.

### D7. The Spark backend change from Livy and YARN is now documented and enforced

| | |
| --- | --- |
| **Severity** | Medium |
| **Status** | Recorded, fixed: recorded in the coverage document and enforced by `Coverage01VsTwoTest`; Livy and YARN profiles are a roadmap feature |
| **Found by** | 0.1-to-2.0 coverage audit |
| **Evidence** | [COVERAGE-0.1-VS-2.0.md](COVERAGE-0.1-VS-2.0.md), "Backend changed" |

0.1's `spark/submit_batch` is documented in its tool file as "Submit a Livy batch job" and
`spark/kill_application` as "Kill a Livy or YARN application". The 2.0 tools `spark_submit_batch` and
`spark_kill_submission` use the Spark standalone Master REST submission server. The names are similar,
and the change was not documented at the time. A tool count (Spark went from 5 tools to 9) does not show
a backend substitution.

**Fix.** The change is recorded in the coverage document and enforced by the `BACKEND_CHANGED` table in
`Coverage01VsTwoTest`, so a tool name can no longer hide a backend substitution. Apache Livy and YARN
ResourceManager profiles are planned on the
[roadmap](ROADMAP.md#later-sources-and-engine-operations).

### D8. HTTP configurations for engines without a REST control API were replaced or removed

| | |
| --- | --- |
| **Severity** | High |
| **Status** | Recorded, fixed: every configuration listed below was replaced by a real protocol source or removed. Real-engine runs for the remaining contract-tested profiles are on the [roadmap](ROADMAP.md#engine-coverage-verification) |
| **Found by** | Route audit of the adapter configurations against each engine's documentation and source, in two passes; the second pass, on 2026-09-28, found Hudi, Ozone and Kudu |
| **Evidence** | The table below |

A declarative HTTP configuration can name any method and path, and nothing in the build checks whether
the product serves that path. Some former HTTP configurations targeted routes the products do not serve:
in each case below, the engine has no REST control API for the declared operations.

The contract harness for these configurations was written from the same reading of each API as the
configuration, so the tests passed. Contract tests prove route rendering, not product compatibility.

In 0.1, the descriptions of 12 tools across 11 engines used the word "facade", meaning the operator was
expected to provide the HTTP service. 2.0 carried over the endpoint shapes without that caveat.

| Engine | What the configuration declared | What the product serves | Status |
| --- | --- | --- | --- |
| Hive | `POST /sql`, `DELETE /queries/{id}`, `GET /sessions` on port 10002 | Port 10002 is HiveServer2's **web UI**, serving HTML. Hive has no REST control API. | **Fixed**: `jdbc` source through the Hive JDBC driver |
| Calcite | `GET /`, `POST /explain`, `POST /sql` | Calcite is a **library**; there is no Calcite server. Its remote surface is Avatica, whose HTTP protocol is RPC framing, not REST paths. | **Fixed**: `jdbc` source through Avatica's driver |
| ZooKeeper | `GET`/`DELETE /znodes/v1/<path>` | Those paths belong to `zookeeper-contrib-rest`, a separate contrib artifact a normal ensemble does not run. The data tree is reached over the client protocol. | **Fixed**: `zookeeper` converter source; the real AdminServer `/commands/*` reads stay HTTP |
| Arrow | `GET /flights`, `POST /actions/{action}` on port 8815 | Arrow Flight is **gRPC** on 8815, not HTTP+JSON. | **Defect closed (removed)**: unsupported configuration and matching test routes removed. A typed Flight source is a **future feature**, not an open defect; Spark Connect does not cover Flight |
| Beam | `GET /v1/jobs` on port 8099 | Beam's JobService is **gRPC** on 8099. | **Defect closed (removed)**: unsupported configuration and matching test routes removed. A typed JobService source is a **future feature**; Spark Connect does not cover Beam |
| Hudi | `GET /v1/tables`, `POST /v1/tables/{table}/clustering`, `POST /v1/tables/{table}/compaction` | Hudi has **no table-service REST API**. Table services run inside Spark and Flink writers or through hudi-cli; the only HTTP server is the timeline server embedded in writers, which serves file listings ([Hudi stack](https://hudi.apache.org/docs/hudi_stack/)). | **Defect closed (removed)**: unsupported configuration and matching test routes removed; engine-procedure coverage belongs on Spark/Flink sources |
| Ozone | `GET /v1/volumes`, `GET /v1/volumes/{volume}/buckets`, `DELETE .../buckets/{bucket}` on the Ozone Manager (9874) | The Ozone Manager serves a web UI. Ozone's protocols are ofs, o3fs, S3, CLI, Recon API, native Java RPC, CSI and HttpFS ([interfaces](https://ozone.apache.org/docs/2.0.0/interface.html)). | **Defect closed (removed)**: unsupported configuration and matching test routes removed; an Ozone source is a future feature |
| Kudu | `GET /tables`, `GET` and `DELETE /tables/{table}` on 8050 | Released Kudu has no table REST API; `/tables` is a web UI page with no DELETE, and by default 8050 is the tablet server web UI. Kudu 1.19.0 announces `/api/v1/tables/<table_id>` on the master (8051), keyed by table ID, behind `--enable_rest_api` ([announcement](https://kudu.apache.org/2025/11/10/introducing-the-rest-api.html)). | **Defect closed (removed)**: unsupported configuration and matching test routes removed; a version-specific REST source is a future feature |

**Why unsupported configurations were removed.** A product-named YAML file is executable product
configuration. A warning in the file does not change which routes the product serves, and a hand-written
contract server can agree with the same incorrect reading of the API. The 18 affected 0.1 capabilities now
have explicit absent verdicts. A future implementation should be a typed source that uses the product's
own protocol or client library, so that the compiler or an upstream machine-readable registry checks the
surface.

**Related changes.**

- Routes were audited against the engine's own documentation or source, not against the contract
  harness. A manual audit is not conclusive: the second pass found Hudi, Ozone and Kudu, which the first
  pass missed. Only a run against the target release, or a check against the engine's own
  machine-readable registry as below, settles a route.
- `ZooKeeperAdminCommandsTest` resolves each `/commands/<name>` path in the ZooKeeper configuration
  through `org.apache.zookeeper.server.admin.Commands`, ZooKeeper's own registry, which the real
  AdminServer uses to dispatch. A command name that is not in the registry fails the build. Where an
  engine ships a machine-readable description of its own surface, Aegis uses it as the reference instead
  of a hand-written contract server.
- `aegis-source-jdbc` is tested against a real embedded Apache Derby database and
  `aegis-source-zookeeper` against a real embedded ZooKeeper server, so neither converter is tested
  against a test double of the software it drives.

## Evidence classes

Current verification state is maintained in [evidence/CLAIMS-MATRIX.md](evidence/CLAIMS-MATRIX.md). The
evidence classes establish different things:

| Evidence class | What it establishes | What it does not establish |
| --- | --- | --- |
| Current embedded-real converter tests | JDBC converter behavior against Apache Derby, and znode behavior against real ZooKeeper | HiveServer2, Avatica or an operator's ZooKeeper deployment compatibility |
| Historical real-engine transcript | A named target worked on the recorded tree and version | Compatibility after later source/configuration changes |
| Source-registry check | A tool name or route exists in the target's own registry or source | A running target accepts the request under deployment authentication and data conditions |
| Contract route harness | Aegis parses, renders and governs the configured request | The target product or release implements that route |

A release compatibility claim requires the target-specific evidence row in the claims matrix; a passing
contract harness alone is not sufficient. For Burr, route declarations are read from the project's source
rather than its prose documentation. That removes one source of misreading, but a running server is still
needed before its row becomes verified-current.

## Pre-release security and release review

The following entries came from a pre-release review of the production modules, the build configuration
and tests, and the documentation.

### D9. The read-only SQL guard no longer depends on dialect-specific comment and escape rules

| | |
| --- | --- |
| **Severity** | High |
| **Status** | Recorded, fixed |
| **Found by** | Pre-release review of `SqlReadOnlyHook` against PostgreSQL string and comment rules |
| **Evidence** | `SqlReadOnlyHookTest.dialectSpecificCommentOrEscapeCannotHideASecondStatement` |

`stripCommentsAndQuoted` treated `#` as a line comment and `\` as a quote escape. Both are MySQL
semantics. On a PostgreSQL-dialect source `#` is an operator and backslash does not escape under
`standard_conforming_strings`, so `select 1 # 1; drop table t` and `select 'a\' ; drop table t; select 'b' --'`
were classified as a single read-only statement while the backend saw two, the second a write. A `READ`
tool guarded only by this hook needs no approval, so the write would have executed.

**Fix.** The stripper now uses portable rules only: `--` and block comments (never `#`), and standard-SQL
doubled-quote escaping (never backslash). A statement that relies on either dialect feature is refused
rather than reinterpreted, so a second statement cannot be hidden behind a dialect difference. A
read-only database credential on the source remains the primary control, as the Javadoc states.

### D10. The JDBC URL sanitiser now redacts credentials in common URL shapes

| | |
| --- | --- |
| **Severity** | Medium |
| **Status** | Recorded, fixed |
| **Found by** | Pre-release review of `JdbcSettings.safeUrl()` and `jdbc_server_info` |
| **Evidence** | `JdbcToolSourceTest.theUrlSanitiserStripsCredentialsInEveryCommonJdbcShape` |

`safeUrl()` stripped only a `//user:pass@` authority. SQL Server and Hive put the secret in a `;password=`
property, and Oracle thin uses `scott/tiger@`, so `jdbc_server_info` and exception text could return the
password to the model. The sanitiser now also redacts credential-named `;`/`&` properties, parenthesised
TNS credentials, and the Oracle `user/pass@` form.

### D11. The audit journal now takes a cross-process lock

| | |
| --- | --- |
| **Severity** | Medium |
| **Status** | Recorded, fixed |
| **Found by** | Pre-release review of the `HashChainFileAuditSink` open path |
| **Evidence** | `HashChainFileAuditSinkTest` (restart path still verifies) |

Two gateways pointed at one journal file would interleave appends with independent `prev`/`seq`, breaking
the chain and its tamper evidence. The sink now holds an advisory lock on a sentinel byte beyond EOF: a
second process fails fast at startup, while readers (including the sink's own verifier) are never
blocked, which also keeps the behaviour correct on Windows, where file locks are mandatory.

### D12. Backend failure text is now redacted before it reaches the caller and the audit journal

| | |
| --- | --- |
| **Severity** | Medium |
| **Status** | Recorded, fixed |
| **Found by** | Pre-release review of the execution failure paths in `GovernanceEngine` and `ContentGovernor` |
| **Evidence** | `GovernanceEngine.fail`, `ContentGovernor.fail` now redact the message |

`OutputGuard` redacts successful output, but a backend error message was placed verbatim into the denial
reason, the client `_meta.reason` and the audit record. A backend that echoes a token or row data in its
error would have exposed it. Both failure paths now run the message through `OutputGuard.redactAll`,
which masks secret shapes while leaving Aegis's own wording intact.

### D13. Audit verification no longer holds the append lock during a full scan

| | |
| --- | --- |
| **Severity** | Medium |
| **Status** | Recorded, fixed |
| **Found by** | Pre-release review of `HashChainFileAuditSink.verify` and `AdminApi` |

`verify()` now scans the sink's immutable committed byte prefix without taking the append monitor. The
sink advances that prefix only after writing a complete newline-delimited record, so concurrent
verification neither observes a partial append nor delays governed calls behind a full-journal scan. The
dedicated verifier group and boolean-only HTTP response remain in place.

### D14. Catalog and prompt acceptance is now bound to the reviewed digest

| | |
| --- | --- |
| **Severity** | Medium |
| **Status** | Recorded, fixed |
| **Found by** | Pre-release review of `AdminApi` accept routes and `ToolCatalog.accept` |

Acceptance now requires one `Aegis-Definition-Digest` request header. `ToolCatalog` and
`ContentGovernor` compare that value with the current quarantined entry while holding their catalog
monitor, then pin that exact digest. A refresh between review and acceptance therefore leaves the entry
quarantined and requires a new review. The same guard covers tools and prompts.

### D15. An oversized last record no longer blocks restart when arguments are journaled

| | |
| --- | --- |
| **Severity** | Medium |
| **Status** | Recorded, fixed |
| **Found by** | Pre-release review of the `HashChainFileAuditSink` tail scan with `audit.includeArguments: true` |

Audit records are now bounded before write at the same 8 MiB limit used by the audit parser, and restart
recovery scans a complete bounded tail record rather than a 1 MiB prefix. An oversized record is refused
before it changes the file or chain head, so it cannot affect later restart recovery. A regression test
covers a valid record larger than the former tail limit and a refused oversized record.

### D16. ZooKeeper znodes are now created owner-only

| | |
| --- | --- |
| **Severity** | Medium |
| **Status** | Recorded, fixed |
| **Found by** | Pre-release review of `ZooKeeperToolSource.createNode` |

Znodes were previously created with a world-accessible ACL. The ZooKeeper source now requires a digest
credential named by `settings.auth.digestEnv` and creates persistent znodes with `CREATOR_ALL_ACL`. The
credential stays outside configuration. The real embedded ZooKeeper test authenticates the source,
creates a node, and verifies that its ACL grants full permissions to the digest creator rather than
`world:anyone`.

### D17. Caller-triggered application errors no longer open the shared circuit breaker

| | |
| --- | --- |
| **Severity** | Low |
| **Status** | Recorded, fixed |
| **Found by** | Pre-release review of `HttpToolExecutor` application-status handling and `GovernanceEngine` breaker counting |

`ToolExecutor.ExecutionException` now distinguishes a backend availability failure from a
request-specific application rejection. HTTP `response.status` mismatches still return a redacted backend
error for that call, but release the breaker permit instead of adding a source failure. Transport errors
and timeouts keep the conservative availability-failure behavior. A regression test repeats mismatches
through the threshold and then shows that an unrelated healthy call still reaches the source.

## Source review for the module reference

Writing the [module reference](guide/modules.html) required reading every configuration key and control
in the source. The following entries came from that review; each is fixed with a test.

### D18. Enumerated configuration values are now validated with their key path

| | |
| --- | --- |
| **Severity** | Medium |
| **Status** | Recorded, fixed |
| **Found by** | Source review of `GatewayFactory` |

`sources[].unlistedTools` treated every value other than `HIDE` as `EXPOSE`, so a typo such as
`HIDDEN` exposed unlisted tools. Rule `effect`, credential `type`, MCP `era`, `catalog.mode`, the
screening modes, `defaultClass` and `server.versions` failed with a bare `IllegalArgumentException`
without the key path, and `approval.requiredFor: [READ]` was rejected only after the state store and
journal had been opened. Every enumerated value now accepts only its documented names and stops startup
with the key path and the accepted values. `HttpSourcesAndWiringTest.enumeratedValuesAndPolicyEndpointsAreValidatedWithTheirKeyPath`.

### D19. The OPA endpoint now passes the same URL and egress checks as other off-host endpoints

| | |
| --- | --- |
| **Severity** | Medium |
| **Status** | Recorded, fixed |
| **Found by** | Source review of `GatewayFactory` policy wiring |

The remote hook URL and the OTLP endpoint were validated and checked against `egress` at startup; the
OPA URL, which carries governance data off the host on every decision, was passed to `URI.create`
unchecked. It now must be an absolute http(s) URL without user-info and must pass the egress allow list.
Same test as D18.

### D20. OAuth metadata keys are no longer ignored when `oauth.resource` is absent

| | |
| --- | --- |
| **Severity** | Low |
| **Status** | Recorded, fixed |
| **Found by** | Source review of `OwnServerSecuritySettings` |

`authorizationServers`, `scopesSupported`, `documentation` and `allowAudienceMismatch` were skipped when
`resource` was absent, an exception to the unknown-key rule; and `allowAudienceMismatch` was unread (so
refused) when the audience already matched. Both are corrected.
`OwnServerSecuritySettingsTest.oauthMetadataRequiresAudienceBindingUnlessExplicitlyEscaped`.

### D21. The own host now writes its 500 response before closing the exchange

| | |
| --- | --- |
| **Severity** | Low |
| **Status** | Recorded, fixed |
| **Found by** | Source review of `AegisServer` |

A single try-with-resources closed the HTTP exchange before its catch block wrote the 500, so an
unexpected failure ended in a dropped connection instead of a response. One helper now answers a generic
500 and then closes the exchange, for MCP, admin and metrics routes. `GuardedExchangeTest`.

### D22. `jdbc_execute_statement` now accepts exactly one statement

| | |
| --- | --- |
| **Severity** | Medium |
| **Status** | Recorded, fixed |
| **Found by** | Source review of `JdbcToolSource` |

The check refused a `;` only when the text did not end with one, so `DELETE ...; DROP ...;` passed and
ran under one approval, while a `;` inside a literal was refused. The tool now uses the shared statement
splitter (`SqlReadOnlyHook.singleStatementProblem`), which ignores `;` inside literals and comments.
The JDBC settings error for a non-JDBC URL also no longer echoes the URL.
`JdbcToolSourceTest.executeStatementRefusesMoreThanOneStatementAndChangesNothing`.

### D23. The state store and tracing thread now open only after configuration is validated

| | |
| --- | --- |
| **Severity** | Low |
| **Status** | Recorded, fixed |
| **Found by** | Source review of the `GatewayFactory` build order |

The state provider was opened before sources, policy and hooks were read, and the OTLP exporter thread
started before the document was validated, so a later configuration error could leave a Derby database
open or a thread running in a long-lived host such as Knox. State now opens after every other section
has been read, the exporter starts only after validation, and a journal that cannot be opened closes the
state store.

### D24. A second journal writer in the same process is now refused

| | |
| --- | --- |
| **Severity** | Medium |
| **Status** | Recorded, fixed |
| **Found by** | Source review of `HashChainFileAuditSink` |

A same-process overlap of the writer lock (for example two Knox topologies naming one journal) was
accepted without a lock, so both sinks appended with their own chain state. The tail was also read before
the lock was taken. A second writer is now refused like a second process, and the tail is read under the
lock. `HashChainFileAuditSinkTest.aSecondWriterInTheSameProcessIsRefusedAndTheChainStaysIntact`.

### D25. Binary tool output no longer passes through string redaction

| | |
| --- | --- |
| **Severity** | Low |
| **Status** | Recorded, fixed |
| **Found by** | Source review of `OutputGuard` against the S11 property |

Resources and prompts refused binary parts unless allowed, but tool results ran every string, including
Base64 image data, through the redaction patterns, which could corrupt it and left it unscreened. Tool
results with binary parts are now withheld unless `output.allowBinary: true`, and allowed binary parts are
passed unchanged. `OutputGuardTest`.

### D26. ZooKeeper connection failures are now reported as backend unavailability

| | |
| --- | --- |
| **Severity** | Low |
| **Status** | Recorded, fixed |
| **Found by** | Source review of `ZooKeeperToolSourceFactory` |

The connection is opened on the first call, and a failed connect surfaced as
`ZooKeeper call failed: ConfigException` with the connect string in the message. It is now
`ZooKeeper is unavailable: ...`, counted by the breaker, without the connect string. An unused
unauthenticated connect overload and unused test-only environment hooks in the JDBC, ZooKeeper and Spark
Connect factories were removed, and the JDBC connection override used by one harness was replaced by the
real `DriverManager` path. `ZooKeeperToolSourceTest.anEnsembleThatCannotBeReachedIsABackendAvailabilityFailure`.

## Real-engine runs, profiles and test harness

### D27. The Pulsar real-engine test now waits for `public/default` before creating topics

| | |
| --- | --- |
| **Severity** | Low (test harness only; no product code involved) |
| **Status** | Recorded, fixed |
| **Found by** | Real-engine rerun (`-Preal-engines`) on 2026-10-03 |

`PulsarFrontDoorsTest` treated Pulsar standalone as ready once `/admin/v2/clusters` listed `standalone`,
then created its topics. In that run the first `PUT /admin/v2/persistent/public/default/orders` answered
`404 Namespace not found`: standalone answers the cluster list before it has created the default
namespace. The test now also waits until `/admin/v2/namespaces/public` lists `public/default`. The rerun
passed both front doors.

### D28. The Airflow profile now states that it targets Airflow 2.x

| | |
| --- | --- |
| **Severity** | Medium (documentation and profile scope) |
| **Status** | Recorded, fixed: the scope is stated in the profile header and the adopter README; an Airflow 3.x profile is a roadmap feature |
| **Found by** | Review of the profile's routes against Airflow's upgrade guide |

`airflow.yaml` targets `/api/v1`. Airflow 3 replaced that API with `/api/v2` and the webserver with
`airflow api-server`, so the profile does not work against an Airflow 3 deployment. The profile header
and `examples/adopters/airflow/README.md` now state this. An Airflow 3.x profile for `/api/v2`,
validated against a running Airflow 3 deployment, is planned on the
[roadmap](ROADMAP.md#later-sources-and-engine-operations).

### D29. The Kafka Admin default-build test now uses a real KRaft broker

| | |
| --- | --- |
| **Severity** | Low |
| **Status** | Recorded, fixed |
| **Found by** | Test-double removal |

The test previously used Kafka's `MockAdminClient`, which creates a topic even when `validateOnly` is set,
so the test needed a recording proxy and a production-code injection seam
(`KafkaAdminToolSourceFactory.adminFactory`). The test now starts a real Kafka 3.9.1 KRaft broker in its
own JVM and checks the broker's metadata directly; the seam, the proxy and the test hook are removed. The
broker refuses an impossible replication factor during the dry run, which the mock could not show.

A topic can still be listed briefly after its deletion returns: the controller has accepted the deletion,
and the broker's metadata cache applies it asynchronously. The test waits up to 30 s for the broker's own
metadata after the create and after the delete. On Windows the memory-mapped index files of a deleted
topic can stay locked briefly after the broker process exits, so the broker's data directory is under
`target/` rather than a JUnit temporary directory.

### D30. `BoundedHttp` returned I/O errors instead of 3xx status for redirect responses

| | |
| --- | --- |
| **Severity** | Medium |
| **Status** | Recorded, fixed |
| **Found by** | `ExistingMcpServerProxyTest` and real-engine validation, 2026-10-05 |

When `HttpClient.Redirect.NEVER` is set, some servers close the body stream immediately after a `Location`
header. Reading the body then threw `IOException` and hid the 3xx status from `ExistingMcpServerProxy` and
downstream MCP clients. **Fix:** for status 300 to 399, close the stream without reading and return an empty
body with the status (`BoundedHttp.send`).

### D31. Knox front-door runs failed when typed source `plugin.classpath` was missing

| | |
| --- | --- |
| **Severity** | Low (harness and docs) |
| **Status** | Recorded, fixed |
| **Found by** | `ZooKeeperFrontDoorsTest` rerun, 2026-10-05 |

`run-example.ps1` / `run-example.sh` ran `mvn install` on the root reactor without `-Preal-engines`, so
`aegis-source-zookeeper` and `aegis-source-a2a` had jars but no `target/plugin.classpath`. Knox installs
need both. **Fix:** before the examples `-Preal-engines` install, build the adopter's Knox source module
with `mvn -Preal-engines -pl <source-module> -am package` when the scenario installs a typed plugin into
Knox `ext/`.

### D32. Oversized-body rejection assertion missed a JDK client abort message

| | |
| --- | --- |
| **Severity** | Low (test harness) |
| **Status** | Recorded, fixed |
| **Found by** | Focused S1-S31 security suite, 2026-10-05 |

`GatewayHostTest.assertLargeBodyRejected` already accepted 413 or JDK I/O aborts that mention
`content-length` or `body`. On JDK 21, closing the connection after an oversized POST can surface as
`HTTP/1.1 header parser received no bytes`, which the helper treated as unexpected. **Fix:** accept
`header parser` and `no bytes` messages as the same client-side abort class; server still rejects
before backend dispatch ([SECURITY-VALIDATION.md](evidence/SECURITY-VALIDATION.md)).

## Related reading

- [DESIGN-REVIEW-AND-GAPS.md](DESIGN-REVIEW-AND-GAPS.md): scope changes from 0.1 to 2.0
- [COVERAGE-0.1-VS-2.0.md](COVERAGE-0.1-VS-2.0.md): every 0.1 tool and what happened to it
- [VALIDATION.md](VALIDATION.md): what is proven, by which test, against what software
- [THREAT-MODEL.md](THREAT-MODEL.md): assets, STRIDE, and abuse scenarios
- [evidence/SECURITY-VALIDATION.md](evidence/SECURITY-VALIDATION.md): focused control-suite evidence
- [ROADMAP.md](ROADMAP.md): planned features and what is not planned
