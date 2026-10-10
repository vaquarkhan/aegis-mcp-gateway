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

Where Aegis, the MCP governance gateway, goes next. [FEATURES.md](FEATURES.md) lists what ships today.
Every capability that is not in FEATURES.md appears here once: either as planned work (Now, Next,
Later) or under [Not planned](#not-planned), with the reason. Release-process work (source-match scan,
licensing review, signing and publishing) is tracked in
[release assurance](evidence/RELEASE-ASSURANCE.md#release-process-items).

## Now: shared trust state

| Item | What it adds | Builds on |
| --- | --- | --- |
| Shared catalog pins on PostgreSQL | Every replica accepts exactly the same tool and prompt definitions, including definitions learned at first sight | `state.type: postgres` ([PostgreSQL state](POSTGRES-STATE.md)) |
| Egress for Kafka broker metadata, JDBC and ZooKeeper sources | Validate Kafka listener addresses advertised after the permitted bootstrap connection, and the JDBC URL host and ZooKeeper connect string, against the egress boundary before their clients use them | `egress`, Kafka client integration, `ToolSource.configuredEndpoints` |

The server-catalog trust anchor, rollback protection for signed files, the offline policy test, egress
for the Kafka Admin bootstrap endpoints and Spark Connect source, the step-up challenge for tool scopes and the subject
check on issued JWTs have shipped; see [FEATURES.md](FEATURES.md) and the [changelog](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/aegis-2.0-review-20261009/CHANGELOG.md).

## Next: evidence, identity and operations

| Item | What it adds | Builds on |
| --- | --- | --- |
| Off-host audit anchoring | Network export of audit checkpoint heads to object storage with object lock, or to a transparency log | `audit.checkpoint` |
| Reference SIEM listener | A `DecisionListener` that ships every decision to syslog or OTLP logs | decision listeners, OTLP exporter |
| Audit journal seal and rotation | Sealed, rotated journal segments that still verify end to end | hash-chained journal |
| Live OpenTelemetry Collector run | Trace export confirmed against a real collector | OTLP span exporter |
| Sender-constrained tokens | DPoP or mTLS-bound access tokens on the own host | JWT identity, mutual TLS |
| SPIRE integration | Workload API client, JWT-SVIDs and trust-bundle rotation without restart | SPIFFE X.509-SVID identity |
| Trusted-proxy identity assertion | Caller identity accepted from a configured, authenticated proxy | identity resolver |
| Dynamic secrets | Leased Vault credentials, rotation without restart, HashiCorp Vault namespaces | `secrets.source: vault` |
| Kafka Admin client security | SASL and SSL client properties with secrets referenced by name (`clientPropertiesEnv`) | `kafka-admin` source |
| Verifiable dry-run receipts | Signed or Merkle receipts in addition to HMAC receipts | dry-run receipts |
| Load and soak benchmarks | Published latency and throughput figures for the governance chain | own host, PostgreSQL state |
| Containment guide for stdio servers and plugins | A deployment profile for child processes and plugins: dedicated OS user, container limits, network policy | stdio and plugin sources |
| Published container image and cluster install | A registry image and a Helm install validated in a Kubernetes cluster | `Dockerfile`, `charts/aegis` |

## Next: Apache Knox host

| Item | What it adds | Builds on |
| --- | --- | --- |
| Knox 3.1 (Jakarta) extension | The `AEGIS_MCP` service loads in Knox 3.1 on `jakarta.servlet` 6.0 and `jakarta.ws.rs` 3.1 | Knox 3.0.0 extension, [ADR-002](adr/ADR-002-KNOX-3.1-JAKARTA-TARGET.md) |
| Token exchange inside Knox | Per-caller backend credentials once Knox hands the service a verified inbound token | token exchange (F34) |

## Later: protocol

| Item | What it adds | Builds on |
| --- | --- | --- |
| MCP Tasks extension | Governed long-running tool calls, following the specification as it settles | `tools/call`, MRTR |
| SSE resumability | Resumed Streamable HTTP responses after a dropped connection | Streamable HTTP transport |
| `x-mcp-header` (SEP-2243) | Header-carried request metadata | MCP request routing |
| `resources/subscribe` | Governed resource subscriptions, with their own design for server-initiated traffic | typed resources |
| Client ID metadata documents (CIMD) | Client identity metadata for OAuth clients | OAuth protected-resource metadata |
| A2A sources: 0.3 cards, card signatures, streaming, task continuation | Wider A2A agent coverage as governed tools | `a2a` source (F35) |

## Later: policy engines

| Item | What it adds | Builds on |
| --- | --- | --- |
| Cedar | Cedar policies composed with the built-in rules | `PolicyDecisionPoint` SPI |
| Apache Ranger as a policy decision point | Ranger policies for Hadoop-ecosystem deployments | `PolicyDecisionPoint` SPI |

## Later: sources and engine operations

| Item | What it adds | Builds on |
| --- | --- | --- |
| Kafka Admin writes | `alter_config`, `reset_offsets`, `delete_records` and partition expansion | `kafka-admin` source |
| Kafka dead-letter inspection | `inspect_dlq` through a consumer, with a record cap that protects the model's context | `kafka-admin` source |
| Flink jar upload | `upload_jar` through multipart support in the HTTP source or a small plugin | `http` source |
| Flink SQL Gateway | `run_sql_readonly` and `run_sql_ddl_dml` with an open, submit and poll session lifecycle | plugin source SPI |
| Iceberg table maintenance | `expire_snapshots`, `remove_orphan_files` and `rewrite_data_files` as Spark or Flink procedures | Spark Connect, Flink sources |
| Arrow Flight source | A typed gRPC Flight client | plugin source SPI |
| Beam JobService source | A typed gRPC JobService client | plugin source SPI |
| Apache Livy and YARN ResourceManager profiles | Batch submission and application kill as declarative HTTP tools, once the identity and Kerberos model is chosen | `http` source |
| Airflow 3.x profile | The `/api/v2` surface of the Airflow 3 api-server | `airflow` profile (Airflow 2.x) |
| Hudi, Ozone and Kudu | Engine procedures through Spark or Flink, or a versioned REST profile when the product publishes one | Spark Connect, Flink, `http` source |

## Engine coverage verification

Each profile below already ships its governance configuration and contract tests. The planned work is a
retained real-engine run through both front doors. Done means a green `<Engine>FrontDoorsTest`, results
kept under `examples/adopters/<engine>/results/`, a regenerated tutorial, and the catalog row marked
**passed** ([adopter status](evidence/ADOPTER-REAL-ENGINE-STATUS.md)).

| Engine | What the run needs |
| --- | --- |
| Hive | HiveServer2 in Docker, the full HS2 client jars on both classpaths, a seeded table (the profile is `type: jdbc`) |
| Paimon | A Paimon REST catalog and warehouse, the same shape of work as the Iceberg REST run |
| Cassandra | Cassandra Sidecar with a cluster (the profile is Sidecar HTTP) |
| Ranger | Ranger Admin with its database and user bootstrap |
| Impala | An Impala coordinator; SQL can move to the `jdbc` source |
| Atlas | Atlas with Kafka, Solr and HBase |

## Next: release-candidate evidence

The validation report records the runs already completed. This pass repeats them on the release candidate and finishes the distribution work around them. Product-stack runs for Hive, Paimon, Cassandra, Ranger, Impala, and Atlas are in the table above. Arrow Flight, Beam JobService, Hudi, Kudu, and Ozone are under [Later: sources and engine operations](#later-sources-and-engine-operations).

| Item | What it adds |
| --- | --- |
| Two-door repeat on the release candidate | Flink, Spark, Spark Connect, Iceberg, Kafka, and the Knox front-door set on the candidate build |
| Security-suite repeat on the release candidate | The S1 to S31 selection on the candidate build |
| PostgreSQL on the release candidate | Provider and two-gateway runs against the deployment you will ship |
| OpenBao, Vault, and Keycloak on the release candidate | Secret-provider and token-exchange runs on the candidate build |
| Source-match review | An external source-match scan with the raw results and a human disposition |
| Distribution LICENSE and NOTICE | A reviewed distribution notice, including the Knox-provided `jsp-api` license metadata |
| Dependency advisory review | A recorded disposition for the Derby dependency and the Knox 3.0.0 host |

## Not planned

These stay out of Aegis by design. The longer reasoning is in [ARCHITECTURE.md](ARCHITECTURE.md)
section 13.

| Item | Reason |
| --- | --- |
| MCP server registry or catalog UI | Discovery has its own lifecycle and access model. Aegis governs the sources declared in its configuration; the signed `serverCatalog` admits or contains only those, at startup |
| Admin web UI | The admin surface is a small JSON API behind approver groups; a UI is a separate deployable |
| Run-time admin route that changes the catalog or contains a server | Changes go through signed files read at startup, so no network caller can widen, bypass or replace them |
| OAuth authorization server, client registration or consent screen | The identity provider issues tokens; Aegis verifies them |
| Federation and virtual servers | Every extra exposure path is another path the policy has to cover |
| Shared circuit breakers | Each replica protects its own view of a backend |
| Data loss prevention product | AFTER-phase hooks and the reference PII and secrets guards are the extension point for a DLP service |
| Built-in prompt-injection model | Typed resources, policy and independent approval carry the control; a classifier can run as a hook |
| Discovery of MCP servers outside the gateway's path | Network and endpoint inventory tools own that |
| Observability stack | Prometheus metrics, OTLP spans and decision listeners feed the stack a deployment already runs |
| WebSocket transport | Streamable HTTP and stdio are the MCP transports Aegis serves |
| REST or gRPC proxying of non-MCP APIs | Aegis governs MCP actions |
| Model-token budgeting | Rate limits and budgets count governed calls, not model tokens |
| A2A front door | A2A agents are governed as tool sources ([ADR-001](adr/ADR-001-A2A-AS-TOOL-SOURCE.md)) |
