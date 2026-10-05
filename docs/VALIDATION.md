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
# Validation report

This report is evidence, not marketing. The current source of truth for individual claims is
[evidence/CLAIMS-MATRIX.md](evidence/CLAIMS-MATRIX.md). A target is verified only after the current
working tree runs it and preserves the result. A skipped test, a source review, a converter test against
a different engine, or a contract route harness is never relabelled as target compatibility. Workstation
build output is useful regression feedback, but becomes release evidence only when retained against a clean,
committed candidate revision.

## Tested environments

The local runs in this report and in [evidence/](evidence/) used one Windows workstation:

| Part | Version | Used for |
| --- | --- | --- |
| Operating system | Windows 11, x64 workstation | every local run |
| JDK | 17.0.17 or 21.0.11 (Microsoft build) | the Maven build, the tests and the gateway jar |
| JDK | 21.0.11–21.0.12 | Keycloak only ([token-exchange evidence](evidence/TOKEN-EXCHANGE-KEYCLOAK.md)) |
| Maven | 3.9.11 | the build |
| Python | 3.13 | Python clients, Python engines and tools |
| Node.js | 24.14.0 | the stdio MCP filesystem server |
| Apache Knox | 3.0.0 | the Knox front door |
| Apache ZooKeeper | 3.9.3 | ZooKeeper converter and Knox front-door run |
| Apache Kafka | 3.9.1 | `kafka-admin` source and Kafka front-door run |
| Apache Iceberg | 1.10.1 | REST catalog front-door run |
| Apache Flink | 1.20.1 | `RealFlinkTest` |
| Apache Spark | 4.0.1 | `RealSparkTest`, `RealSparkConnectServerTest` |
| Apache Derby | 10.16.1.1 | JDBC converter and `derby-local` state |
| PostgreSQL | 17.6 | two gateway processes ([evidence](evidence/POSTGRES-TWO-GATEWAYS.md)) |
| OpenBao | 2.7.1 | KV secret provider ([evidence](evidence/SECRETS-OPENBAO.md)); HashiCorp Vault 1.17.6 dev also exercised 2026-10-05 (same KV v2 API) |
| Keycloak | 26.8.0 | RFC 8693 token exchange ([evidence](evidence/TOKEN-EXCHANGE-KEYCLOAK.md)) |
| a2a-sdk (Python) | 1.2.1 | the A2A agent behind the `a2a` source (`A2aAgentFrontDoorsTest`, both front doors) |
| Helm, kubeconform, hadolint, actionlint | 4.3.0, 0.8.0, 2.15.1, 1.7.12 | chart, manifest, Dockerfile and workflow checks ([chart README](../charts/aegis/README.md)) |
| Apache CouchDB | 3.4.2 (official Docker image) | `CouchdbFrontDoorsTest` own host and Knox ([adopter status](evidence/ADOPTER-REAL-ENGINE-STATUS.md)) |
| Apache Pinot | 1.2.0 (official Docker QuickStart) | `PinotFrontDoorsTest` own host and Knox |

The RDS PostgreSQL 18.3 run is described in the [PostgreSQL RDS validation evidence](evidence/POSTGRES-RDS-VALIDATION.md).
The engine version of each adopter run is in [`examples/adopters/README.md`](../examples/adopters/README.md).
Docker was used locally on 2026-10-05 for PostgreSQL 17.6, Vault 1.17.6 dev, and the gateway image smoke
test ([E2E validation record](evidence/E2E-VALIDATION-2026-10-05.md)). Helm 4.3.0 and kubeconform 0.8.0
were installed locally for chart checks; no Kubernetes cluster install was performed. No Windows Subsystem
for Linux distribution was installed, so engines that need a Linux-only service stack without a Windows
path were not run; each affected adopter README names what it needs.

## Local regression snapshot

The following local workstation result is regression feedback, not release-candidate evidence. Re-run
and retain it from a clean committed candidate before using the count in an Aegis release claim:

```console
$ mvn -B "-Prelease" clean verify
BUILD SUCCESS          20 reactor projects, 364 tests, 0 failures, 0 errors, 0 skipped
RAT                     0 unapproved files
Release profile         source jars and CycloneDX SBOMs generated under target/
```

The 364 executed tests break down as follows. The default reactor does not include the opt-in
`real-engines` validation modules; their runs are recorded separately below.

