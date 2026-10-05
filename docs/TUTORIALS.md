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
# Tutorials

Each tutorial runs real Apache software on one machine and puts Aegis between an agent and it. The
outputs in each one are copied from a recorded run; the files they use are in
[`adopters/tutorials/`](../adopters/tutorials/).

| Tutorial | Engines | Front doors | IDE setup and prompts | Start here if you... |
| --- | --- | --- | --- | --- |
| [Spark and Iceberg from your IDE](TUTORIAL-SPARK-ICEBERG.md) | Spark 4.0.1 (Spark Connect) with Iceberg 1.10.1 tables on a REST catalog | own host and Knox 3.0.0 | VS Code, Kiro, Cursor, Claude Desktop, MCP Python SDK | want the complete developer path: tiny dataset, IDE agent, governed change, both flavors |
| [Spark and Spark Connect](TUTORIAL-SPARK-AND-SPARK-CONNECT.md) | Spark 4.0.1: monitoring REST API and Spark Connect | own host | scripted client | run Spark jobs and SQL through an agent |
| [Iceberg REST catalog](TUTORIAL-ICEBERG.md) | Iceberg 1.10.1 `RESTCatalogServer` | own host | scripted client | govern catalog operations: namespaces, properties, drops |
| [Kafka and Kafka Connect](TUTORIAL-KAFKA-AND-KAFKA-CONNECT.md) | Kafka 3.9.1 KRaft broker and Connect worker | own host | scripted client | govern topics and connectors |
| [Flink](TUTORIAL-FLINK.md) | Flink 1.20.1 | own host | scripted client | govern jobs, savepoints and cancellation |

The engine profiles marked **passed** in [`examples/adopters/`](../examples/adopters/README.md) also have
a retained run through both front doors (own host and Knox), with the exact configuration, topology and
results; the Spark and Iceberg tutorial's run is kept there as `spark-iceberg`.

## One tutorial per verified project, from your IDE, with and without Knox

These fifteen are built by `tools/build-project-tutorials.py` from each project's kept real run. All
fifteen, plus Spark with Iceberg, were run on the current tree on 2026-10-03 through both front doors (the
own host and a stock Knox 3.0.0), and every kept audit journal verifies. Each tutorial has the engine
command line, both flavors step by step, VS Code, Kiro and Cursor files in
`adopters/tutorials/<project>/ide/`, prompts mapped to tools with what Aegis answered, and the full
request table of that run. `python tools/build-project-tutorials.py --check` fails when a tutorial no
longer matches its kept run.

| Project | Tutorial |
| --- | --- |
| Apache ActiveMQ 6.3.2 (Jolokia) | [tutorials/activemq.md](tutorials/activemq.md) |
| Apache BookKeeper 4.18.1 | [tutorials/bookkeeper.md](tutorials/bookkeeper.md) |
| Apache Burr 0.42.0 (incubating) | [tutorials/burr.md](tutorials/burr.md) |
| Apache Calcite 1.42.0 over Avatica 1.28.0 | [tutorials/calcite.md](tutorials/calcite.md) |
| An existing MCP server (Python SDK and the filesystem server) | [tutorials/existing-mcp-server.md](tutorials/existing-mcp-server.md) |
| Apache Flink 1.20.1 | [tutorials/flink.md](tutorials/flink.md) |
| Apache Flume 1.11.0 | [tutorials/flume.md](tutorials/flume.md) |
| Apache Iceberg 1.10.1 REST catalog | [tutorials/iceberg.md](tutorials/iceberg.md) |
| Apache Ignite 2.18.0 | [tutorials/ignite.md](tutorials/ignite.md) |
| Apache Kafka 3.9.1 and Kafka Connect | [tutorials/kafka.md](tutorials/kafka.md) |
| Apache Pulsar 4.0.13 | [tutorials/pulsar.md](tutorials/pulsar.md) |
| Apache Solr 9.10.1 | [tutorials/solr.md](tutorials/solr.md) |
| Apache Spark 4.0.1 | [tutorials/spark.md](tutorials/spark.md) |
| Apache Spark Connect 4.0.1 | [tutorials/spark-connect.md](tutorials/spark-connect.md) |
| Apache ZooKeeper 3.9.3 | [tutorials/zookeeper.md](tutorials/zookeeper.md) |

The IDE files are checked by `DocumentationAssetsTest` (they parse and name the tutorial's endpoints). The
transport they use was exercised with the official MCP Python SDK in the Spark and Iceberg tutorial; the
IDEs themselves were not driven by a test.

## Projects without a tutorial

Airflow, Atlas, Cassandra, CouchDB, Doris, Druid, Hadoop, HBase, Hive, Impala, NiFi, Paimon, Pinot,
Ranger, Storm and Superset have a configuration profile that passes the contract test, which proves
route rendering and governance order, not that the product answers. None has a real run on record, so
none has a tutorial. The status and blocker of each are in
[examples/adopters/README.md](../examples/adopters/README.md). For Airflow in particular, the profile is
written for the Airflow 2.x `/api/v1` REST API, which Airflow 3 replaced with `/api/v2`; see
[examples/adopters/airflow/README.md](../examples/adopters/airflow/README.md).
