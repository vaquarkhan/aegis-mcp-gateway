<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Adopter real-engine status (2026-10-05)

Companion to [`examples/adopters/README.md`](../../examples/adopters/README.md) and
[CLAIMS-MATRIX.md](CLAIMS-MATRIX.md). **Contract support** (AdapterConfigsTest) is not the same as a
real two-door run.

## Real two-door verified (kept results)

| Adopter | Engine | Evidence |
| --- | --- | --- |
| ActiveMQ | 6.3.2 | `examples/adopters/activemq/results/` |
| BookKeeper | 4.18.1 | `examples/adopters/bookkeeper/results/` |
| Burr | 0.42.0 | `examples/adopters/burr/results/` |
| Calcite / Avatica | 1.42.0 / 1.28.0 | `examples/adopters/calcite/results/` |
| CouchDB | 3.4.2 Docker | `examples/adopters/couchdb/results/` |
| Pinot | 1.2.0 Docker QuickStart | `examples/adopters/pinot/results/` |
| Existing MCP server | MCP SDK + filesystem | `examples/adopters/existing-mcp-server/results/` |
| Flink | 1.20.1 | `examples/adopters/flink/results/` |
| Flume | 1.11.0 | `examples/adopters/flume/results/` |
| **Hadoop HDFS** | 3.2.1 Docker (bde2020 NN+DN) | `examples/adopters/hadoop/results/` (new) |
| **HBase** | REST via `dajobe/hbase` | `examples/adopters/hbase/results/` (new) |
| Iceberg REST | 1.10.1 | `examples/adopters/iceberg/results/` |
| Ignite | 2.18.0 | `examples/adopters/ignite/results/` |
| Kafka + Connect | 3.9.1 | `examples/adopters/kafka/results/` |
| **NiFi** | 1.28.1 Docker (HTTP + revision) | `examples/adopters/nifi/results/` (new) |
| Pulsar | 4.0.13 | `examples/adopters/pulsar/results/` |
| Solr | 9.10.1 | `examples/adopters/solr/results/` |
| Spark | 4.0.1 | `examples/adopters/spark/results/` |
| Spark Connect | 4.0.1 | `examples/adopters/spark-connect/results/` |
| ZooKeeper | 3.9.3 | `examples/adopters/zookeeper/results/` |
| Spark+Iceberg tutorial | Spark Connect + Iceberg | `examples/adopters/spark-iceberg/results/` |
| A2A | a2a-sdk 1.2.1 | front-door test; setup via `examples/adopters/a2a/setup.ps1` |

Each verified row exercises READ, outsider deny, DESTRUCTIVE/MUTATE approval + dry-run (as applicable),
engine side-effect checks, own host and Knox, and verifiable audit journals.

## In progress / not yet kept

FrontDoors tests exist under `examples/aegis-validation` and are wired in
`examples/adopters/run-example.ps1`. They are **not** verified-current until a green Keep run lands
results under `examples/adopters/<name>/results/`.

| Adopter | Status |
| --- | --- |
| Airflow | Test present; standalone auth + DAG seed fixes in flight |
| Storm | Test present; UI boots; topology jar fetch from Apache distro in flight |
| Druid | Test present; nano-quickstart entrypoint override in flight |
| Doris | Test present (`apache/doris:all-in-one-4.1.3`); run pending/in progress |
| Superset | Test present (`apache/superset:4.0.2`); run pending/in progress |

## Still blocked on full product stacks

| Adopter | Blocker for a real two-door run on this Windows host |
| --- | --- |
| Atlas | Atlas + Kafka + Solr + HBase stack |
| Cassandra | Cassandra Sidecar + cluster (profile targets Sidecar REST, not CQL) |
| Hive | HiveServer2 JDBC + `hive-jdbc` on gateway classpath (not an HTTP API) |
| Impala | Coordinator debug UI + Hadoop stack |
| Paimon | REST catalog server + warehouse storage |
| Ranger | Ranger Admin + DB |

Adding any of the above as **verified-current** requires a FrontDoors test that starts the real product,
seeds data outside Aegis, runs `GovernedFlow` through both doors, and keeps results under
`examples/adopters/<name>/results/`. Fabricating transcripts without that run is not allowed.

## How to re-run a verified adopter

```powershell
$env:AEGIS_KNOX_HOME = 'C:\knox\knox-3.0.0'
./examples/adopters/run-example.ps1 hadoop -Keep
./examples/adopters/run-example.ps1 nifi -Keep
./examples/adopters/run-example.ps1 hbase -Keep
```
