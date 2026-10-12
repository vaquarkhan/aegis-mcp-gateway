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
# Aegis 2.0 adopter catalog

This directory contains **25 deployable adapter configurations and 116 tools**. Each configuration now has
one named directory, one executable YAML profile and one developer README:

```text
adopters/configurations/adapters/
  hive/
    hive.yaml       executable Aegis configuration
    README.md       backend surface, source choice, evidence and developer notes
```

The YAML is product configuration, not a code-generation input. It defines the source, tool surface,
classes, typed resources, policy, approval groups, egress controls and evidence location. The README
explains why the selected source type is correct and what must be verified before deployment.

## Layout contract

Every directory immediately under this catalog must contain:

1. `<adopter>/<adopter>.yaml`, the executable profile;
2. `<adopter>/README.md`, the developer guide for that profile.

`AdapterConfigsTest` and `Coverage01VsTwoTest` discover profiles through this layout. A flat YAML at
this catalog root, a mismatched name, or a missing README fails the test inventory. Do not add a
project-specific 0.1-style Java adapter when a declarative profile or existing generic typed source
already supports the real backend protocol.

## Select the right source type

| Backend surface | 2.0 source design |
| --- | --- |
| Stable HTTP API | `type: http` in the adopter YAML |
| JDBC protocol | `type: jdbc` plus the generic `aegis-source-jdbc` module |
| ZooKeeper data tree | `type: zookeeper` plus `aegis-source-zookeeper` |
| Kafka broker protocol | typed Kafka Admin source |
| Spark Connect gRPC | typed Spark Connect source |
| New non-HTTP protocol | a focused `ToolSourceFactory`, with real target evidence before release |

A source type is a protocol decision, not a label. Hive and Calcite use JDBC. ZooKeeper uses its client protocol for znodes and HTTP for the AdminServer command surface.

Arrow, Beam, Hudi, Kudu, and Ozone are planned typed sources for a later release. They are not part of this catalog.

## Run an adopter

Each `<adopter>.yaml` in this catalog is the profile an operator edits. Its `baseUrl` or JDBC URL is a
service hostname such as `airflow-webserver` or `hiveserver2`. That name does not resolve on a laptop, and
the file has no `host:` listener, so starting the gateway on it does not give an IDE a port to call and
does not start the engine.

A profile that has a kept two-door run also has these files. Copy them; do not point the IDE at the engine.

| What | Where it already is | Where you put your copy |
| --- | --- | --- |
| Own-host gateway config | `examples/adopters/<adopter>/aegis/own-host.yaml` | a file you own, outside git, for example `aegis-<adopter>.local.yaml` |
| Knox gateway config | `examples/adopters/<adopter>/aegis/knox-aegis.yaml` | `$AEGIS_KNOX_HOME/conf/aegis/<adopter>.yaml` |
| Knox topology | `examples/adopters/<adopter>/aegis/knox-topology.xml` | `$AEGIS_KNOX_HOME/conf/topologies/aegis-<adopter>.xml` |
| Aegis Knox extension | `aegis-knox3-extension/target/aegis-knox3-extension-2.0.0-SNAPSHOT-knox-bundle.jar` | `$AEGIS_KNOX_HOME/ext/` |
| Engine start command | `examples/adopters/<adopter>/HOW-TO-RUN.md`, section 1 | run it yourself; the published host port in that file is the free port from the kept run |
| IDE connection | `adopters/tutorials/<adopter>/ide/` | the editor path in the table below |
| Walkthrough | `docs/tutorials/<adopter>.md` | read it; it is the same scenario |

In your own-host copy, set `host.listen.port` to `8080` (the kept file says `port: 0`, which picks a random
port) and set the engine address to the host port you actually published. Set `AEGIS_SIGNING_KEY` to at
least 32 bytes, and set `AEGIS_VALIDATION_TOKEN_READER`, `AEGIS_VALIDATION_TOKEN_OPERATOR`,
`AEGIS_VALIDATION_TOKEN_APPROVER`, and `AEGIS_VALIDATION_TOKEN_OUTSIDER` to values of at least 16
characters. Those four tokens are the people `guest`, `sam`, `tom`, and `admin`. Also set every
credential variable the profile names (`usernameEnv`, `passwordEnv`, `userEnv`, `tokenEnv`). The YAML
stores the variable name, not the secret.

