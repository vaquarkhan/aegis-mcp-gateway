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
# Claims matrix

This is the release-evidence index for Aegis 2.0. A product claim needs an implementation anchor, an
assertion anchor and a result classification. It is deliberately stricter than “a test exists.” A
contract route harness can prove configuration rendering and governance flow; it cannot certify a third-party
product release. A converter tested against an embedded Apache engine can prove converter behavior; it
cannot certify every database or client implementation.

## Status vocabulary

| Status | Meaning |
| --- | --- |
| **verified-current** | This working tree ran the named command against the named real or embedded-real target. |
| **verified-historical** | A preserved earlier run exists, but the current working tree has not repeated it. |
| **implemented-contract** | Code and governance flow are tested against a contract or protocol harness, not a target product. |
| **implemented-pending** | The source/configuration exists but no adequate target execution has happened. |
| **not shipped** | The capability is intentionally absent or remains on the roadmap. |

## Core control plane

| ID | Claim | Implementation and assertion | Evidence target | Status | Revalidate when |
| --- | --- | --- | --- | --- | --- |
| C01 | Every governed call follows the fixed control order and fails closed when a required control cannot run. | `GovernanceEngine`; `GovernanceEngineTest` and `ControlsTest` | deterministic engine tests | verified-current | chain, deny-code or SPI change |
| C02 | Approval and destructive-run receipts bind to the caller, the full action binding (gateway, tool, class, source, resources, argument digest, pinned definition digest) and one-time state; the requester (under any NFKC or case spelling) and delegated callers cannot approve; decisions are journaled before they take effect. | `ApprovalService`, `VrpService`, `Action.binding`; `GovernanceEngineTest`, `SeparationOfDutiesTest` (during review, five hand-made mutants that reverted identity folding, approval binding, receipt binding, evidence and decision journaling were each caught by these tests) | deterministic engine tests | verified-current | approval, receipt, binding or state-store change |
| C03 | A `TOOL` preview uses the actual READ preview tool, not the destructive tool being validated. | `GatewayFactory.SourceBuild`; `DryRunPreviewToolTest` | converter-shaped test source | verified-current | source binding or dry-run parser change |
| C04 | Input and output controls inspect nested data, with bounded parsing and redaction. | `SchemaValidator`, `ValueWalker`, `OutputGuard`; engine tests | deterministic engine tests | verified-current | JSON, screen or output-control change |
| C05 | Egress checks inspect declared and embedded HTTP(S) targets, classify DNS answers, and bound outbound reads. | `EgressGuard`, `BoundedHttp`; `ControlsTest` | deterministic engine tests | verified-current | egress or HTTP-client change |
| C06 | Audit intent is written before backend execution and records form a locally verifiable hash chain. | `HashChainFileAuditSink`; audit and engine tests | local file system | verified-current | audit format or store change |
| C07 | Resources, prompts and completion traverse governance as read operations when enabled. | `ContentGovernor`; content tests | deterministic engine tests | verified-current | MCP/content operation change |
| C08 | OTLP span export is bounded, asynchronous, carries no identity, arguments or results, and cannot slow or change a governed call. | `OtlpSpanExporter`; `OtlpSpanExporterTest` | local collector test endpoint that asserts the OTLP/JSON document shape | implemented-contract; no live OpenTelemetry Collector run | exporter or span attribute change |
| C09 | A valid JWT without a route's required OAuth scope is refused with 403 `insufficient_scope`, and published OAuth URLs are https or loopback only. | `OwnServerPerimeter`, `JwtVerifier.verifyWithScopes`, `ProtectedResourceMetadata`; `ScopeEnforcementTest`, `ProtectedResourceMetadataTest` (both modules) | deterministic tests and the JDK HTTP server | verified-current | perimeter, identity or OAuth metadata change |

## Source and adapter evidence

