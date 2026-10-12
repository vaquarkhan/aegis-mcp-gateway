<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Adopter real-engine status (2026-10-05)

Companion to [`examples/adopters/README.md`](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/{{ site.github.source.branch }}/examples/adopters/README.md) and
[CLAIMS-MATRIX.md](CLAIMS-MATRIX.md).

## What adopter coverage means here

Every shipped profile has a governance configuration. A two-door run is a retained own-host and Knox exercise against a real product process. Product-stack runs still ahead are on the [future roadmap](../ROADMAP.md#future-roadmap-remaining-target-verification).

## Retained two-door runs

The inventory records **28** profiles with own-host and Knox runs: the adopter rows marked **passed** in [`examples/adopters/README.md`](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/{{ site.github.source.branch }}/examples/adopters/README.md), including
the `spark-iceberg` scenario. Those 28 profiles have IDE tutorials. A2A is a separate opt-in test and is not one of the 28. The release-candidate repeat is on the [roadmap](../ROADMAP.md#next-release-candidate-evidence).

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
| **HBase** | REST via `dajobe/hbase@sha256:daa36a6d90b118ced866b6c76fcd918e7da73302b0e4971f506f0f61f645a9fe` | `examples/adopters/hbase/results/` |
| **Hive** | 4.0.1 HiveServer2 Docker | `examples/adopters/hive/results/` |
| Iceberg REST | 1.10.1 | `examples/adopters/iceberg/results/` |
| Ignite | 2.18.0 | `examples/adopters/ignite/results/` |
| Kafka + Connect | 3.9.1 | `examples/adopters/kafka/results/` |
| **NiFi** | 1.28.1 Docker | `examples/adopters/nifi/results/` |
| Pulsar | 4.0.13 | `examples/adopters/pulsar/results/` |
| **Ranger** | 2.9.0 Admin and database Docker | `examples/adopters/ranger/results/` |
| Solr | 9.10.1 | `examples/adopters/solr/results/` |
| Spark | 4.0.1 | `examples/adopters/spark/results/` |
| Spark Connect | 4.0.1 (gRPC plugin) | `examples/adopters/spark-connect/results/` |
| **Storm** | 2.6.2 Docker | `examples/adopters/storm/results/` |
| **Superset** | 4.0.2 Docker | `examples/adopters/superset/results/` |
| ZooKeeper | 3.9.3 | `examples/adopters/zookeeper/results/` |
| Spark+Iceberg tutorial | Spark Connect + Iceberg | `examples/adopters/spark-iceberg/results/` |

`A2aAgentFrontDoorsTest` can exercise an official a2a-sdk 1.2.1 agent through both doors. It is skipped unless `AEGIS_A2A_PYTHON` is set, and this tree keeps no `results/` for it. A skipped run is not a retained pass.

## Remaining engineering (not ASF process)

These four ship as configuration. The next step for each is a full product-stack run. JDBC and Spark Connect apply where the product speaks that protocol.

| Adopter | Surface in this release | Fits JDBC or Spark Connect? | Next product-stack step |
| --- | --- | --- | --- |
| **Impala** | Coordinator **HTTP** debug UI tools today; SQL would be JDBC like Hive | **SQL yes (jdbc rewrite); cancel/status stay HTTP.** Spark Connect does not speak Impala | [Future roadmap](../ROADMAP.md#future-roadmap-remaining-target-verification): an Impala coordinator |
| **Paimon** | Declarative **HTTP** REST catalog (same pattern as Iceberg REST) | **Not JDBC/gRPC.** | [Future roadmap](../ROADMAP.md#future-roadmap-remaining-target-verification): a production REST catalog whose API matches the profile |
| **Cassandra** | Declarative **HTTP** Cassandra Sidecar | **Not JDBC** (profile is Sidecar REST, not CQL). CQL would be a different jdbc profile | [Future roadmap](../ROADMAP.md#future-roadmap-remaining-target-verification): Sidecar routes that match the profile |
| **Atlas** | Declarative **HTTP** Atlas v2 | **Not JDBC/gRPC** | [Future roadmap](../ROADMAP.md#future-roadmap-remaining-target-verification): Atlas with Kafka, Solr, and HBase |

### Spark Connect covers Spark

`aegis-spark-connect` is a **typed Spark Connect client** (Spark’s own gRPC protocol). It is not a
generic gRPC proxy. Arrow Flight and Beam JobService are different protocols and are planned as typed sources in a later release.

## ASF / Incubator / release process (not this engineering backlog)

Provenance scans, software-grant header switches and private security and conduct channel proof are
tracked under [release process items](RELEASE-ASSURANCE.md#release-process-items). A published image
and a cluster install are on the [roadmap](../ROADMAP.md).

## How to reproduce an adopter run

From the repository root:

```powershell
$env:AEGIS_KNOX_HOME = 'C:\knox\knox-3.0.0'
./examples/adopters/run-example.ps1 <adopter> -Keep
```

`-Keep` copies a passing run over the tracked files in `examples/adopters/<adopter>/results/`. Review that diff before committing it. The runner still stops the processes it started, so this command is not how an IDE session is left listening. The release-candidate repeat is on the [roadmap](../ROADMAP.md#next-release-candidate-evidence).
