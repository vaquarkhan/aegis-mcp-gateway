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

```powershell
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar `
  --config adopters/configurations/adapters/airflow/airflow.yaml
```

Before connecting to a target, replace the reference endpoint or connection string, credentials,
groups, protected-resource policy, egress rules and audit location. Set `AEGIS_SIGNING_KEY` to at least
32 random bytes. A target-version real-engine run is required before a compatibility claim.

## Inventory and developer entry points

| Adopter | Profile | Real backend surface | Source | Tools | Current evidence |
| --- | --- | --- | --- | ---: | --- |
| [ActiveMQ](activemq/README.md) | [`activemq.yaml`](activemq/activemq.yaml) | ActiveMQ Jolokia | http | 3 | contract route test |
| [Airflow](airflow/README.md) | [`airflow.yaml`](airflow/airflow.yaml) | Airflow stable REST API | http | 5 | contract route test |
| [Atlas](atlas/README.md) | [`atlas.yaml`](atlas/atlas.yaml) | Atlas v2 API | http | 4 | contract route test |
| [BookKeeper](bookkeeper/README.md) | [`bookkeeper.yaml`](bookkeeper/bookkeeper.yaml) | BookKeeper HTTP API | http | 4 | contract route test |
| [Burr](burr/README.md) | [`burr.yaml`](burr/burr.yaml) | Apache Burr (incubating) tracking API | http | 10 | route source checked; target run pending |
| [Calcite](calcite/README.md) | [`calcite.yaml`](calcite/calcite.yaml) | Avatica JDBC | jdbc | 6 | generic converter proven against real Derby; Avatica run pending |
| [Cassandra](cassandra/README.md) | [`cassandra.yaml`](cassandra/cassandra.yaml) | Cassandra Sidecar | http | 4 | contract route test |
| [CouchDB](couchdb/README.md) | [`couchdb.yaml`](couchdb/couchdb.yaml) | CouchDB API | http | 4 | contract route test |
| [Doris](doris/README.md) | [`doris.yaml`](doris/doris.yaml) | Doris Frontend | http | 4 | contract route test |
| [Druid](druid/README.md) | [`druid.yaml`](druid/druid.yaml) | Druid APIs | http | 4 | contract route test |
| [Flume](flume/README.md) | [`flume.yaml`](flume/flume.yaml) | Flume monitoring surface | http | 2 | contract route test |
| [Hadoop WebHDFS](hadoop/README.md) | [`hadoop.yaml`](hadoop/hadoop.yaml) | WebHDFS | http | 4 | contract route test |
| [HBase](hbase/README.md) | [`hbase.yaml`](hbase/hbase.yaml) | HBase REST | http | 4 | contract route test |
| [Hive](hive/README.md) | [`hive.yaml`](hive/hive.yaml) | HiveServer2 JDBC | jdbc | 6 | generic converter proven against real Derby; HiveServer2 run pending |
| [Ignite](ignite/README.md) | [`ignite.yaml`](ignite/ignite.yaml) | Ignite REST | http | 4 | contract route test |
| [Impala](impala/README.md) | [`impala.yaml`](impala/impala.yaml) | Impala coordinator | http | 4 | contract route test |
| [NiFi](nifi/README.md) | [`nifi.yaml`](nifi/nifi.yaml) | NiFi API | http | 5 | contract route test |
| [Paimon](paimon/README.md) | [`paimon.yaml`](paimon/paimon.yaml) | Paimon REST catalog | http | 4 | contract route test |
| [Pinot](pinot/README.md) | [`pinot.yaml`](pinot/pinot.yaml) | Pinot controller | http | 4 | contract route test |
| [Pulsar](pulsar/README.md) | [`pulsar.yaml`](pulsar/pulsar.yaml) | Pulsar admin API | http | 6 | contract route test |
| [Ranger](ranger/README.md) | [`ranger.yaml`](ranger/ranger.yaml) | Ranger service API | http | 4 | contract route test |
| [Solr](solr/README.md) | [`solr.yaml`](solr/solr.yaml) | Solr APIs | http | 4 | contract route test |
| [Storm](storm/README.md) | [`storm.yaml`](storm/storm.yaml) | Storm UI API | http | 4 | contract route test |
| [Superset](superset/README.md) | [`superset.yaml`](superset/superset.yaml) | Superset API | http | 4 | contract route test |
| [ZooKeeper](zookeeper/README.md) | [`zookeeper.yaml`](zookeeper/zookeeper.yaml) | AdminServer plus znode client protocol | http + zookeeper | 8 | znode converter proven against real ZooKeeper; command names registry-checked |

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
