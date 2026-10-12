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
# Changelog

## 2.0.0 (unreleased)

- A self-contained Hello World starts the example inventory service and leaves Aegis listening, and the
  generated tutorials put the source plugin jar on the classpath and read Knox's exported certificate
  from the Knox installation.
- Cursor and Kiro setup now says which JSON to copy into the open project, and that the agent is on
  Aegis only after the gateway is still listening and the editor lists that server's tools.
- Adopter READMEs now say which file to copy, where it goes, and which command leaves the gateway
  listening. The catalog YAML hostnames are not a local engine, and `run-example -Keep` remains the
  test that stops its processes and overwrites retained evidence.
- Atlas, Cassandra, Impala, and Paimon are the remaining product-stack runs, listed under
  [Future roadmap: remaining target verification](docs/ROADMAP.md#future-roadmap-remaining-target-verification).
- Ranger Admin 2.9.0 now has a retained two-door run. `RangerFrontDoorsTest` starts the official
  Admin and database images, governs the public v2 service and policy reads through the own host and
  Knox, and deletes a scratch policy only after approval.
- HiveServer2 4.0.1 now has a retained two-door run. `HiveFrontDoorsTest` starts the official
  Hive image, governs JDBC reads through the own host and Knox, and refuses a write smuggled into
  the read-only SQL tool.
- Getting-started and tutorial fixes for a first run on Windows: the Hello World page starts the
  inventory service and Aegis, the Kafka start script survives a JVM stderr banner, connector
  registration has a PowerShell command, and the Spark and Iceberg Knox steps trust the exported
  certificate without leaving the tutorial directory. Generated project tutorials define `$REPO` and
  state that `-Keep` overwrites retained evidence and does not leave a gateway listening. The Spark
  and Iceberg SDK client imports `httpx2`, which is what `mcp` 2.2.0 installs.
- The Superset front-door test seeds two charts from a local SQLite table when the public
  example-data download is unavailable, so the own-host run does not depend on that host.
- The validation report now points later runs, typed sources, and distribution follow-up at the roadmap.
- GitHub Pages renders Mermaid diagrams in the architecture and tutorial pages.
- The architecture sequence diagram no longer uses a semicolon inside a message, which Mermaid 11.12.0 treats as the end of the statement.
- Repository links on the published site use the branch GitHub Pages is building, so replacing that branch does not leave the site pointed at a removed name.
- The OTLP drain test's local collector reads the POST off the accept thread and answers with a
  fixed-length body, so a Windows JDK does not time out the export and report zero spans.
- Host trust anchor for the server catalog: `--require-server-catalog <review.pub>`,
  `AEGIS_REQUIRE_SERVER_CATALOG_KEY` or `-Daegis.requireServerCatalogKey` (an absolute path, read from
  the host environment) makes `serverCatalog` mandatory and fixes its review key, so editing the
  configuration cannot drop or swap the catalog.
- Rollback protection for signed files: the server catalog, `catalog.manifest` and break-glass orders
  accept an optional signed `"serial"` (`--serial <n>` on `--server-catalog-create`,
  `--manifest-create` and `--break-glass-create`). The highest accepted serial per kind is kept in the
  state store (`SignedSerial`) through the optional `HighWaterStateStore` capability; a lower or
  missing serial is then refused at startup. The bundled memory, Derby and PostgreSQL providers make
  the update atomic; an external provider without that capability refuses numbered files rather than
  weakening rollback protection. Durable with
  `derby-local` and `postgres` state. One atomic high-water update means a candidate that arrives
  after a higher serial is refused across PostgreSQL replicas.
- Offline policy test: `--policy-test <aegis.yaml> <cases.json>` evaluates callers, tools and arguments
  through exposure, schema, resource, scope and policy steps without a backend, audit or approval
  (`GovernanceEngine.explain`), and exits 1 when a case does not match its `expect`.
- Egress for plugin sources: `ToolSource.configuredEndpoints()` (a default method, so existing plugins
  compile unchanged) lets a plugin report its configured endpoints. The Kafka Admin source reports each
  bootstrap server as `tcp://host:port` and the Spark Connect source its server; their configured
  hosts join the allow list like an HTTP `baseUrl`, and every call checks them and every DNS answer.
  Kafka brokers advertised after bootstrap are constrained by listener configuration and network policy.
  A malformed
  `bootstrapServers` entry now stops startup.
- Step-up for tool scopes: on the own host, a JWT caller refused for missing `actionScopes` gets HTTP
  403 with `WWW-Authenticate: Bearer error="insufficient_scope", scope="<route scopes> <tool scopes>"`;
  the body is still the JSON-RPC result with `SCOPE_DENIED`.
- Token exchange: an inspectable three-part issued JWT must carry a non-blank string `sub` equal to the
  caller's subject; a compact encrypted JWE is refused because its subject cannot be checked.
- Knox host: a topology whose Aegis configuration failed to load now tries again on a later request
  once 10 seconds (`KnoxGatewayRegistry.RETRY_AFTER`) have passed, so a corrected file takes effect
  without redeploying the topology. A built gateway is still kept until the topology is undeployed.
- New tests: `tracing.otlp` wiring end to end against a local collector
  (`GatewayFactoryTest`), and a real second JVM refused by the Derby lock (`DerbyStateStoreTest`).
- Module reference page: each module now opens with what it is, why it exists, what you get and when
  to use it, and the module map has an at-a-glance table.
- Priority-zero security remediation: bounded wildcard evaluation fails closed instead of treating an
  exhausted matcher as an unmatched allow; promotion explicitly documents and reports at-most-once
  approval/receipt consumption when audit intent cannot be recorded; duplicate S19 evidence is renamed
  H12; and unbound G03 validation is `recorded-unattested` pending candidate-bound raw evidence.
- Per-tool OAuth action scopes: `tools[].actionScopes` requires exact verified scopes at step 2, before
  resource scopes, policy, approval, credentials and the backend (`SCOPE_DENIED` with
  `requiredActionScopes` and `missingActionScopes`). Verified JWT scopes now ride on `CallerIdentity`
  (names only); static, SPIFFE, stdio and Knox identities carry none, so they are denied scoped tools.
  The requirement joins the approval and dry-run binding only when present, so bindings of unscoped
  tools are unchanged.
- Offline signed server catalog (`serverCatalog`, off by default): an Ed25519-signed register of which
  configured sources start, bound to each source's configuration digest, with each server's exact tool
  pins. Non-ACTIVE servers are never constructed or discovered; unpinned tools are never bound;
  pinning becomes signed-only: an inline or manifest pin the catalog does not carry, including a
  `prompt:` pin, stops startup. Startup-only, with no admin route and no runtime URL registration.
  Validated against real Flink, Iceberg, Spark and Kafka through the own host and Knox 3.0.0; the
  real-engine harness gains `SignedServers`, `FrontDoor.catalog`, and run records kept per scenario folder.
- [examples/break-glass-mcp-servers](examples/break-glass-mcp-servers/README.md): four independent
  break-glass MCP servers (Spark, Kafka, Iceberg, Flink), one folder each. In every folder the
  checked-in `aegis.yaml` is the server: the shipped profile with local addresses plus `catalog`,
  `serverCatalog` (with a commented `breakGlass` block) and `host`. Each folder has its own engine
  launcher and `Run-EndToEnd.ps1`, `Start-McpServer.ps1` and `Stop-McpServer.ps1`; `Run-All.ps1` runs
  all four. Locally, 2026-10-07: 4 of 4 servers passed (68 checks against real engines; denials must carry the
  expected Aegis deny code, and an order signed with the review key is refused). Results stay in the
  git-ignored `run/` folders.
- Break-glass containment orders (`serverCatalog.breakGlass`): signed with a separate key, contain-only,
  at most 24 hours, and an expired order refuses startup. New offline commands:
  `--server-catalog-create`, `--server-catalog-verify`, `--break-glass-create`, `--break-glass-verify`
  (sign with `--manifest-sign`).

- Clarified [docs/COVERAGE-0.1-VS-2.0.md](docs/COVERAGE-0.1-VS-2.0.md): 0.1 naming (not “1.0”), product-shape
  table, 152/155/165 arithmetic, Kafka+Connect under one profile, and Keep vs coverage distinction.
- Track Hive, Paimon, Cassandra, Ranger, Impala, and Atlas two-door Keeps only on
  [docs/ROADMAP.md](docs/ROADMAP.md); removed incomplete `HiveFrontDoorsTest` scaffold (no half-done Keep code).
- Force-track retained Keep `*-transcript.log` files under `examples/adopters/*/results/` (global `*.log`
  ignores previously dropped them from commits and broke documentation link checks).
- JDBC `driverClass` loads with the thread context class loader so engine drivers supplied as Knox/own-host
  plugin jars resolve at startup (Hive FrontDoors and other JDBC adopters).
- Spark Connect `missingTokenEnvironmentFailsStartup` test normalizes line endings before patching shipped YAML
  (Windows CRLF no longer skips the `tokenEnv` injection).
- Regenerated all 25 IDE project tutorials from kept two-door runs (`tools/build-project-tutorials.py`).
- Roadmap: explicit **Future work: remaining target verification** for Hive, Paimon, Cassandra, Ranger,
  Impala, Atlas (with acceptance criteria), plus future typed sources (Arrow/Beam) and D7/D28. Hive
  `apache/hive:4.0.1` HS2 Docker + beeline connectivity proven; FrontDoors Keep remains roadmap work
  ([ADOPTER-REAL-ENGINE-STATUS.md](docs/evidence/ADOPTER-REAL-ENGINE-STATUS.md)).
- HTML [Hello World guide](docs/guide/hello-world.html): how to add Aegis to an IDE development cycle and put
  governed MCP in front of an existing MCP server or Apache project; linked from the landing page,
  handbook, and developer guide.
- HTML [security and threat-model guide](docs/guide/security.html) for GitHub Pages: trust zones Z1 to Z6,
  STRIDE/TM01-TM10, OWASP MCP handling, S1 to S31 highlights, and the 227-test validation summary; linked
  from the landing page, handbook, developer guide, and README.
- Clarified remaining adopter Keep vs ASF process: Hive is already JDBC (same family as Calcite);
  Spark Connect is not a generic gRPC path for Atlas/Ranger/Arrow/Beam; Arrow/Beam/Hudi/Ozone/Kudu false
  HTTP facades remain **closed defects** (removed), with typed sources listed as future features.
  ASF provenance/SBOM/grant/publish items moved out of the day-to-day engineering defect list in
  [docs/ROADMAP.md](docs/ROADMAP.md) and [docs/evidence/ADOPTER-REAL-ENGINE-STATUS.md](docs/evidence/ADOPTER-REAL-ENGINE-STATUS.md).
- FrontDoors defect follow-ups: Superset chart-id seeding parses `result[].id` only and confirms charts
  via GET; `superset_get_chart` + TOOL dry-run for delete; Doris `show_proc?path=/` and
  `/backends` path corrections (inventory now 116 adapter tools).
- Generated IDE-ready tutorials for the ten newly Keep-verified adopters (Airflow, CouchDB, Doris, Druid,
  Hadoop, HBase, NiFi, Pinot, Storm, Superset), bringing the project-tutorial set to twenty-five plus
  Spark+Iceberg. See [docs/TUTORIALS.md](docs/TUTORIALS.md).
- Plain-language [tutorials README](adopters/tutorials/README.md) and per-project `ide/README.md` files
  explaining what the VS Code / Kiro / Cursor MCP JSON files mean for new users; developer README in
  each core Maven module (for example [aegis-config/README.md](aegis-config/README.md)); Helm chart
  README expanded with install and values examples.
- Real two-door Keep runs retained for Airflow 2.9.3, Doris 4.1.3, Druid 28.0.1, Storm 2.6.2, and
  Superset 4.0.2 (own host and Knox), in addition to the earlier Docker Keep set (CouchDB, Pinot,
  Hadoop HDFS, HBase, NiFi). Stack-blocked adapters remain Atlas, Cassandra, Hive, Impala, Paimon,
  and Ranger. See [docs/evidence/ADOPTER-REAL-ENGINE-STATUS.md](docs/evidence/ADOPTER-REAL-ENGINE-STATUS.md).
- New configuration-driven architecture: one governance engine (`aegis-engine`), MCP protocol for the
  2025 and 2026-07-28 revisions (`aegis-mcp`), strict declarative configuration (`aegis-config`), and
  two front doors: the own-host gateway (`aegis-gateway`) and an optional Apache Knox 3.0 extension
  (`aegis-knox3-extension`).
- The 0.1 per-engine Java modules (`mcp-gateway-core`, `mcp-gateway-dist`, `mcp-adapter-*`) are
  removed from this line. Their 2.0 successors are named configuration profiles under
  `adopters/configurations/` and shared typed sources (JDBC, ZooKeeper, Spark Connect, Kafka Admin).
  The 0.1 source remains in Git history. See [docs/HISTORY.md](docs/HISTORY.md) and
  [docs/COVERAGE-0.1-VS-2.0.md](docs/COVERAGE-0.1-VS-2.0.md).
- Arrow, Beam, Hudi, Kudu, and Ozone are planned typed sources and are not part of this release.
- Stricter startup validation: enumerated values (`unlistedTools`, rule `effect`, credential `type`,
  `era`, `catalog.mode`, screening and hook modes, `defaultClass`, `server.versions`) accept only their
  documented names; the OPA URL is checked like other off-host endpoints, including `egress`; OAuth
  metadata keys without `oauth.resource` stop startup.
- Per-caller backend credentials: `credentials.type: token-exchange` exchanges the caller's verified
  inbound JWT for a backend-audience token (RFC 8693) on each call, with caching, a subject check and
  no fallback. Verified against Keycloak 26.8.0 standard token exchange
  ([evidence](docs/evidence/TOKEN-EXCHANGE-KEYCLOAK.md)). Own host only.
- New `aegis-source-a2a` plugin (`type: a2a`): one A2A 1.0 agent as one governed tool over JSON-RPC
  `SendMessage`, with a same-origin rule for the card's endpoint. Verified through both front doors
  against an agent built on the official `a2a-sdk` 1.2.1 (`A2aAgentFrontDoorsTest`); new profile
  `adopters/configurations/a2a/`.
- New command `--openapi-draft <openapi> <draft.yaml>`: a reviewable configuration with every tool
  `DESTRUCTIVE` and a deny-all policy. Run on the Apache Iceberg 1.10.1 REST catalog document against
  the official catalog server (`OpenApiDraftIcebergTest`).
- Input schemas: local `$ref`s are resolved and enforced; a remote `$ref` is never fetched and the
  call is denied with `INVALID_INPUT`. Regression tests for bug classes published against other MCP
  gateways (remote `$ref`, expression-language response filters, run-time backend registration).
- Reference hook services in `adopters/hooks/` (PII guard, secrets guard, change window; Python
  standard library), with a live end-to-end run against the gateway jar that CI repeats on every push.
- Fix outbound HTTP redirect handling: `BoundedHttp` returns 3xx status with an empty body instead of
  failing while reading a closed stream (downstream MCP proxy and egress tests).
- Fix Knox adopter runs: `run-example.ps1` / `run-example.sh` build typed-source `plugin.classpath`
  artifacts with `-Preal-engines` before front-door tests; document 0.1 versus 2.0 engine coverage in
  [docs/COVERAGE-0.1-VS-2.0.md](docs/COVERAGE-0.1-VS-2.0.md); retain [2026-10-05 E2E validation](docs/evidence/E2E-VALIDATION-2026-10-05.md).
- Container image (`Dockerfile`, non-root, digest-pinned bases) and a Helm chart (`charts/aegis`,
  StatefulSet with one journal volume per replica, render-time safety checks). The chart is linted and
  schema-checked and its rendered configuration starts the gateway; the image is built in CI, not
  locally.
- `SECURITY.md` describes the advisory process after a report: triage with CVSS 4.0, fix in the
  advisory's private fork with a regression test, release, then advisory and CVE.
- Product [threat model](docs/THREAT-MODEL.md) with trust-zone SVG
  ([`aegis-threat-model.svg`](docs/assets/aegis-threat-model.svg)), DFD, STRIDE-by-zone analysis,
  detailed TM01-TM10 abuse cases, OWASP MCP handling matrix, and
  [security validation evidence](docs/evidence/SECURITY-VALIDATION.md) documenting the 227-test
  focused S1-S31 suite and per-class proof catalogue; linked from Security, Handbook, Validation,
  and governance mapping.
- Test rename: `KnoxAclBodyBlindnessTest` is now `KnoxAclRouteScopeTest` (same assertions).
- New key `output.allowBinary` (default `false`): tool results with image, audio or blob parts are
  withheld unless allowed. Existing tools that return images need `output.allowBinary: true`.
- One audit journal file has one writer per host, including within one JVM.
- `jdbc_execute_statement` runs exactly one statement, using the shared statement splitter.
- Audit head export: `audit.checkpoint` writes `{seq, head}` to a separate file, the gateway refuses to
  reopen a truncated or regenerated journal, and `--verify-audit <journal> --checkpoints <file>` checks
  every exported head.
- Signed catalog manifests: `catalog.manifest` (Ed25519) supplies authoritative pins, and the gateway
  jar gains `--manifest-keygen`, `--manifest-create`, `--manifest-sign` and `--manifest-verify`.
- Secret sources: `secrets.source: env | file | vault` (KV v2) and the `SecretProviderFactory` SPI.
  Missing-secret messages now say "not set by secrets.source" instead of naming the environment.
- Mutual TLS and SPIFFE on the own host: `host.tls.clientAuth: require` with `clientCaFile`, and
  `identity.type: spiffe`. `McpHttpRequest` gains a `peerCertificates` component; the four-argument
  constructor is unchanged.
- The default build runs the `kafka-admin` test against a real Kafka 3.9.1 KRaft broker;
  `MockAdminClient` and the `KafkaAdminToolSourceFactory.adminFactory` test seam are removed, and
  `examples/aegis-validation-kafka-server` is now part of the default build.
- `TwoGatewayProcessesPostgresIT`: two gateway processes on one PostgreSQL namespace
  ([evidence](docs/evidence/POSTGRES-TWO-GATEWAYS.md)).
- Module reference page (`docs/guide/modules.html`) and [ADR-002](docs/adr/ADR-002-KNOX-3.1-JAKARTA-TARGET.md)
  on the Knox 3.1 Jakarta target. Findings are logged as D18 to D26 in [docs/DEFECT-LOG.md](docs/DEFECT-LOG.md).
- Evidence and limits are recorded in [docs/VALIDATION.md](docs/VALIDATION.md),
  [docs/evidence/CLAIMS-MATRIX.md](docs/evidence/CLAIMS-MATRIX.md), and the complete per-adopter
  evidence/status index in [examples/adopters/README.md](examples/adopters/README.md).

## 0.1.0

- Multi-module Aegis MCP Governance Gateway (Java 17)
- Core interceptor chain (10 steps), auth, FinOps, catalog integrity, YAML manifests
- Flink adapter (REST + SQL Gateway) with offline HTTP test-double tests
- Kafka adapter with AdminClient, Schema Registry HTTP, and DLQ inspect
- Spark adapter with History Server, Livy submit/kill, optional SQL HTTP
- Iceberg adapter with REST catalog reads/mutates, credential redaction, VRP dry-run
- OAuth resource server with JWKS JWT validation (Nimbus)
- Dist shade jar, Docker, Helm charts
- Docs (`getting-started`, `operations`, `adapters`, DESIGN, LLD, RESEARCH) and examples
