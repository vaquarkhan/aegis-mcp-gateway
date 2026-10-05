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
# Roadmap

What is **not** built, in the order it should be. This file exists so the documentation can be read as a
description of the code rather than of an intention: anything listed here must not be claimed anywhere
else, and [FEATURES.md](FEATURES.md) describes only what ships.

Nothing here is a placeholder in the code. Where a control is absent, the gateway fails closed rather
than pretending: an unimplemented auth mode refuses to start, an unconfigured control denies.

## Current release and Apache Incubator obligations

These are current release blockers for Aegis as an Apache Incubator project. Applicable ASF and
Incubator policy governs the release process; the technical evidence below does not replace that process.

| Item | Why it blocks | Where it is discussed |
| --- | --- | --- |
| Re-capture the manual Knox 3.0.0 cold-install transcript with the release candidate `knox-bundle` jar | The manual transcript predates the hook and OTLP work and used four jars. The automated two-door run with the current bundle in a stock Knox 3.0.0 process passed on 2026-10-05 for the ZooKeeper scenario on the local working tree (claims matrix H10; see [E2E validation](evidence/E2E-VALIDATION-2026-10-05.md)) | [evidence provenance note](evidence/knox-3.0.0-cold-install/README.md) |
| Dependency vulnerability scan over the SBOM | `mvn -Prelease package` writes a CycloneDX SBOM (`target/bom.json`), but nothing checks it against a vulnerability feed yet | root `pom.xml` `release` profile |
| Provenance scan (SCANOSS or FOSSA) over AI-assisted code | Required by the project's [AGENTS.md](../AGENTS.md) before the first Aegis release. The local SCANOSS fingerprint completed, but the external source-match API rate-limited the full scan and produced no result. | [release-assurance evidence](evidence/RELEASE-ASSURANCE.md#source-match-and-provenance-review) |
| Resolve gateway-bundle Derby advisory | Fresh OSV scan flags `org.apache.derby:derby:10.16.1.1` (`GHSA-rcjc-c4pj-xxrp`). The OSV-listed 10.16.1.2 coordinate was unavailable from Maven Central; the next Derby line needs a newer Java baseline. | [release-assurance evidence](evidence/RELEASE-ASSURANCE.md#gateway-finding) |
| Resolve supported Knox 3.0.0 host-stack advisories | The Knox bundle does not package Knox classes, but its fresh runtime-host SBOM has 36 advisories across 15 Knox-provided dependencies. Supporting the Knox path needs an upstream upgrade, Jakarta migration, or an explicit reviewed risk acceptance. | [release-assurance evidence](evidence/RELEASE-ASSURANCE.md#knox-host-findings) |
| Review distribution LICENSE, NOTICE, and SBOM metadata | The Knox bundle SBOM has no license metadata for provided `javax.servlet.jsp:jsp-api:2.1`; shaded JAR resource precedence is not a reviewed distribution-level notice process. | [release-assurance evidence](evidence/RELEASE-ASSURANCE.md#archive-and-legal-content-inspection) |
| Publish and verify private reporting contacts for security and conduct | `SECURITY.md` and `CODE_OF_CONDUCT.md` describe targets but no checkout-visible evidence confirms that the private channels are enabled or independent of a reported maintainer | root `SECURITY.md`, `CODE_OF_CONDUCT.md` |
| Run the real-engine suite in CI rather than by hand | `RealFlinkTest`, `RealSparkTest` and `RealSparkConnectServerTest` now run locally against current source; Iceberg and Kafka still skip when their externally started endpoints are absent. CI must provision every target and fail a required target job when its real test skips | [VALIDATION.md](VALIDATION.md) §2 |

## Work gated on the applicable software grant

Aegis is an Apache Incubator project. The following header transition remains gated only on the ASF
recording the applicable software grant. Do not run the transition or claim its output header applies
before that event is verified.

| Item | Why it matters | Where it is discussed |
| --- | --- | --- |
| Apply an ASF source-header transition after an ASF-recorded Software Grant | The current author headers remain in place until the ASF has recorded the applicable grant. After that, use the applicable ASF header and review the result. | `tools/asf-header-switch.py` is a guarded transition helper; Apache RAT then checks headers |

## Identity and credentials

| Item | State today | Note |
| --- | --- | --- |
| RFC 8693 token exchange for backend credentials | Done on the own host: `credentials.type: token-exchange` exchanges the verified caller JWT per call; verified against Keycloak 26.8.0 ([evidence](evidence/TOKEN-EXCHANGE-KEYCLOAK.md)) | Remaining: the Knox front door (no verified inbound token reaches Aegis there), other identity providers, and sender-constrained tokens (DPoP, mTLS-bound) |
| Vault-backed backend credentials | Done for secret lookup: `secrets.source: env, file or vault` (`SecretProviderFactory` SPI) supplies every named secret, including backend credentials, from a mounted directory or one KV v2 secret; validated against OpenBao 2.7.1 ([evidence](evidence/SECRETS-OPENBAO.md)) | Remaining: dynamic or leased Vault credentials and rotation without restart (values are read once at start-up); HashiCorp Vault itself and Vault namespaces were not exercised |
| Plugin source secrets through the gateway's environment lookup | JDBC and Spark Connect now receive `BuildContext.env` through the compatible `ToolSourceFactory.create(id, settings, context)` overload; existing two-argument third-party factories remain compatible | The JDBC real-Derby source test and Spark Connect factory test prove context resolution. Kafka Admin has no secret-name client-property setting yet; add `clientPropertiesEnv` when SASL/SSL property support is implemented |
| Backend errors reported inside an HTTP 200 body | `response.status.pointer` / `response.status.equals` validates the raw JSON document before `response.pointer` projection | ActiveMQ requires `/status == 200`; Ignite requires `/successStatus == 0`. `HttpSourcesAndWiringTest` covers success, failure, missing field and invalid JSON; retained real ActiveMQ/Ignite own-host + Knox success runs were refreshed |
| CIMD (client identity metadata document) | Not present | 0.1 shipped an incomplete implementation whose own documents disagreed about whether it worked. 2.0 left it out rather than claim it |
| SPIFFE workload identity on the own host | Done for X.509-SVIDs: `host.tls.clientAuth: require` with a trust bundle, and `identity.type: spiffe` mapping verified SPIFFE IDs to callers; real mutual TLS test with `keytool`-issued certificates (`SpiffeMutualTlsTest`) | Not exercised against SPIRE-issued SVIDs; no SPIRE Workload API client, JWT-SVIDs or bundle rotation without restart. Knox keeps its own client-certificate handling |
| Client certificate authentication on the gateway's own front door | Done: `host.tls.clientAuth: require` and `clientCaFile` (handshake refuses clients outside the bundle) | Trusted-proxy identity assertion is still not built in |
| Knox 3.1 (Jakarta) host extension | `aegis-knox3-extension` targets Knox 3.0.0 on `javax.servlet` 4.0.1 and `javax.ws.rs` 2.0. Knox trunk (3.1.0-SNAPSHOT) moved to `jakarta.servlet` 6.0 and `jakarta.ws.rs` 3.1 with Jersey 3 | A Jakarta build is needed to load in Knox 3.1. It is the planned target for aligning with Knox 3.1 identity work (federated OIDC and RFC 8693 exchange); 3.0.0 remains the current proof point. See [ADR-002](adr/ADR-002-KNOX-3.1-JAKARTA-TARGET.md) |

## Policy

| Item | State today | Note |
| --- | --- | --- |
| Cedar as a policy engine | Built-in rule policy plus OPA over HTTP, composed so all must allow | The `PolicyDecisionPoint` SPI is the seam; no engine change needed |
| Apache Ranger as a PDP | Not present | The obvious fit for a Hadoop-ecosystem deployment, and a reason the Knox conversation matters |

## Scale

| Item | State today | Note |
| --- | --- | --- |
| Shared catalog pins and circuit breakers across replicas | `state.type: postgres` shares approval and receipt nonces, pending approvals and rate windows across gateway processes (validated at provider level against Amazon RDS for PostgreSQL 18.3, [evidence](evidence/POSTGRES-RDS-VALIDATION.md), and with two gateway processes against PostgreSQL 17.6, [evidence](evidence/POSTGRES-TWO-GATEWAYS.md)). Catalog pins and breakers are per process; `derby-local` and `memory` are single-process | Until pins and breakers are shared, give every replica the same signed `catalog.manifest` (or the same operator pins) with `REQUIRE_PINS`, so every replica accepts exactly the same definitions, and treat breaker state as local to each replica |
| Load and soak measurement | None. No latency figure is claimed anywhere | Governance sits on the hot path, so a number should exist before anyone promises one |

## Coverage of the engines

| Item | State today | Note |
| --- | --- | --- |
| Kafka Admin source: `alter_config`, `reset_offsets`, `delete_records` | The source carries topic list/describe/create/delete plus cluster and redacted topic-configuration reads | All three writes need their own scenarios against the real KRaft broker that `ExampleConfigsTest` now starts in the default build; partition expansion is not implemented yet |
| Kafka `inspect_dlq` | Not present | Needs a consumer rather than the Admin client, plus a record cap so a poison topic cannot fill the model's context |
| Flink `upload_jar` | Not present | `POST /jars/upload` is `multipart/form-data`; the declarative body template is JSON. Needs either multipart support in the HTTP source or a small plugin |
| Flink SQL Gateway (`run_sql_readonly`, `run_sql_ddl_dml`) | Not present | Needs a session lifecycle (open, submit, poll), which is a source rather than one declarative call |
| Iceberg `expire_snapshots`, `remove_orphan_files`, `rewrite_data_files` | Not present | Engine procedures run by Spark or Flink, not REST catalog operations. They belong to that engine's source |
| Real-engine verification of the 25 deployable adapter configurations and 115 tools | Current default build proves converter behavior against real Apache Derby and ZooKeeper; historical reference-engine runs exist separately | Every target version needs a preserved real-engine run before its config is promoted to verified-current. The claim matrix is the release gate. |
| Complete endpoint audit | D8 corrected Hive, Calcite and ZooKeeper surfaces that had no real API. Arrow Flight and Beam JobService are identified as gRPC converter work. | Continue against each remaining target's own source, OpenAPI or registry, then execute the target. |
| Arrow Flight converter | Not present | Flight is gRPC, not HTTP. Implement and test a typed client source rather than restoring a facade. |
| Beam JobService converter | Not present | JobService is gRPC. Implement and test a typed client source rather than restoring a facade. |
| An Apache Livy source | Not present. 0.1's `submit_batch` and `kill_application` targeted Livy and YARN; the 2.0 Spark tools target Spark's own Master REST submission server | The only coverage 2.0 lost relative to 0.1 that is not deliberate ([COVERAGE-0.1-VS-2.0.md](COVERAGE-0.1-VS-2.0.md)). Livy's REST API is ordinary JSON over HTTP, so this is a configuration file rather than code |
| A YARN ResourceManager source | Not present, same reason | `killApplication` through the RM REST API is one declarative call; the work is in getting the identity and the Kerberos story right |

## Protocol

| Item | State today | Note |
| --- | --- | --- |
| MCP Tasks | Not present | Watch the specification rather than guess at it |
| `resources/subscribe` | Not implemented and not advertised | Subscriptions mean server-initiated traffic, which the current design deliberately refuses. Needs its own design, not a line of code |
| An A2A tool source | Done: `aegis-source-a2a`, one A2A 1.0 agent as one tool, verified against an `a2a-sdk` 1.2.1 agent through both front doors; still no A2A front door ([ADR-001](adr/ADR-001-A2A-AS-TOOL-SOURCE.md)) | Remaining: A2A 0.3 cards, card signature verification, streaming, task continuation |

## Observability

| Item | State today | Note |
| --- | --- | --- |
| Verify the OTLP exporter against a live collector | Verified against a local HTTP endpoint that asserts the document shape | The specification's hex id encoding differs from what several SDKs emitted, so a real collector run is the only way to settle it |
| Anchor the audit head hash outside the host | Built in as a file export: `audit.checkpoint` appends `{seq, head}` to a separate file on a record count, a timer, open and close; the gateway refuses to start on a journal shorter than or different at the last exported head, and `--verify-audit <file> --checkpoints <file>` checks every exported head (`AuditCheckpointTest`, `MainAndSettingsTest`) | Remaining: a network export (object store with object lock, a transparency log). The file export protects only as far as the checkpoint file's storage is out of the journal writer's reach |
| Audit seal and rotation | Append-only file | Operational rather than protective, but needed for long-lived deployments |

## Packaging

| Item | State today |
| --- | --- |
| Container image | `Dockerfile` (non-root, digest-pinned bases); built and started by CI on Linux, not built locally. No published image yet |
| Helm chart | `charts/aegis` (StatefulSet, render-time safety checks); linted, schema-checked and its rendered configuration started; not yet installed in a cluster |
| OpenAPI to configuration | `--openapi-draft` writes a reviewable draft, every tool `DESTRUCTIVE`, deny-all policy |
| Verifiable dry-run receipts (signed or Merkle) rather than HMAC | HMAC receipts bound to caller, tool and argument digest |

## Deliberately not planned

Not "later": these are out of scope by design, and the reasoning is in
[ARCHITECTURE.md](ARCHITECTURE.md) section 13. A server registry or catalog UI, federation and virtual
servers, an admin web UI, an observability stack, WebSocket transport, REST or gRPC proxying of non-MCP
APIs, model-token budgeting, and an A2A protocol front door.
