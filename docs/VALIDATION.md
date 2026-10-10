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

This report records the builds, target versions, and adopter runs for Aegis Gateway 2.0. Individual claims are in [evidence/CLAIMS-MATRIX.md](evidence/CLAIMS-MATRIX.md). Each adopter page names the target version that was exercised.

## Tested environments

The local runs in this report and in [adopter status](evidence/ADOPTER-REAL-ENGINE-STATUS.md) used one Windows workstation:

| Part | Version | Used for |
| --- | --- | --- |
| Operating system | Windows 11, x64 workstation | every local run |
| JDK | 17.0.17 or 21.0.11 (Microsoft build) | the Maven build, the tests and the gateway jar |
| JDK | 21.0.11 to 21.0.12 | Keycloak only ([token-exchange evidence](evidence/TOKEN-EXCHANGE-KEYCLOAK.md)) |
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
| Helm, kubeconform, hadolint, actionlint | 4.3.0, 0.8.0, 2.15.1, 1.7.12 | chart, manifest, Dockerfile and workflow checks ([chart README](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/aegis-2.0-review-20261009/charts/aegis/README.md)) |
| Apache CouchDB | 3.4.2 (official Docker image) | `CouchdbFrontDoorsTest` own host and Knox ([adopter status](evidence/ADOPTER-REAL-ENGINE-STATUS.md)) |
| Apache Pinot | 1.2.0 (official Docker QuickStart) | `PinotFrontDoorsTest` own host and Knox |

The temporary external PostgreSQL run is described in the [PostgreSQL validation evidence](evidence/POSTGRES-RDS-VALIDATION.md); its engine version was not retained.
The engine version of each adopter run is in [`examples/adopters/README.md`](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/aegis-2.0-review-20261009/examples/adopters/README.md).
Docker was used locally on 2026-10-05 for PostgreSQL 17.6, Vault 1.17.6 dev, and the gateway image smoke
test ([E2E validation record](evidence/E2E-VALIDATION-2026-10-05.md)). Helm 4.3.0 and kubeconform 0.8.0
were installed locally for chart checks; no Kubernetes cluster install was performed. No Windows Subsystem
for Linux distribution was installed, so engines that need a Linux-only service stack without a Windows
path were not run; each affected adopter README names what it needs.

## Default build (2026-10-03)

```console
$ mvn -B "-Prelease" clean verify
BUILD SUCCESS          20 reactor projects, 364 tests, 0 failures, 0 errors, 0 skipped
RAT                     0 unapproved files
Release profile         source jars and CycloneDX SBOMs generated under target/
```

The default reactor covers governance, configuration, and packaging. Opt-in real-engine runs are recorded in the sections below. PostgreSQL shared-state runs are in the dated integration evidence.

### Full matrix revalidation (2026-10-05)

See [evidence/E2E-VALIDATION-2026-10-05.md](evidence/E2E-VALIDATION-2026-10-05.md) for the historically
reported claim-by-claim pass on that mutable working-tree snapshot, including `ZooKeeperFrontDoorsTest` (Knox bundle SHA-256
`0726C4CE71D23856F8FE9A58BE2C80E7FC7E4AE740ED70E8ECC8CBFB2617B780`), Keycloak token exchange,
Helm/kubeconform/`start_rendered.py`, hooks e2e, and Docker Postgres/Vault ITs. The later temporary external PostgreSQL run was not part of this date's record.

### Security control validation (2026-10-05)

See [evidence/SECURITY-VALIDATION.md](evidence/SECURITY-VALIDATION.md) for the focused S1 to S31 control-to-test plan and its 227-test result. See the product [threat model](THREAT-MODEL.md) for the control boundaries.

### Runs added on 2026-10-03

| Run | Result | Against |
| --- | --- | --- |
| `A2aAgentFrontDoorsTest` (`-Preal-engines`, `AEGIS_A2A_PYTHON`) | 2 of 2 passed, 0 skipped | an agent on the official `a2a-sdk` 1.2.1, own host and stock Knox 3.0.0 |
| `OpenApiDraftIcebergTest` (`-Preal-engines`) | 1 of 1 passed | the Iceberg 1.10.1 REST document and the official `RESTCatalogServer` |
| `examples/adopters/token-exchange/keycloak_e2e.py` | 5 of 5 checks passed | Keycloak 26.8.0 ([evidence](evidence/TOKEN-EXCHANGE-KEYCLOAK.md)) |
| `adopters/hooks/e2e_gateway.py` | 7 of 7 checks passed | the gateway jar as a process, CPython `http.server`, the three reference hooks |
| `adopters/hooks/test_hooks.py` | 12 of 12 passed | the hook services over real HTTP |
| Helm chart checks | lint 0 failures, kubeconform 7 of 7 valid, rendered configuration reached `/readyz` | [chart README](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/aegis-2.0-review-20261009/charts/aegis/README.md) |

### Signed server catalog and break-glass against real engines (2026-10-06, local working tree)