HTTP profiles start with the gateway jar. Hive, Calcite, and ZooKeeper also need the source plugin jars
named in that adopter's README and in its `HOW-TO-RUN.md`.

```powershell
# from the repository root, after: mvn -B -Preal-engines -DskipTests install
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar `
  --config <path to your copy of own-host.yaml>
```

The process stays up. The log line `Aegis gateway listening on http://127.0.0.1:8080/mcp` is the URL the
IDE uses. `examples/adopters/run-example.ps1 <adopter> -Keep` (or `run-example.sh <adopter> --keep`) is
the test: it checks both doors and then stops what it started, and `-Keep` overwrites the retained
evidence. Use the test to re-check. Use the copy above when you want a gateway an editor can call.

| Editor | Copy this file | To |
| --- | --- | --- |
| Cursor | `adopters/tutorials/<adopter>/ide/cursor/mcp.json` | `.cursor/mcp.json` |
| VS Code | `adopters/tutorials/<adopter>/ide/vscode/mcp.json` | `.vscode/mcp.json` |
| VS Code through Knox | `adopters/tutorials/<adopter>/ide/vscode/mcp.knox.json` | `.vscode/mcp.json` |
| Kiro | `adopters/tutorials/<adopter>/ide/kiro/mcp.json` | `.kiro/settings/mcp.json` |

Replace `REPLACE_WITH_YOUR_TOKEN` with the reader token if the assistant should act as `guest`.

Knox, when you want that door: install it with `examples/adopters/knox/install-knox.ps1` or
`install-knox.sh`, set `AEGIS_KNOX_HOME`, copy the three Knox rows in the table, replace `${KNOX_HOME}`
inside the topology, set `gateway.port` to `8443` and `gateway.host` to `127.0.0.1` in
`conf/gateway-site.xml`, then from the Knox directory run `java -jar bin/knoxcli.jar create-master`,
`java -jar bin/knoxcli.jar create-cert --hostname localhost`, `java -jar bin/ldap.jar conf`, and
`java -jar bin/gateway.jar`. For Hive, Calcite, and ZooKeeper, also copy each jar listed under
"Plugin jars this run used" in `HOW-TO-RUN.md` into `$AEGIS_KNOX_HOME/ext/`. The kept
`results/knox-ext-plugins.log` marks which of those jars the test added.

Atlas, Cassandra, Impala, and Paimon have a profile in this catalog and no kept run, so they have no
`HOW-TO-RUN.md` and no `ide/` folder. Do not invent a server for them. Their READMEs say why.

## Inventory and developer entry points