| ID | Claim | Implementation and assertion | Evidence target | Status | Revalidate when |
| --- | --- | --- | --- | --- | --- |
| S01 | The shipped inventory includes six named shared reference profiles, one separate teaching template, and 25 deployable adapter configurations with 115 tools. | shared and adapter YAML configs; `AdapterConfigsTest`; `DocumentationClaimsTest` | parsed configurations and layout contracts | verified-current | any shared profile, adapter config or tool change |
| S02 | The implemented 0.1 successor surface has 152 tools; the full comparison surface has 164, including ten new Apache Burr (incubating) tools and two Kafka Admin read capabilities; all 156 old tools have an implemented or explicitly absent verdict, and no implemented tool becomes less guarded. | `Coverage01VsTwoTest` | frozen 0.1 inventory and 2.0 YAML | verified-current | any config, verdict or 0.1 inventory change |
| S03 | JDBC metadata/query behavior and result bounds work against actual Apache software. | `aegis-source-jdbc`; `JdbcToolSourceTest` | Apache Derby 10.16.1.1 | verified-current | JDBC source change |
| S04 | ZooKeeper data-tree operations, optimistic versions, binary handling and path rules work against actual Apache software. | `aegis-source-zookeeper`; `ZooKeeperToolSourceTest` | ZooKeeper 3.9.3 through Curator TestingServer | verified-current | ZooKeeper source change |
| S05 | Configured ZooKeeper AdminServer command names exist in ZooKeeper's own command registry. | `ZooKeeperAdminCommandsTest` | ZooKeeper `Commands` registry | verified-current | ZooKeeper admin config change |
| S06 | Flink source governance runs against a real local Flink 1.20.1 MiniCluster on the current tree. | `RealFlinkTest` | Flink 1.20.1 MiniCluster | verified-current source behavior | Flink source, Flink version or config change |
| S07 | Spark read-source governance and Spark Connect source governance run against current local Spark 4.0.1 runtimes; Spark standalone submission/kill remains unexercised. | `RealSparkTest`, `RealSparkConnectServerTest` | Spark 4.0.1 driver UI; Spark Connect 4.0.1 local server | verified-current source behavior, limited to exercised paths | Spark, Spark Connect or source/config change |

| S08 | Iceberg catalog governance works against an official REST catalog server. | `IcebergFrontDoorsTest` (own host and Knox, rerun 2026-10-03), `RealIcebergRestCatalogTest` | Iceberg 1.10.1 RESTCatalogServer | verified-current (two-door run on the current tree) | any later source/config change |
| S09 | Kafka Admin and Connect governance work against a broker and worker. | `KafkaFrontDoorsTest` (own host and Knox, rerun 2026-10-03), `ExampleConfigsTest.kafkaConnectAndAdminClient` (default build) | Kafka 3.9.1 KRaft and Connect | verified-current (two-door run on the current tree) | any later source/config change |
| S10 | HTTP adapter configs are parsed, routed and governed, and target-version compatibility needs a retained real-engine run for each target. | `AdapterConfigsTest` | contract route harness | implemented-contract | target engine real run |
| S11 | Arrow, Beam, Hudi, Kudu and Ozone have no deployable 2.0 configuration. Their former HTTP routes were removed, and all 18 old tools have explicit absent verdicts until real typed sources exist. | absence from the `adopters/configurations/adapters/<adopter>/` profile directories; `Coverage01VsTwoTest.UNIMPLEMENTED_ENGINES` | source inspection and executable verdict accounting | deliberately absent | a real source for any of the five |

## Hosts and integrations

| ID | Claim | Implementation and assertion | Evidence target | Status | Revalidate when |
| --- | --- | --- | --- | --- | --- |
| H01 | The own-server host authenticates JWTs and governs a real local HTTP request. | `GatewayHostTest` | local JDK HTTP server and local JWKS fixture | verified-current | host or identity change |
| H02 | The Knox extension maps a Knox Subject and governs a proxied MCP call. | `KnoxExtensionTest` | Knox 3.0.0 API plus handler-independent test MCP server | verified-current API-level behavior | Knox extension or Knox version change |
| H03 | The recorded Knox cold-install transcript demonstrates end-to-end flow, but predates hook and OTLP work and used the earlier four-jar layout. | `docs/evidence/knox-3.0.0-cold-install/` | stock Knox 3.0.0, historical jars, test MCP backend | verified-historical | before any release or Knox claim broadening |
| H09 | Knox implementation code stays outside Aegis artifacts; the Knox and own-host adapters remain separate while both package the shared Aegis engine. | `KnoxPackagingBoundaryTest`; `tools/provenance/compare_knox_source.py` | POMs, production source and built jars; local Knox source at the revision in `knox-source-comparison.json` | verified-current structural boundary | host packaging, extension import or Knox baseline change |
| H04 | Iceberg REST integration uses the published Iceberg REST Catalog protocol, not a private endpoint model. | `iceberg.yaml`, `IcebergFrontDoorsTest` (rerun 2026-10-03), `RealIcebergRestCatalogTest` | Apache Iceberg REST Catalog API | verified-current | Iceberg config or catalog version change |

