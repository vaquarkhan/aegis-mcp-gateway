<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Adopter real-engine status (2026-10-05)

Companion to [`examples/adopters/README.md`](../../examples/adopters/README.md) and
[CLAIMS-MATRIX.md](CLAIMS-MATRIX.md). **Contract support** (AdapterConfigsTest) is not the same as a
real two-door run.

## What “100% verified adopters” means here

Aegis **governance** is complete for every shipped profile (fail-closed chain, contract harness).
**Target verification** means a kept own-host + Knox run against a real product process. That is an
engineering task only when a runnable surface exists (HTTP, JDBC driver + server, or a typed plugin
like Spark Connect). It is **not** closed by inventing REST routes, and it is **not** the same as
Apache Software Foundation release process work (see [ROADMAP.md](../ROADMAP.md)).

## Real two-door verified (kept results)

| Adopter | Engine | Evidence |
| --- | --- | --- |
| ActiveMQ | 6.3.2 | `examples/adopters/activemq/results/` |
| **Airflow** | 2.9.3 Docker standalone | `examples/adopters/airflow/results/` |
| BookKeeper | 4.18.1 | `examples/adopters/bookkeeper/results/` |
| Burr | 0.42.0 | `examples/adopters/burr/results/` |
| Calcite / Avatica | 1.42.0 / 1.28.0 | `examples/adopters/calcite/results/` |
| CouchDB | 3.4.2 Docker | `examples/adopters/couchdb/results/` |
| Pinot | 1.2.0 Docker QuickStart | `examples/adopters/pinot/results/` |
| **Doris** | 4.1.3 Docker all-in-one | `examples/adopters/doris/results/` |
| **Druid** | 28.0.1 Docker nano-quickstart | `examples/adopters/druid/results/` |
| Existing MCP server | MCP SDK + filesystem | `examples/adopters/existing-mcp-server/results/` |
| Flink | 1.20.1 | `examples/adopters/flink/results/` |
| Flume | 1.11.0 | `examples/adopters/flume/results/` |
| **Hadoop HDFS** | 3.2.1 Docker | `examples/adopters/hadoop/results/` |
| **HBase** | REST via `dajobe/hbase` | `examples/adopters/hbase/results/` |
| Iceberg REST | 1.10.1 | `examples/adopters/iceberg/results/` |
| Ignite | 2.18.0 | `examples/adopters/ignite/results/` |
| Kafka + Connect | 3.9.1 | `examples/adopters/kafka/results/` |
| **NiFi** | 1.28.1 Docker | `examples/adopters/nifi/results/` |
| Pulsar | 4.0.13 | `examples/adopters/pulsar/results/` |
| Solr | 9.10.1 | `examples/adopters/solr/results/` |
| Spark | 4.0.1 | `examples/adopters/spark/results/` |
| Spark Connect | 4.0.1 (gRPC plugin) | `examples/adopters/spark-connect/results/` |
| **Storm** | 2.6.2 Docker | `examples/adopters/storm/results/` |
| **Superset** | 4.0.2 Docker | `examples/adopters/superset/results/` |
| ZooKeeper | 3.9.3 | `examples/adopters/zookeeper/results/` |
| Spark+Iceberg tutorial | Spark Connect + Iceberg | `examples/adopters/spark-iceberg/results/` |
| A2A | a2a-sdk 1.2.1 | `examples/adopters/a2a/` |

## Remaining engineering (not ASF process)

These six still need a **real product process** on the Keep path. JDBC and Spark Connect help only
where the product’s real surface matches those protocols.

| Adopter | Honest surface already in tree | Closable with existing JDBC / Spark Connect? | What still blocks a Keep |
| --- | --- | --- | --- |
| **Hive** | `type: jdbc` + `HiveDriver` (fake HTTP `/sql` was removed) | **Yes — same JDBC source as Calcite/Derby.** | `apache/hive:4.0.1` HS2 Docker + beeline proven 2026-10-05. **No FrontDoors scaffold in tree.** Full Keep needs HS2 client jars on classpath, seed, retained results, tutorial. Tracked on [ROADMAP.md](../ROADMAP.md) |
| **Impala** | Coordinator **HTTP** debug UI tools today; SQL would be JDBC like Hive | **SQL yes (jdbc rewrite); cancel/status stay HTTP.** Spark Connect does not speak Impala | Full Impala (or JDBC endpoint) stack — **future roadmap** |
| **Paimon** | Declarative **HTTP** REST catalog (same pattern as Iceberg REST) | **Not JDBC/gRPC.** | Paimon REST catalog + warehouse Keep — **future roadmap** |
| **Cassandra** | Declarative **HTTP** Cassandra Sidecar | **Not JDBC** (profile is Sidecar REST, not CQL). CQL would be a different jdbc profile | Sidecar + cluster Docker Keep — **future roadmap** |
| **Ranger** | Declarative **HTTP** Admin API | **Not JDBC/gRPC** | Ranger Admin + DB Docker Keep — **future roadmap** |
| **Atlas** | Declarative **HTTP** Atlas v2 | **Not JDBC/gRPC** | Atlas + Kafka + Solr + HBase — **future roadmap** |

### Why Spark Connect does not “finish all”

`aegis-spark-connect` is a **typed Spark Connect client** (Spark’s own gRPC protocol). It is not a
generic gRPC proxy. Arrow Flight and Beam JobService are different protocols and remain **new typed
sources** on the roadmap — they were **removed as false HTTP facades** (defect closed), not left as
open bugs to revive.

## ASF / Incubator / release process (not this engineering backlog)

Provenance scans, software-grant header switches, published container registries, Helm install in a
shared ASF cluster, and private security/conduct channel proof are tracked under
[ROADMAP.md](../ROADMAP.md) § “Apache Incubator and release process (gated outside day-to-day code)”.
They do not block Keep-green adopter engineering and are not claimed as Aegis code defects.

## How to re-run a verified adopter

```powershell
$env:AEGIS_KNOX_HOME = 'C:\knox\knox-3.0.0'
./examples/adopters/run-example.ps1 <adopter> -Keep
```
