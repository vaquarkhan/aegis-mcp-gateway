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
# Adoption guide: governing an Apache project's MCP surface

Aegis 2.0 is designed so that the project you govern changes **nothing**: no new dependency, no
code, no rebuild. You write one configuration file and pick a front door.

## Step 1: pick the source type

| Your project has... | Use | Example |
| --- | --- | --- |
| an MCP server (HTTP or stdio) | `type: mcp` | `adopters/configurations/existing-mcp-server/existing-mcp-server.yaml` |
| a REST API | `type: http` (declarative tools) | `adopters/configurations/flink/flink.yaml`, `adopters/configurations/spark/spark.yaml`, `adopters/configurations/iceberg/iceberg.yaml`, `adopters/configurations/kafka/kafka.yaml` (Kafka Connect), plus the REST entries in the **25 deployable adapter configurations and 116 tools** under `adopters/configurations/adapters/` |
| only a Java client library or a non-REST protocol | a `ToolSourceFactory` plugin | `adopters/configurations/kafka/kafka.yaml` with `type: kafka-admin`; the JDBC and ZooKeeper converter sources |

## Step 1a: govern an existing MCP server

Keep the server that already owns your project’s capability, names, schemas, and semantics. Aegis adds the governance layer without requiring a code change, fork, or plugin in that project: caller identity, typed-resource policy, approvals, receipts, egress, rate/budget controls, screening, and hash-chained evidence run at the MCP boundary.

```yaml
sources:
  - id: engine-mcp
    type: mcp
    transport: http
    url: http://engine-mcp:9000/mcp
    era: AUTO                 # 2026-07-28 first, then the supported 2025 handshake
    namePrefix: "engine_"
    credentials: {type: bearer, tokenEnv: ENGINE_MCP_TOKEN}
    tools:
      list_jobs: {class: READ}
      get_job: {class: READ, resources: [{type: "engine:job", pointer: /jobId}]}
      delete_job: {class: DESTRUCTIVE, resources: [{type: "engine:job", pointer: /jobId}]}
```

The full shipped configuration is [`adopters/configurations/existing-mcp-server/existing-mcp-server.yaml`](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/{{ site.github.source.branch }}/adopters/configurations/existing-mcp-server/existing-mcp-server.yaml). Aegis discovers downstream tool schemas and puts its fixed governance chain in front of every call. After durable `ALLOW_INTENT`, the downstream server receives either the configured static gateway service credential or an exchanged backend-audience token. It never receives the inbound caller token.

| Your server | Aegis configuration | Result |
| --- | --- | --- |
| Streamable HTTP, 2026-07-28 | `transport: http`, `era: AUTO` or the current revision | Stateless MCP requests without an initialize/session exchange |
| Streamable HTTP, 2025 revisions | `transport: http`, `era: AUTO` or an explicit 2025 revision | Aegis performs initialize and carries the caller-bound session |
| stdio subprocess | `transport: stdio`, `command: [...]` | Aegis launches and supervises the configured local process |

The operator makes three project-specific decisions:

1. **Classify every exposed tool** as `READ`, `MUTATE`, or `DESTRUCTIVE`. An unlisted tool defaults to `DESTRUCTIVE`; `unlistedTools: HIDE` removes it from discovery until it is classified.
2. **Name governed resources** with JSON pointers, such as `engine:job` from `/jobId`. That changes a broad permission to call a tool into a policy that can constrain a specific object.
3. **Choose the source admission state and pins.** Use `catalog.mode: REQUIRE_PINS` for reviewed production definitions, or explicit TOFU only when the first observed definition is trusted. To fix which servers may start at all, add a signed `serverCatalog` ([configuration](CONFIGURATION.md#servercatalog)).

Aegis retains control when a downstream server is wrong or compromised: downstream annotations can raise a tool class but cannot lower the operator’s class; tool and prompt definition changes quarantine; results are rebuilt from specification fields; nested URL arguments still pass egress checks; and enabled prompts are pinned like tools.

A gateway does not improve an inherently broad tool. A free-form SQL tool remains a broad SQL tool, and a server with one credential remains bounded by that credential’s privileges. Use narrow tools, typed resources, read-only backend credentials where appropriate, and source-specific controls such as `sql-readonly`. Aegis also adds one process and one backend round trip; this repository makes no latency claim.

To try it, copy the shipped profile, set the source URL, classify tools and resources, provide `AEGIS_SIGNING_KEY` and the downstream service credential, then run the own-host executable or use the optional Knox host integration. If a tool cannot be governed accurately, record it as a gap rather than exposing a route the product does not serve or a misleading route.

## Step 1b: check whether your engine already ships a config

Aegis 0.1 had one Maven module per engine (`mcp-adapter-<engine>`). In 2.0, 24 usable 0.1
surfaces plus the new Apache Burr (incubating) integration are **25 deployable adapter folders and 116 tools** under
[`adopters/configurations/adapters/`](https://github.com/vaquarkhan/aegis-mcp-gateway/tree/{{ site.github.source.branch }}/adopters/configurations/adapters). Each folder contains a
same-named executable YAML profile and developer README. Most use
`type: http`; Hive and Calcite use the JDBC converter, and ZooKeeper combines its real AdminServer HTTP
commands with its client-protocol converter. Arrow, Beam, Hudi, Kudu and Ozone are explicit roadmap
sources, not product configuration, because their former HTTP routes did not exist.

| | |
| --- | --- |
| Messaging and streaming | ActiveMQ, Pulsar, Storm |
| Storage and tables | Hadoop HDFS, HBase, Cassandra, CouchDB, Ignite, BookKeeper, Paimon |
| Query and analytics | Hive, Impala, Calcite, Druid, Doris, Pinot, Solr, Superset |
| Pipelines and flow | Airflow, NiFi, Flume |
| Governance and coordination | Atlas, Ranger, ZooKeeper |
| Agent state and traces | Burr |

Each adopter directory contains its executable YAML profile and developer README:

```bash
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar \
  --config adopters/configurations/adapters/airflow/airflow.yaml
```

`AdapterConfigsTest` derives its inventory directly from the configurations: **25 deployable adapter
configurations and 116 tools** are parsed, governed, and checked against the applicable source
contract. Converter behaviour is additionally tested against real embedded Apache software (Derby for
JDBC and ZooKeeper for the data-tree client). The current real-engine compatibility status for every
adapter is the source of truth in [VALIDATION.md](VALIDATION.md). A configuration becomes
release-compatible for an engine version only after its real-engine row is green; until then it is a
reviewed, governed starting point. Set your own groups and `egress.allowCidrs`, and run the target
engine's verification command before promotion.

## Step 2: classify every tool

Classify each tool as READ, MUTATE or DESTRUCTIVE.

- A proxied tool you do not classify is DESTRUCTIVE, which means approval and a dry-run receipt
  are required. Set `unlistedTools: HIDE` to expose only the tools you list.
- Upstream MCP annotations can make a tool stricter, never looser.

## Step 3: name the governed resources

Give each tool that touches something named a resource rule:

```yaml
resources:
  - {type: "iceberg:table", pointers: [/namespace, /table]}   # -> iceberg:table:sales.orders
```

Policy rules and scopes then match `iceberg:table:sales.*` and similar patterns. A missing value is
a denial, never a wildcard.

## Step 4: groups, approvers, policy

```yaml
access:   {writeGroups: [ops], approverGroups: [stewards]}
approval: {keyEnv: AEGIS_SIGNING_KEY}
policy:
  rules:
    - {id: read,  groups: [users, ops], classes: [READ]}
    - {id: write, groups: [ops], classes: [MUTATE, DESTRUCTIVE]}
```

The default is deny. Rules with `effect: DENY` override allows. To use OPA as well, add
`policy.opa.url`; then both the rules and OPA must allow.

## Step 5a: run the gateway on its own server (Aegis's own security)

```yaml
host:
  listen: {host: 0.0.0.0, port: 8443}
  tls: {keystore: server.p12, keystorePasswordEnv: AEGIS_KEYSTORE_PASSWORD}
  identity:
    type: jwt
    jwt: {issuer: "https://idp.example.com", audience: aegis, algorithms: [RS256],
          jwksUri: "https://idp.example.com/.well-known/jwks.json", groupsClaim: groups}
```

```
AEGIS_SIGNING_KEY=... AEGIS_KEYSTORE_PASSWORD=... java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar --config aegis.yaml
```

MCP clients connect to `https://host:8443/mcp`. Approvers use `https://host:8443/aegis/v1/approvals`.

For a desktop client over stdio, set `host.stdio: {actor: me, groups: [...]}` and run with `--stdio`.

## Step 5b: run inside Apache Knox 3.0 (Knox's security)

1. Copy `aegis-engine`, `aegis-mcp`, `aegis-observability-otlp`, `aegis-config` and `aegis-knox3-extension` jars (or the single `knox-bundle` jar) into `<knox>/ext/`, then restart Knox. Optional jars for Derby state, the project-governance hook and plugin sources are listed in [KNOX-EXTENSION.md](KNOX-EXTENSION.md#install).
2. Add the service to a topology whose providers authenticate and assert groups:

```xml
<service>
  <role>AEGIS_MCP</role>
  <param><name>aegis.config</name><value>/etc/knox/conf/aegis/aegis.json</value></param>
</service>
```

3. Clients use `https://<knox>/gateway/<topology>/aegis-mcp/v1/mcp`. Approvals are under `.../aegis-mcp/v1/admin/approvals`.

Knox authenticates the caller (LDAP, KnoxSSO, JWT federation) and asserts groups. `AclsAuthz` can
gate who reaches the route at all. Aegis then governs each tool call. The `host:` section is
ignored in Knox mode. Use JSON, or YAML if the Knox classpath has snakeyaml (Knox 3.0.0 ships 2.4).
Details are in [KNOX-EXTENSION.md](KNOX-EXTENSION.md).

## Step 6: operate

- **Audit:** `java -jar aegis-gateway-...-all.jar --verify-audit audit/aegis.jsonl` verifies the hash chain.
- **Rug pulls:** `catalog.refreshSeconds` re-lists tools. Changed definitions are quarantined. An
  approver reviews the digest in `GET /aegis/v1/catalog`, then accepts exactly that digest with
  `POST /aegis/v1/catalog/<tool>/accept` and the single header
  `Aegis-Definition-Digest: sha256:...`. A changed digest is refused and must be reviewed again.
  With a signed `serverCatalog`, run-time acceptance is refused; re-sign the catalog instead.
- **Incidents:** a break-glass order signed with a separate key quarantines or revokes named servers
  for up to 24 hours at the next restart. It can only take capability away. See
  [break-glass MCP servers](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/{{ site.github.source.branch }}/examples/break-glass-mcp-servers/README.md) for four runnable examples.
- **Health:** `/healthz`, and `/readyz` (the gateway's own server). `.../aegis-mcp/v1/health` (Knox).

## What a project has to change

Nothing in its code. It may want to:

- give Aegis a service credential (for example a catalog token), referenced by `tokenEnv`;
- allow the gateway's network address in its own firewall.
## Durable state without an external database

The default `state.type: memory` is appropriate for development and one process that can lose pending
approvals on restart. For a durable one-node deployment, the own-server distribution includes the
Apache Derby provider:

```yaml
state:
  type: derby-local
  settings:
    path: state/aegis
```

This starts no database server and needs no separately installed database. Derby's embedded database lock is
part of the safety model: a second writable Aegis process using the same path fails to start instead of
creating independent approval and receipt state. Use one replica with this mode.

For several replicas, use `state.type: postgres` from `aegis-state-postgres` with the same `jdbcUrl` and
`namespace` on every replica (see [CONFIGURATION.md](CONFIGURATION.md#state)). Two gateway processes sharing
one PostgreSQL 17.6 namespace were run: an approval requested on one was decided on the other, the replay
was refused, and one rate window applied across both ([evidence](evidence/POSTGRES-TWO-GATEWAYS.md)). The
provider also has a recorded 2026-10-08 run of five provider tests and one two-gateway test against a
temporary managed PostgreSQL instance; the engine version was not retained
([evidence](evidence/POSTGRES-RDS-VALIDATION.md)). Catalog pins and circuit breakers stay per process, and
PostgreSQL replication and failover were not tested; rerun the integration profile against your target
database before you rely on it.

## Own-server perimeter policy

Use route groups when your own Aegis listener should match Knox's “may reach this service” boundary:

```yaml
server:
  allowedOrigins: [https://console.example.com]
host:
  routes:
    mcpGroups: [mcp-users]
    adminGroups: [mcp-admins]
  cors:
    allowedOrigins: [https://console.example.com]
    allowedMethods: [POST, DELETE, GET]
    allowedHeaders: [Authorization, Content-Type, MCP-Protocol-Version, Mcp-Method, Mcp-Name]
    maxAgeSeconds: 600
```

Route groups run before an MCP/admin request body is read. They do not replace tool policy: an admitted
`mcp-user` still needs the groups, scopes, policy decision, approval and receipt required for the exact
MCP action. Browser CORS is off unless exact origins are configured. Wildcards are refused.

## Source evidence and quarantine

A source can be withheld as one unit while its endpoint, version or evidence is under review:

```yaml
sources:
  - id: payments
    type: mcp
    trust:
      state: QUARANTINED
      evidence: docs/evidence/payments-2026-09-27.md
    url: https://payments.example.com/mcp
    tools: {}
```

A quarantined source contributes no callable tool. The evidence value is an operator-maintained reference;
Aegis does not fetch it on the request path. Restore `state: ACTIVE` only after the source evidence,
digest pins and target verification have been reviewed.