| Adopter | Profile | Real backend surface | Source | Tools | Current evidence |
| --- | --- | --- | --- | ---: | --- |
| [ActiveMQ](activemq/README.md) | [`activemq.yaml`](activemq/activemq.yaml) | ActiveMQ Jolokia | http | 3 | both doors passed; `examples/adopters/activemq/` |
| [Airflow](airflow/README.md) | [`airflow.yaml`](airflow/airflow.yaml) | Airflow stable REST API | http | 5 | both doors passed against Airflow 2.9.3 |
| [Atlas](atlas/README.md) | [`atlas.yaml`](atlas/atlas.yaml) | Atlas v2 API | http | 4 | contract route test; no Atlas process in this tree |
| [BookKeeper](bookkeeper/README.md) | [`bookkeeper.yaml`](bookkeeper/bookkeeper.yaml) | BookKeeper HTTP API | http | 4 | both doors passed; `examples/adopters/bookkeeper/` |
| [Burr](burr/README.md) | [`burr.yaml`](burr/burr.yaml) | Apache Burr (incubating) tracking API | http | 10 | both doors passed against Burr 0.42.0 |
| [Calcite](calcite/README.md) | [`calcite.yaml`](calcite/calcite.yaml) | Avatica JDBC | jdbc | 6 | both doors passed against Avatica |
| [Cassandra](cassandra/README.md) | [`cassandra.yaml`](cassandra/cassandra.yaml) | Cassandra Sidecar | http | 4 | contract route test; profile paths are not the Sidecar API |
| [CouchDB](couchdb/README.md) | [`couchdb.yaml`](couchdb/couchdb.yaml) | CouchDB API | http | 4 | both doors passed against CouchDB 3.4.2 |
| [Doris](doris/README.md) | [`doris.yaml`](doris/doris.yaml) | Doris Frontend | http | 4 | both doors passed against Doris 4.1.3 |
| [Druid](druid/README.md) | [`druid.yaml`](druid/druid.yaml) | Druid APIs | http | 4 | both doors passed against Druid 28.0.1 |
| [Flume](flume/README.md) | [`flume.yaml`](flume/flume.yaml) | Flume monitoring surface | http | 2 | both doors passed; `examples/adopters/flume/` |
| [Hadoop WebHDFS](hadoop/README.md) | [`hadoop.yaml`](hadoop/hadoop.yaml) | WebHDFS | http | 4 | both doors passed against Hadoop 3.2.1 |
| [HBase](hbase/README.md) | [`hbase.yaml`](hbase/hbase.yaml) | HBase REST | http | 4 | both doors passed |
| [Hive](hive/README.md) | [`hive.yaml`](hive/hive.yaml) | HiveServer2 JDBC | jdbc | 6 | both doors passed against HiveServer2 4.0.1 |
| [Ignite](ignite/README.md) | [`ignite.yaml`](ignite/ignite.yaml) | Ignite REST | http | 4 | both doors passed; `examples/adopters/ignite/` |
| [Impala](impala/README.md) | [`impala.yaml`](impala/impala.yaml) | Impala coordinator | http | 4 | contract route test; no coordinator in this tree |
| [NiFi](nifi/README.md) | [`nifi.yaml`](nifi/nifi.yaml) | NiFi API | http | 5 | both doors passed against NiFi 1.28.1 |
| [Paimon](paimon/README.md) | [`paimon.yaml`](paimon/paimon.yaml) | Paimon REST catalog | http | 4 | contract route test; Apache ships no REST catalog server |
| [Pinot](pinot/README.md) | [`pinot.yaml`](pinot/pinot.yaml) | Pinot controller | http | 4 | both doors passed against Pinot 1.2.0 |
| [Pulsar](pulsar/README.md) | [`pulsar.yaml`](pulsar/pulsar.yaml) | Pulsar admin API | http | 6 | both doors passed; `examples/adopters/pulsar/` |
| [Ranger](ranger/README.md) | [`ranger.yaml`](ranger/ranger.yaml) | Ranger Admin public v2 | http | 4 | both doors passed against Ranger Admin 2.9.0 |
| [Solr](solr/README.md) | [`solr.yaml`](solr/solr.yaml) | Solr APIs | http | 4 | both doors passed; `examples/adopters/solr/` |
| [Storm](storm/README.md) | [`storm.yaml`](storm/storm.yaml) | Storm UI API | http | 4 | both doors passed against Storm 2.6.2 |
| [Superset](superset/README.md) | [`superset.yaml`](superset/superset.yaml) | Superset API | http | 4 | both doors passed against Superset 4.0.2 |
| [ZooKeeper](zookeeper/README.md) | [`zookeeper.yaml`](zookeeper/zookeeper.yaml) | AdminServer plus znode client protocol | http + zookeeper | 8 | both doors passed against ZooKeeper 3.9.3 |

## What the tests prove

`AdapterConfigsTest` loads all **25 deployable adopter profiles and 116 tools**. It verifies the
configuration surface, access classes, approval flow, dry-run flow, resource extraction, policy denials
and audit-chain integrity. HTTP profiles run through a contract route harness. That proves Aegis renders
and governs the configured operation; it does not establish that a target product release implements
that route.

The generic converter tests use actual Apache software rather than hand-written protocol fixtures:

- `JdbcToolSourceTest` executes the JDBC source against embedded Apache Derby.
- `ZooKeeperToolSourceTest` executes znode operations against a real embedded ZooKeeper server.
- `ZooKeeperAdminCommandsTest` resolves each configured `/commands/<name>` through ZooKeeper's own
  `Commands` registry.

[VALIDATION.md](../../../docs/VALIDATION.md) and
[evidence/CLAIMS-MATRIX.md](../../../docs/evidence/CLAIMS-MATRIX.md) are the authoritative evidence
records. A source, route harness or converter test is not target-product compatibility certification.