| Module | Tests | Evidence class |
| --- | ---: | --- |
| `aegis-engine` | 115 | deterministic governance, controls, audit and content tests, including local and remote schema `$ref` handling and JSON Pointer expression refusal, including seven adversarial separation-of-duties cases, one-writer journal enforcement, binary tool-output refusal, audit head checkpoints (truncated and regenerated journals) and Ed25519 signed catalog manifests |
| `aegis-mcp` | 45 | protocol, downstream client and admin API tests, including the non-diagnostic fixed-journal audit verifier route and the absence of any run-time backend registration route |
| `aegis-observability-otlp` | 7 | bounded asynchronous OTLP/HTTP exporter tests: trace document, privacy, collector delivery, drain, failures and full queue |
| `aegis-config` | 56 | strict configuration (including RFC 8693 token-exchange credentials and the OpenAPI draft generator, enumerated values and policy endpoint checks), hooks, dry-run safety, policy, observability wiring, fail-closed HTTP application-status validation, secret sources (file, Vault settings and failures, a ServiceLoader provider), checkpoint and manifest configuration. `VaultKvSecretProviderIT` runs only with `-Pvault-integration` ([OpenBao evidence](evidence/SECRETS-OPENBAO.md)) |
| `aegis-mcp-guardrails` | 2 | direct evidence-reference and protected-resource guardrail tests |
| `aegis-mcp-security` | 23 | direct identity, OAuth metadata and published-URL safety, strict host-security settings, route-admission, OAuth scope enforcement, exact-origin CORS, and X.509-SVID rules on `keytool`-issued certificates |
| `aegis-state-derby` | 4 | real embedded Apache Derby durable-state tests: restart, expiration, atomic nonce, rate windows and provider path safety |
| `aegis-state-postgres` | 0 in default build | provider compiles and is packaged; `PostgresStateStoreIT` runs in the opt-in `postgres-integration` profile against a real server (see [PostgreSQL RDS validation evidence](evidence/POSTGRES-RDS-VALIDATION.md)) |
| `aegis-gateway` | 26 | JDK HTTP binding tests (including the `--openapi-draft` command): TLS/identity integration, own-host route admission, dedicated audit verifier role, 403 `insufficient_scope` over the wire, exact-origin CORS, bounds, metadata, metrics, stdio, Derby provider discovery, the generic 500 path, audit checkpoints from the command line, the signed-manifest workflow on the running host, and SPIFFE over real mutual TLS. `TwoGatewayProcessesPostgresIT` runs only with `-Ppostgres-integration` |
| `aegis-source-jdbc` | 16 | actual embedded Apache Derby source tests plus context-aware credential-name resolution and single-statement enforcement |
| `aegis-source-zookeeper` | 16 | actual embedded Apache ZooKeeper, including an unreachable ensemble |
| `aegis-source-a2a` | 8 | A2A card trust rules, request shape, reply mapping and gateway integration against a local listener; the protocol run against an `a2a-sdk` 1.2.1 agent is `A2aAgentFrontDoorsTest` (opt-in) |
| `aegis-knox3-extension` | 5 | Knox 3.0.0 API integration with a downstream MCP harness and the real `AclsAuthz` route-scope check (Knox route ACLs do not inspect the MCP request body) |
| `examples/aegis-examples` | 36 | configuration, nested shared-profile and adopter layout, coverage, host-packaging boundary and built-jar contents, SVG/PNG/link/anchor documentation-contract, canonical-copy, rendered-Pages-route, Mermaid, static-asset and project-tutorial tests; the `kafka-admin` source against a real Kafka 3.9.1 KRaft broker |
| `examples/aegis-spark-connect` | 5 | Spark source tests including context-aware named-token resolution |
| **total** | **364, 0 skipped** | local release-profile regression, 2026-10-03 |

The release-profile build has no external-network or operator-provided service requirement for its default test set. It proves the control plane, configuration rules, embedded-real converter behavior, PostgreSQL provider packaging, attached source jars, and generated CycloneDX SBOMs. The generated artifacts remain local build outputs until they are reproduced from a clean committed candidate, reviewed, signed, and retained. The default build does not run the PostgreSQL integration tests; shared-state semantics rest on the separate `postgres-integration` run recorded in the [PostgreSQL RDS validation evidence](evidence/POSTGRES-RDS-VALIDATION.md).

### Full matrix revalidation (2026-10-05)