`mvn -B -Preal-engines -pl examples/aegis-validation verify -Dtest=FlinkFrontDoorsTest,IcebergFrontDoorsTest,SparkFrontDoorsTest,KafkaFrontDoorsTest`
with `AEGIS_KNOX_HOME` set: 17 of 17 passed, 0 skipped. That count includes the existing two-door scenarios, which rerun unchanged on the modified harness.

For each engine, `SignedServers` reads the definitions with `REQUIRE_PINS` (nothing trusted), signs a server
catalog of every configured source with its configuration digest and exact pins, and opens it behind the own
host and a stock Knox 3.0.0 gateway. `GET catalog` must list exactly the signed tools as ACTIVE, with the signed
digests, and a real read must succeed. A break-glass order is then signed with a separate incident key.

| Engine | Signed catalog | Break-glass order | Result through both doors |
| --- | --- | --- | --- |
| Flink 1.20.1 MiniCluster | `flink` | `flink=QUARANTINED` | catalog read of a real job; with the only server contained, start-up is refused (Knox log: `no source that the signed server catalog lets start`); an edited `baseUrl` is refused on the own host |
| Iceberg 1.10.1 `RESTCatalogServer` | `iceberg` | `iceberg=REVOKED` | real read and resource-scope refusal under the catalog; contained start-up refused |
| Spark 4.0.1 application UI + standalone Master | `spark-history`, `spark-master` | `spark-master=REVOKED` | Master tools `NOT_EXPOSED` to reader and operator, the waiting driver stays `SUBMITTED`, application reads still governed |
| Kafka 3.9.1 KRaft broker + Connect worker | `kafka-connect`, `schema-registry`, `kafka-admin` | `kafka-connect=REVOKED` | Connect tools `NOT_EXPOSED`, the real connector is not deleted, broker reads still governed |

Every audit journal from the run was afterwards checked by hand with `--verify-audit` (2026-10-07:
12 of 12 journals OK); the harness itself does not run that check. The run records stay under
`examples/aegis-validation/target/adopter-results/<engine>-server-catalog` and `<engine>-break-glass`: transcripts, the
signed catalog, the order, public keys and Knox logs. They are not copied into `examples/adopters/`. The catalogs were
built with the library API (`SignedServerCatalog`, `SignedCatalogManifest`), not the CLI; the CLI path is covered
on the own host by `ServerCatalogFlowTest`. Spark Connect and Spark with Iceberg (module
`aegis-validation-spark-connect`) were not run with a catalog.

### Dated opt-in real-engine profile (2026-10-05)

The real-engine examples are intentionally not part of the default build. On 2026-10-05 the catalog marked
**26** adopter/scenario profiles as real two-door **passed** in
[`examples/adopters/README.md`](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/aegis-2.0-review-20261009/examples/adopters/README.md) (the earlier 2026-10-03 cohort plus the
Docker-backed Airflow, CouchDB, Doris, Druid, Hadoop, HBase, NiFi, Pinot, Storm, and Superset Keeps, and
the `spark-iceberg` scenario). Each passed Keep was run through both own-host Aegis and the then-built
`knox-bundle` jar in a stock Knox 3.0.0 process. Retained folders were refreshed from those runs; the
per-project tutorials in [TUTORIALS.md](TUTORIALS.md) are built from them.
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

The other **6** shipped profiles (Atlas, Cassandra, Hive, Impala, Paimon, Ranger) are config/contract
supported as configuration; the service prerequisite for each is recorded in the same adopter index and on
[ROADMAP.md](ROADMAP.md).

## Inventory contract

`AdapterConfigsTest`, `Coverage01VsTwoTest` and `DocumentationClaimsTest` make the public inventory
executable:

| Claim | Current value | How it is checked |
| --- | ---: | --- |
| Shared reference configurations | **7 executable reference profiles** plus a separate teaching template | one executable YAML and developer README in each `adopters/configurations/<profile>/` directory; `DocumentationClaimsTest` rejects flat root YAML and missing catalog links |
| Adapter configurations | **25 deployable adapter configurations** | one executable YAML in each `adopters/configurations/adapters/<adopter>/` directory |
| Adapter tools | **116 tools** | summed from the same parsed YAML |
| 0.1 frozen inventory | 156 tools, 33 engines | checked against the shipped 2.0 configuration |
| Implemented 0.1 successor surface | 152 tools | exact frozen verdict accounting |
| New Kafka Admin capabilities | 2 tools | real AdminClient cluster and configuration reads |
| Full comparison surface, including Apache Burr (incubating) | 165 tools | exact `Coverage01VsTwoTest` assertion |
| Planned later | 5 engines | Arrow, Beam, Hudi, Kudu, and Ozone are typed sources for a later release |
| Less-guarded implemented 0.1 tools | 0 | class-order assertion |

The 25 deployable adapter configurations and 116 tools are governed in the current default build. Their external
compatibility state is below and in the claims matrix.

