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

- New configuration-driven architecture: one governance engine (`aegis-engine`), MCP protocol for the
  2025 and 2026-07-28 revisions (`aegis-mcp`), strict declarative configuration (`aegis-config`), and
  two front doors: the own-host gateway (`aegis-gateway`) and an optional Apache Knox 3.0 extension
  (`aegis-knox3-extension`).
- The 0.1 per-engine Java modules (`mcp-gateway-core`, `mcp-gateway-dist`, `mcp-adapter-*`) are
  removed from this line. Their 2.0 successors are named configuration profiles under
  `adopters/configurations/` and shared typed sources (JDBC, ZooKeeper, Spark Connect, Kafka Admin).
  The 0.1 source remains in Git history. See [docs/HISTORY.md](docs/HISTORY.md) and
  [docs/COVERAGE-0.1-VS-2.0.md](docs/COVERAGE-0.1-VS-2.0.md).
- Five former product-named HTTP configurations (Arrow, Beam, Hudi, Kudu, Ozone) are not shipped,
  because their routes were not real product APIs.
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