See [evidence/E2E-VALIDATION-2026-10-05.md](evidence/E2E-VALIDATION-2026-10-05.md) for the claim-by-claim
pass on this working tree, including `ZooKeeperFrontDoorsTest` (Knox bundle SHA-256
`0726C4CE71D23856F8FE9A58BE2C80E7FC7E4AE740ED70E8ECC8CBFB2617B780`), Keycloak token exchange,
Helm/kubeconform/`start_rendered.py`, hooks e2e, and Docker Postgres/Vault ITs. RDS PostgreSQL 18.3 was
not re-run on this date.

### Security control validation (2026-10-05)

See [evidence/SECURITY-VALIDATION.md](evidence/SECURITY-VALIDATION.md) for the focused S1–S31
deterministic suite (and related host/Knox perimeter tests) re-run on this tree, plus the product
[threat model](THREAT-MODEL.md). That record proves gateway control properties; it does not certify
OWASP Top 10 elimination.

### Runs added on 2026-10-03

| Run | Result | Against |
| --- | --- | --- |
| `A2aAgentFrontDoorsTest` (`-Preal-engines`, `AEGIS_A2A_PYTHON`) | 2 of 2 passed, 0 skipped | an agent on the official `a2a-sdk` 1.2.1, own host and stock Knox 3.0.0 |
| `OpenApiDraftIcebergTest` (`-Preal-engines`) | 1 of 1 passed | the Iceberg 1.10.1 REST document and the official `RESTCatalogServer` |
| `examples/adopters/token-exchange/keycloak_e2e.py` | 5 of 5 checks passed | Keycloak 26.8.0 ([evidence](evidence/TOKEN-EXCHANGE-KEYCLOAK.md)) |
| `adopters/hooks/e2e_gateway.py` | 7 of 7 checks passed | the gateway jar as a process, CPython `http.server`, the three reference hooks |
| `adopters/hooks/test_hooks.py` | 12 of 12 passed | the hook services over real HTTP |
| Helm chart checks | lint 0 failures, kubeconform 7 of 7 valid, rendered configuration reached `/readyz` | [chart README](../charts/aegis/README.md); the image itself was not built locally |

### Latest opt-in real-engine profile

The real-engine examples are intentionally not part of the default build. On 2026-10-03 all 15 adopters
marked **passed** in [`examples/adopters/README.md`](../examples/adopters/README.md), plus the
`spark-iceberg` scenario, were rerun on the current working tree through both own-host Aegis and the
current `knox-bundle` jar in a stock Knox 3.0.0 process: 33 tests in two modules, 0 failures, 0 skipped
(Pulsar after the D27 harness fix). All 32 kept audit journals pass `--verify-audit`. The kept folders were
refreshed from that run, and the per-project tutorials in [TUTORIALS.md](TUTORIALS.md) are built from them.
Each retained folder contains:

- `aegis/own-host.yaml`, `aegis/knox-aegis.yaml` and `aegis/knox-topology.xml`: the exact redacted
  configuration each front door used;
- `engine/`: the engine configuration files the test wrote;
- `HOW-TO-RUN.md`: process startup commands, own-host and Knox setup, a sample MCP request and the
  step/result table;
- `results/`: transcripts, audit journals, Surefire result and, where needed, the Knox plugin report.

The retained materials record the own-host and Knox paths that were exercised. The claims matrix is
authoritative for whether each run remains current after later code or configuration changes; a release
candidate still needs its own run.

The other 16 shipped profiles are config/contract supported but not real-engine verified; the precise
service prerequisite and the fact that no evidence was fabricated are recorded in the same adopter index.

## Inventory contract

`AdapterConfigsTest`, `Coverage01VsTwoTest` and `DocumentationClaimsTest` make the public inventory
executable:

| Claim | Current value | How it is checked |
| --- | ---: | --- |
| Shared reference configurations | **6 executable reference profiles** plus a separate teaching template | one executable YAML and developer README in each `adopters/configurations/<profile>/` directory; `DocumentationClaimsTest` rejects flat root YAML and missing catalog links |
| Adapter configurations | **25 deployable adapter configurations** | one executable YAML in each `adopters/configurations/adapters/<adopter>/` directory |
| Adapter tools | **115 tools** | summed from the same parsed YAML |
| 0.1 frozen inventory | 156 tools, 33 engines | checked against every implemented or explicitly absent verdict |
| Implemented 0.1 successor surface | 152 tools | exact frozen verdict accounting; five engine surfaces without a real API were removed |
| New Kafka Admin capabilities | 2 tools | real AdminClient cluster and configuration reads |
| Full comparison surface, including Apache Burr (incubating) | 164 tools | exact `Coverage01VsTwoTest` assertion |
| Deliberately absent engine surfaces | 5 engines, 18 tools | Arrow, Beam, Hudi, Kudu and Ozone need real typed sources; no configuration ships for them |
| Less-guarded implemented 0.1 tools | 0 | class-order assertion |