## Test boundaries: real software versus deterministic fixtures

No production configuration selects a test-double backend. Non-production fixtures stay in `src/test` and support narrow assertions only:

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

The detailed target-status source is
[`examples/adopters/README.md`](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/aegis-2.0-review-20261009/examples/adopters/README.md): it distinguishes 26 real two-door runs
from 6 configuration profiles whose product-stack run is next. The table below is
supplemental source/converter detail.

| Target | Recorded evidence | Qualification |
| --- | --- | --- |
| JDBC converter | 16 tests against Apache Derby 10.16.1.1, including result cap, error mapping and read-only behavior | recorded local converter behavior |
| ZooKeeper converter | 16 tests against ZooKeeper 3.9.3 through Curator TestingServer, including digest-authenticated owner-only node creation and an unreachable ensemble counted as an availability failure; AdminServer command names resolve through ZooKeeper's own registry | recorded local converter behavior |
| Flink 1.20.1 | `RealFlinkTest` starts a real Flink MiniCluster and verifies read, denied cancel, dry-run, approval and real cancellation against the canonical config | recorded local source behavior; rerun when Flink source, Flink version or config changes |
| Spark 4.0.1 | `RealSparkTest` starts a real local Spark driver and verifies History/driver-UI reads through the canonical config; standalone-master submission/kill remains unexercised | recorded local read-source behavior; standalone-master write path still needs its own run |
| Spark Connect 4.0.1 | `RealSparkConnectServerTest` starts a real Spark Connect server in a separate JVM and exercises reads plus a dry-run, independent approval and real destructive drop | recorded local source behavior; rerun when Spark Connect source, Spark version or config changes |
| Iceberg 1.10.1 REST catalog | `IcebergFrontDoorsTest`: real `RESTCatalogServer`, own host and Knox, recorded 2026-10-05 | recorded local source behavior; the release-candidate repeat is on the [roadmap](ROADMAP.md#next-release-candidate-evidence) |
| Kafka 3.9.1 and Connect | `KafkaFrontDoorsTest`: real KRaft broker and Connect worker, own host and Knox, recorded 2026-10-05; every default build also drives the `kafka-admin` source against a real broker | recorded local source behavior; the release-candidate repeat is on the [roadmap](ROADMAP.md#next-release-candidate-evidence) |
| Atlas, Cassandra, Hive, Impala, Paimon, Ranger | configuration and `AdapterConfigsTest` governance flow | product-stack runs are on the [roadmap](ROADMAP.md#engine-coverage-verification) |
| Arrow, Beam, Hudi, Kudu and Ozone | planned typed sources | [roadmap](ROADMAP.md#later-sources-and-engine-operations) |
| `aegis-state-postgres` | `PostgresStateStoreIT`: five tests for shared nonce and rate state, persistence, expiration, escaped scans, invalid rate requests, environment-named credentials, and signed-serial high-water rollback | [PostgreSQL validation evidence](evidence/POSTGRES-RDS-VALIDATION.md); the release-candidate repeat is on the [roadmap](ROADMAP.md#next-release-candidate-evidence) |
| Two gateway processes, one `postgres` namespace | `TwoGatewayProcessesPostgresIT` starts two `Main` JVMs against a real PostgreSQL 17.6 server: an approval requested on A is decided on B, the approved delete runs once on B, the replay on A is refused, and a rate window of 3 is one window across both | recorded local source behavior; see [two-gateway evidence](evidence/POSTGRES-TWO-GATEWAYS.md). Re-run from the exact candidate before a release claim |
| Knox extension | API-level tests include the real `AclsAuthz` route-scope check; on 2026-10-05 a dated `ZooKeeperFrontDoorsTest` run installed that run's `knox-bundle` jar (SHA-256 `0726C4CE71D23856F8FE9A58BE2C80E7FC7E4AE740ED70E8ECC8CBFB2617B780`) into stock Knox 3.0.0 `ext/` and ran the full scenario against real ZooKeeper 3.9.3 (claims matrix H10). Knox 3.1 (Jakarta) needs a separate build, see [ADR-002](adr/ADR-002-KNOX-3.1-JAKARTA-TARGET.md) |

Every current configuration follows the same enforcement chain. Each profile names the version that was exercised. Later runs are on the [roadmap](ROADMAP.md#next-release-candidate-evidence).

## Later work

Product-stack runs, typed sources, and the release-candidate repeat are on the [roadmap](ROADMAP.md).

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
  survives a restart of one process. A real second-JVM test confirms Derby's embedded lock refuses
  another writer on the same path. `postgres` shares nonces, pending approvals, rate windows, and
  signed-serial high-water state across processes (five provider tests and one two-gateway test were
  reported against a temporary managed PostgreSQL instance, and an earlier two-gateway run used PostgreSQL
  17.6); catalog pins and circuit breakers stay per process, so active-active claims are limited to
  those shared items.
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
