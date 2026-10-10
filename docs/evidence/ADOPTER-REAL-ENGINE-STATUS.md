<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Adopter real-engine status (2026-10-05)

Companion to [`examples/adopters/README.md`](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/aegis-2.0-review-20261009/examples/adopters/README.md) and
[CLAIMS-MATRIX.md](CLAIMS-MATRIX.md). **Contract support** (AdapterConfigsTest) is not the same as a
real two-door run.

## What adopter coverage means here

Every shipped profile has an implemented governance configuration and contract assertion. That is
implementation and contract coverage, not candidate-bound target verification. **Target verification**
means a retained own-host and Knox run against a real product process, with immutable artifacts bound
to the candidate commit/tree. A runnable surface can be HTTP, a JDBC driver and server, or a typed
plugin such as Spark Connect. Coverage is not closed by inventing REST routes, and it is not the same
as release process work (see [release process items](RELEASE-ASSURANCE.md#release-process-items)).

## Retained two-door runs (historical until candidate-bound rerun)

The historical inventory recorded **26** profiles with own-host and Knox Keep runs: the adopter/scenario
rows marked **passed** in [`examples/adopters/README.md`](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/aegis-2.0-review-20261009/examples/adopters/README.md), including
the `spark-iceberg` scenario. Those results are not bound to the current dirty tree by an immutable
manifest, so they are historical rather than `verified-current`. The 26 profiles drive the generated
IDE tutorials. **A2A** also has a recorded two-door run below; it is an additional typed-source Keep,
not one of the six contract-only backlog items.

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

These six ship as configuration. The next step for each is a full product-stack run. JDBC and Spark Connect apply where the product speaks that protocol.

| Adopter | Surface in this release | Fits JDBC or Spark Connect? | Next product-stack step |
| --- | --- | --- | --- |
| **Hive** | `type: jdbc` + `HiveDriver` | **Yes: same JDBC source as Calcite/Derby.** | Full Keep needs HiveServer2 client jars, seed data, retained results, and a tutorial. Tracked on [ROADMAP.md](../ROADMAP.md) |
| **Impala** | Coordinator **HTTP** debug UI tools today; SQL would be JDBC like Hive | **SQL yes (jdbc rewrite); cancel/status stay HTTP.** Spark Connect does not speak Impala | Full Impala (or JDBC endpoint) stack: **future roadmap** |
| **Paimon** | Declarative **HTTP** REST catalog (same pattern as Iceberg REST) | **Not JDBC/gRPC.** | Paimon REST catalog + warehouse Keep: **future roadmap** |
| **Cassandra** | Declarative **HTTP** Cassandra Sidecar | **Not JDBC** (profile is Sidecar REST, not CQL). CQL would be a different jdbc profile | Sidecar + cluster Docker Keep: **future roadmap** |
| **Ranger** | Declarative **HTTP** Admin API | **Not JDBC/gRPC** | Ranger Admin + DB Docker Keep: **future roadmap** |
| **Atlas** | Declarative **HTTP** Atlas v2 | **Not JDBC/gRPC** | Atlas + Kafka + Solr + HBase: **future roadmap** |

### Spark Connect covers Spark

`aegis-spark-connect` is a **typed Spark Connect client** (Spark’s own gRPC protocol). It is not a
generic gRPC proxy. Arrow Flight and Beam JobService are different protocols and are planned as typed sources in a later release.

## ASF / Incubator / release process (not this engineering backlog)

Provenance scans, software-grant header switches and private security and conduct channel proof are
tracked under [release process items](RELEASE-ASSURANCE.md#release-process-items); a published image
and a cluster install are on the [roadmap](../ROADMAP.md). None of them blocks adopter engineering.

## How to reproduce an adopter run

From the repository root:

```powershell
$env:AEGIS_KNOX_HOME = 'C:\knox\knox-3.0.0'
./examples/adopters/run-example.ps1 <adopter> -Keep
```

A new run remains `recorded-unattested` until its exact commit/tree identity, raw output, retained
result files, and SHA-256 manifest are preserved as candidate-bound evidence.