## Rules for changing this file

1. Add or update a row whenever an externally visible capability, count or compatibility claim changes.
2. Change **verified-current** to **verified-historical** after any relevant code/configuration change until
   the exact target run repeats.
3. Preserve the command output or raw transcript under `docs/evidence/` before promoting a row to
   **verified-current**.
4. A test double must be identified, never implied away. A source route, a deterministic response and a real
   product response are different evidence classes.
5. Update each referenced documentation sentence in the same change. `DocumentationClaimsTest` guards
   the inventory arithmetic; this matrix is the review surface for the remaining qualitative claims.

## MCP security additions

| ID | Claim | Implementation and assertion | Evidence target | Status | Revalidate when |
| --- | --- | --- | --- | --- | --- |
| H05 | Own-server route groups reject an identity before its MCP/admin body is buffered or dispatched. | `OwnServerSecuritySettings` (`host.routes`), `OwnServerPerimeter`, `AegisServer.serve`; `OwnServerPerimeterTest.routeAdmissionUsesIdentityAndRouteGroupBeforeAnyBodyIsNeeded`, `GatewayHostTest.ownHostRouteGroupsAndCorsRunBeforeMcpDispatch` | local JDK HTTP server | verified-current | perimeter or host binding change |
| H06 | Browser preflight uses exact origins and cannot advertise an origin the common MCP handler rejects. | `OwnServerSecuritySettings` (`host.cors`), `OwnServerPerimeter.preflight`, `AegisServer.preflight`; `OwnServerPerimeterTest.corsPolicyUsesExactOriginsAndHeaderNormalization`, `GatewayHostTest.corsPreflightCannotAdvertiseAnOriginTheMcpHandlerRejects` | local JDK HTTP server | verified-current | CORS or origin-check change |
| H07 | Own-server OAuth protected-resource metadata follows RFC 9728 and is bound to the JWT audience unless explicitly escaped. | `ProtectedResourceMetadata`, `OwnServerSecuritySettings` (`host.oauth`); `ProtectedResourceMetadataTest` (both modules), `OwnServerSecuritySettingsTest.oauthMetadataRequiresAudienceBindingUnlessExplicitlyEscaped` | local JDK HTTP server | verified-current | OAuth metadata or identity change |
| H10 | The current `knox-bundle` jar loads in a stock Apache Knox 3.0.0 gateway process and governs MCP calls end to end: Knox demo-LDAP authentication and `AclsAuthz` admit the route, Aegis denies a reader's destructive call, requires an independent approval and a dry-run receipt, executes the approved delete on a real ZooKeeper 3.9.3 server, and refuses the replay; both audit journals verify. | `ZooKeeperFrontDoorsTest` (OWN_HOST and KNOX); [retained run](../../examples/adopters/zookeeper/results/knox-transcript.log), knox-bundle SHA-256 `0726C4CE71D23856F8FE9A58BE2C80E7FC7E4AE740ED70E8ECC8CBFB2617B780` | stock Knox 3.0.0 (`bin/gateway.jar`) with the bundle in `ext/`, real ZooKeeper 3.9.3, 2026-10-05 | verified-current on the local source snapshot | any extension, bundle, Knox version or candidate change |
| H08 | The Knox bundle contains the Aegis engine, MCP, OTLP, configuration and PostgreSQL state modules, the PostgreSQL driver and the extension, and no Knox, servlet, JAX-RS, Jersey, Jackson or SnakeYAML class. | `aegis-knox3-extension/pom.xml` shade include list; `KnoxPackagingBoundaryTest.builtHostsShareAegisGovernanceButNotHostImplementations` reads the built `knox-bundle` jar | built jar in the default reactor | verified-current packaging | shade list or dependency change |
| S12 | `derby-local` persists durable local governance state for one process, with exact nonce and rate semantics. | `aegis-state-derby`, `StateStoreFactory`; `DerbyStateStoreTest`, `GatewayHostTest.derbyLocalStateProviderIsDiscoveredByTheOwnServer` | Apache Derby 10.16.1.1 embedded | verified-current, single replica only; the one-writer lock is Derby's documented behaviour and no test starts a second JVM | state store or Derby version change |
| S13 | A quarantined MCP/HTTP/plugin source contributes no callable tool and must identify its evidence reference. | `sources[].trust`, `GatewayFactory.SourceBuild.bind`; `GatewayFactoryTest.sourceTrustQuarantineHidesEveryToolUntilOperatorConfigActivatesTheSource` | deterministic configuration test | verified-current, static operator configuration | source binding change |
| S14 | PostgreSQL state-store behavior has been exercised against a real RDS PostgreSQL 18.3 instance: two providers share nonce/rate state; persistence, expiration, escaped scans, invalid rate requests, and environment-named credentials work. | `PostgresStateStoreIT`; [PostgreSQL RDS validation evidence](POSTGRES-RDS-VALIDATION.md) | temporary RDS PostgreSQL 18.3 | verified-current source behavior, not candidate or active-active certification | state provider, PostgreSQL version, candidate release, or deployment topology change |
| S15 | Two gateway processes sharing one `postgres` namespace share approvals, single-use approval and receipt nonces and rate windows: an approval requested on replica A is decided on B, the approved delete runs once, the replay on A is refused, and three calls spread over A and B fill one window of 3. | `TwoGatewayProcessesPostgresIT`; [two-gateway evidence](POSTGRES-TWO-GATEWAYS.md) | two `Main` JVMs, real PostgreSQL 17.6 on loopback | verified-current source behavior; not replication, failover or candidate certification | state provider, approval or rate code, PostgreSQL version, or candidate release change |
| S16 | With `audit.checkpoint` the journal exports its head on a record count, a timer, open and close; it refuses to reopen a journal that is shorter than or different at the last exported head without exporting anything, and `--verify-audit --checkpoints` reports truncation and regeneration that the chain alone accepts. | `HashChainFileAuditSink`, `AuditCheckpointVerifier`, `Main`; `AuditCheckpointTest`, `MainAndSettingsTest.auditCheckpointsAreWrittenFromConfigAndCheckedFromTheCommandLine`, `AuditCheckpointAndManifestConfigTest` | deterministic file tests | verified-current | audit sink, checkpoint format or CLI change |
| S17 | A signed `catalog.manifest` (Ed25519, verified before parsing) activates exactly its pinned tools in `REQUIRE_PINS`; a changed definition is quarantined, an edited manifest, another key or another gateway name stops start-up, and a conflicting inline pin is refused. The gateway jar generates keys, creates a manifest from a reviewed `GET /aegis/v1/catalog` reply, signs and verifies. | `SignedCatalogManifest`, `GatewayFactory`, `CatalogManifestCommands`; `SignedCatalogManifestTest`, `SignedCatalogManifestFlowTest` (real own host), `AuditCheckpointAndManifestConfigTest` | JDK Ed25519; real own host on loopback | verified-current; key custody is the operator's | manifest format, signature algorithm or pin semantics change |
| S18 | `secrets.source: file` and `vault` replace the environment for every named secret, strictly; a Vault token limited to one path reads one KV v2 secret, and 403, 404, a wrong token or an unreachable server stop start-up without echoing the token. | `FileSecretProvider`, `VaultKvSecretProvider`, `SecretProviderFactory`; `SecretProvidersTest`, `VaultKvSecretProviderIT`; [OpenBao evidence](SECRETS-OPENBAO.md) | real OpenBao 2.7.1 dev server on loopback | verified-current for OpenBao over HTTP; HashiCorp Vault, namespaces and TLS to the server not exercised | provider code, KV API or OpenBao/Vault version change |
| S19 | With `host.tls.clientAuth: require` and `identity.type: spiffe`, an X.509-SVID chaining to the bundle authenticates as its mapped actor (recorded with issuer `spiffe://<trust domain>`); no certificate or an untrusted one fails the handshake; a CA certificate, two URI SANs, another trust domain or an unmapped ID get 401. | `SpiffeIdentity`, `OwnServerSecuritySettings`, `AegisServer`; `SpiffeMutualTlsTest` (real TLS), `SpiffeIdentityTest` | JDK TLS with `keytool`-issued certificates (not SPIRE-issued) | verified-current for file-based SVIDs; SPIRE Workload API not exercised | identity, TLS host or SVID rule change |
| S20 | The `kafka-admin` source creates, describes, lists and deletes topics on a real Kafka 3.9.1 KRaft broker in the default build; a dry run is the broker's own `validateOnly` check, creates nothing, and an impossible replication factor is refused by the broker. | `KafkaAdminToolSource`; `ExampleConfigsTest.kafkaConnectAndAdminClient` with `RealKafkaBroker` | real broker in its own JVM from `aegis-validation-kafka-server` | verified-current in every default build | Kafka version or kafka-admin source change |
| G01 | `hooks[].type: project-governance` enforces evidence references for configured high-impact actions and denies protected resources. | `ProjectGovernanceGuardrail`; `ProjectGovernanceGuardrailTest`, and `ApacheProjectGovernanceProfileTest` for the profile build and tool visibility | deterministic hook tests; profile built in a temporary directory | verified-current | guardrail setting, hook or profile change |
| S21 | `credentials.type: token-exchange` gives each caller a per-user backend token by RFC 8693, never forwards the inbound token, never falls back, and keeps tokens out of the journal. | `TokenExchangeCredentialsTest`; `examples/adopters/token-exchange/keycloak_e2e.py` ([evidence](TOKEN-EXCHANGE-KEYCLOAK.md)) | Keycloak 26.8.0 standard token exchange, own host | verified-current (own host); not supported in Knox | credential provider, host binding or Keycloak version change |
| S22 | The `a2a` source offers an A2A 1.0 agent as one governed tool, applies the source credential, and enforces the card endpoint origin rule. | `A2aToolSourceTest`; `A2aAgentFrontDoorsTest` | agent on the official `a2a-sdk` 1.2.1, own host and Knox 3.0.0 | verified-current | a2a source, A2A protocol or SDK version change |
| S23 | `--openapi-draft` produces a configuration that loads, with every tool `DESTRUCTIVE` and a deny-all policy, from the published Iceberg 1.10.1 REST document; a reviewed tool reaches the real catalog with the document's schema enforced. | `OpenApiDraftTest`, `OpenApiDraftCommandTest`, `OpenApiDraftIcebergTest` | Iceberg 1.10.1 `RESTCatalogServer` | verified-current | generator or schema validator change |
| S24 | A remote schema `$ref` is never fetched and denies the call; local `$ref`s are enforced and cycles are bounded. | `SchemaRefTest` | deterministic | verified-current | schema validator change |
| G02 | The reference PII, secrets and change-window hooks refuse before the backend sees the call, withhold a result carrying a card number, and a stopped hook makes the gateway deny. | `adopters/hooks/test_hooks.py`; `adopters/hooks/e2e_gateway.py` | gateway jar as a process, CPython `http.server` backend | verified-current | hook contract or hook service change |
| H11 | The Helm chart renders valid Kubernetes 1.33 manifests, refuses unsafe values, and its rendered configuration starts the gateway with the StatefulSet's own arguments. | `charts/aegis/ci/start_rendered.py`; helm lint, kubeconform | Helm 4.3.0, kubeconform 0.8.0; no cluster | verified-current for rendering; image build and cluster install not run locally | chart or Dockerfile change |