The 25 deployable adapter configurations and 115 tools are governed in the current default build. Their external
compatibility state is below and in the claims matrix. A removed route that had no real API counts as an accurate
absence, not as a 2.0 capability.

## Test boundaries: real software versus deterministic fixtures

No production configuration selects a test-double backend. The five configurations whose
routes did not exist were deleted rather than relabelled. The remaining non-production fixtures are
kept in `src/test` and support narrow assertions only:

| Fixture | Scope | What it proves | What it cannot prove |
| --- | --- | --- | --- |
| `TestMcpServer`, `TestStdioMcpServer` | test sources, published only in the Maven test jar | protocol negotiation, sessions, pagination, SSE, bounds, content governance and failure handling independent of the production handler | interoperability with every external MCP implementation |
| `RestRouteContractHarness` | `examples/aegis-examples/src/test` | configured method/path/body rendering and governance order for the 25 shipped configurations | that a target product/version serves the route; a real-target run remains required |
| local HTTP endpoints in OPA, JWKS, hook and OTLP tests | module test sources | exact request/response, timeout and fail-closed behavior | compatibility with a named external product unless a separate real-target row says so |

Two in-memory classes are production code but are not backend simulations. `InMemoryStateStore` is the real,
bounded, one-process default governance state with no restart durability. `InMemoryAuditSink` is a
programmatic embedding/test observer; configured gateways always create `HashChainFileAuditSink` as
the primary journal. Embedded Apache Derby and ZooKeeper tests run actual upstream implementations,
not hand-written protocol fixtures.

## Evidence by target

The complete current target-status source of truth is
[`examples/adopters/README.md`](../examples/adopters/README.md): it distinguishes 15 real two-door runs
from 16 config/contract-supported profiles without fabricated engine evidence. The table below is
supplemental source/converter detail.

| Target | Current evidence | Current status |
| --- | --- | --- |
| JDBC converter | 16 tests against Apache Derby 10.16.1.1, including result cap, error mapping and read-only behavior | verified-current converter behavior |
| ZooKeeper converter | 16 tests against ZooKeeper 3.9.3 through Curator TestingServer, including digest-authenticated owner-only node creation and an unreachable ensemble counted as an availability failure; AdminServer command names resolve through ZooKeeper's own registry | verified-current converter behavior |
| Flink 1.20.1 | `RealFlinkTest` starts a real Flink MiniCluster and verifies read, denied cancel, dry-run, approval and real cancellation against the canonical config | verified-current source behavior; rerun when Flink source, Flink version or config changes |
| Spark 4.0.1 | `RealSparkTest` starts a real local Spark driver and verifies History/driver-UI reads through the canonical config; standalone-master submission/kill remains unexercised | verified-current read-source behavior; standalone-master write path still needs its own run |
| Spark Connect 4.0.1 | `RealSparkConnectServerTest` starts a real Spark Connect server in a separate JVM and exercises reads plus a dry-run, independent approval and real destructive drop | verified-current source behavior; rerun when Spark Connect source, Spark version or config changes |
| Iceberg 1.10.1 REST catalog | `IcebergFrontDoorsTest`: real `RESTCatalogServer`, own host and Knox; rerun 2026-10-05 on the current tree | verified-current source behavior; rerun on the exact candidate before a release claim |
| Kafka 3.9.1 and Connect | `KafkaFrontDoorsTest`: real KRaft broker and Connect worker, own host and Knox; rerun 2026-10-05; and in every default build `ExampleConfigsTest` drives the `kafka-admin` source against a real broker | verified-current source behavior; rerun on the exact candidate before a release claim |
| 16 profiles without real-engine evidence | deterministic `AdapterConfigsTest` contract route and governance flow (and documented environment prerequisites in `examples/adopters/`) | config/contract supported; target runs pending, no compatibility claim |
| Arrow, Beam, Hudi, Kudu and Ozone | no deployable configuration; 18 old tools have explicit absent verdicts because the former routes were not real product APIs | typed source implementation pending |
| `aegis-state-postgres` | `PostgresStateStoreIT` ran four real RDS PostgreSQL 18.3 integration tests covering two-provider nonce/rate sharing, persistence, expiration, escaped scans, invalid rate requests, and environment-named credentials | verified-current source behavior; see [PostgreSQL RDS validation evidence](evidence/POSTGRES-RDS-VALIDATION.md). Re-run from the exact candidate before release or active-active claims |
| Two gateway processes, one `postgres` namespace | `TwoGatewayProcessesPostgresIT` starts two `Main` JVMs against a real PostgreSQL 17.6 server: an approval requested on A is decided on B, the approved delete runs once on B, the replay on A is refused, and a rate window of 3 is one window across both | verified-current source behavior; see [two-gateway evidence](evidence/POSTGRES-TWO-GATEWAYS.md). Re-run from the exact candidate before a release claim |
| Knox extension | current API-level tests include the real `AclsAuthz` route-scope check; on 2026-10-05 `ZooKeeperFrontDoorsTest` installed the current `knox-bundle` jar (SHA-256 `0726C4CE71D23856F8FE9A58BE2C80E7FC7E4AE740ED70E8ECC8CBFB2617B780`) into stock Knox 3.0.0 `ext/` and ran the full scenario against real ZooKeeper 3.9.3 (claims matrix H10); the separate manual cold-install transcript used an earlier four-jar layout | verified-current for the bundle on the local snapshot; the manual cold-install transcript is verified-historical. Knox 3.1 (Jakarta) needs a separate build, see [ADR-002](adr/ADR-002-KNOX-3.1-JAKARTA-TARGET.md) |

The important distinction is that Aegis is not waiting for a test to decide whether it governs an action.
Every current config still traverses the same enforcement chain. The target run is required to claim that
the particular endpoint or driver works with the particular upstream release.

## What must run before a release claim

1. Re-run Flink, Spark, Spark Connect, Iceberg and Kafka on the exact release candidate tree.
2. Re-run `PostgresStateStoreIT` from the exact release candidate against a real PostgreSQL deployment and retain the multi-provider evidence before claiming active-active approval/rate-window support.
3. Implement and run real typed sources for Arrow Flight, Beam JobService, Hudi, Kudu and Ozone; do not restore their former HTTP configurations, which targeted routes the products do not serve.
4. Run every remaining HTTP adapter against its documented product version, preserve the raw output under
   `docs/evidence/`, then update its claim-matrix row.
5. Re-run the Knox front-door scenarios on the exact release candidate bundle (the 2026-10-03 run covers all 16 scenarios on the local working tree), and re-capture the manual cold-install transcript with that bundle.
6. Run an OpenTelemetry collector interoperability test, and re-run `TwoGatewayProcessesPostgresIT` on the exact candidate.
7. Run the provenance scan required by `AGENTS.md` and attach the result.

## Explicit limits

- The embedded Derby suite proves the JDBC converter contract, not HiveServer2 or Avatica release
  compatibility. Those targets need their own run.
- The SQL read-only guard is an indicator. A source credential that cannot write is the enforcement
  control for SQL effects.
- The audit chain is locally tamper-evident. With `audit.checkpoint` the gateway exports its head to a
  separate file and refuses to start on a journal shorter than, or different at, the last exported head;
  `--verify-audit <file> --checkpoints <file>` checks every exported head offline. The protection is as
  strong as the storage the checkpoint file lives on: Aegis does not itself write to a WORM store or a
  transparency log.
- `memory` and `derby-local` are single-process: `memory` loses state on restart; `derby-local`
  survives a restart of one process. The suite does not start a second JVM against the same Derby
  path, so the one-writer guarantee rests on Derby's documented embedded locking. `postgres` shares
  nonces, pending approvals and rate windows across processes (four integration tests against RDS
  PostgreSQL 18.3, and two gateway JVMs against PostgreSQL 17.6); catalog pins and circuit breakers stay per process, so active-active claims are
  limited to those shared items.
- Injection screening and redaction reduce specific risks; neither is a general DLP or prompt-injection
  proof.

## Reproduce

```powershell
# From the repository root
C:\tools\apache-maven-3.9.11\bin\mvn.cmd -B clean install

# Adds real-engine modules where the profile can start an engine. Iceberg and Kafka require their
# loopback environment variables and servers before their tests execute.
C:\tools\apache-maven-3.9.11\bin\mvn.cmd -B "-Preal-engines" clean install
```

For each external target, use the source-specific setup and preservation instructions in the relevant
tutorial and add the result to [evidence/CLAIMS-MATRIX.md](evidence/CLAIMS-MATRIX.md).
