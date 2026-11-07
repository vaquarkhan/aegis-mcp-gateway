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

New to Aegis? Start with the HTML [Hello World guide](guide/hello-world.html): how Aegis fits an IDE
development cycle and how to put governed MCP in front of an existing MCP server or Apache project.

Each tutorial runs real Apache software on one machine and puts Aegis between an agent and it. The
outputs in each one are copied from a recorded run; the files they use are in
[`adopters/tutorials/`](https://github.com/vaquarkhan/aegis-mcp-gateway/tree/{{ site.github.source.branch }}/adopters/tutorials).

| Tutorial | Engines | Front doors | IDE setup and prompts | Start here if you... |
| --- | --- | --- | --- | --- |
| [Spark and Iceberg from your IDE](TUTORIAL-SPARK-ICEBERG.md) | Spark 4.0.1 (Spark Connect) with Iceberg 1.10.1 tables on a REST catalog | own host and Knox 3.0.0 | VS Code, Kiro, Cursor, Claude Desktop, MCP Python SDK | want the complete developer path: tiny dataset, IDE agent, governed change, both flavors |
| [Spark and Spark Connect](TUTORIAL-SPARK-AND-SPARK-CONNECT.md) | Spark 4.0.1: monitoring REST API and Spark Connect | own host | scripted client | run Spark jobs and SQL through an agent |
| [Iceberg REST catalog](TUTORIAL-ICEBERG.md) | Iceberg 1.10.1 `RESTCatalogServer` | own host | scripted client | govern catalog operations: namespaces, properties, drops |
| [Kafka and Kafka Connect](TUTORIAL-KAFKA-AND-KAFKA-CONNECT.md) | Kafka 3.9.1 KRaft broker and Connect worker | own host | scripted client | govern topics and connectors |
| [Flink](TUTORIAL-FLINK.md) | Flink 1.20.1 | own host | scripted client | govern jobs, savepoints and cancellation |
| [Break-glass MCP servers](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/{{ site.github.source.branch }}/examples/break-glass-mcp-servers/README.md) | Spark 4.0.1, Kafka 3.9.1, Iceberg 1.10.1 and Flink 1.20.1, one independent server each | own host and stdio; Knox steps in each README | IDE settings in each README | want a signed server catalog and incident containment end to end |

The engine profiles marked **passed** in [`examples/adopters/`](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/{{ site.github.source.branch }}/examples/adopters/README.md) also have
a retained run through both front doors (own host and Knox), with the exact configuration, topology and
results; the Spark and Iceberg tutorial's run is kept there as `spark-iceberg`.

## One tutorial per verified project, from your IDE, with and without Knox

These twenty-seven are built by `tools/build-project-tutorials.py` from each project's retained run. All
twenty-seven, plus Spark with Iceberg, have historical own-host and Knox Keep records, and
every kept audit journal verifies. Each tutorial has the engine command line, both flavors step by step,
VS Code, Kiro and Cursor files in `adopters/tutorials/<project>/ide/`, prompts mapped to tools with what
Aegis answered, and the full request table of that run. `python tools/build-project-tutorials.py --check`
fails when a tutorial no longer matches its kept run.

Security reviewers: start with the [HTML security guide](guide/security.html) (threat zones, TM01 to TM10,
OWASP MCP handling, S1 to S31) before walking a tutorial.

| Project | Tutorial |
| --- | --- |
| Apache ActiveMQ 6.3.2 (Jolokia) | [tutorials/activemq.md](tutorials/activemq.md) |
| Apache Airflow 2.9.3 | [tutorials/airflow.md](tutorials/airflow.md) |
| Apache BookKeeper 4.18.1 | [tutorials/bookkeeper.md](tutorials/bookkeeper.md) |
| Apache Burr 0.42.0 (incubating) | [tutorials/burr.md](tutorials/burr.md) |
| Apache Calcite 1.42.0 over Avatica 1.28.0 | [tutorials/calcite.md](tutorials/calcite.md) |
| Apache CouchDB 3.4.2 | [tutorials/couchdb.md](tutorials/couchdb.md) |
| Apache Doris 4.1.3 | [tutorials/doris.md](tutorials/doris.md) |
| Apache Druid 28.0.1 | [tutorials/druid.md](tutorials/druid.md) |
| An existing MCP server (Python SDK and the filesystem server) | [tutorials/existing-mcp-server.md](tutorials/existing-mcp-server.md) |
| Apache Flink 1.20.1 | [tutorials/flink.md](tutorials/flink.md) |
| Apache Flume 1.11.0 | [tutorials/flume.md](tutorials/flume.md) |
| Apache Hadoop HDFS 3.2.1 | [tutorials/hadoop.md](tutorials/hadoop.md) |
| Apache HBase (REST) | [tutorials/hbase.md](tutorials/hbase.md) |
| Apache Hive 4.0.1 | [tutorials/hive.md](tutorials/hive.md) |
| Apache Iceberg 1.10.1 REST catalog | [tutorials/iceberg.md](tutorials/iceberg.md) |
| Apache Ignite 2.18.0 | [tutorials/ignite.md](tutorials/ignite.md) |
| Apache Kafka 3.9.1 and Kafka Connect | [tutorials/kafka.md](tutorials/kafka.md) |
| Apache NiFi 1.28.1 | [tutorials/nifi.md](tutorials/nifi.md) |
| Apache Pinot 1.2.0 | [tutorials/pinot.md](tutorials/pinot.md) |
| Apache Pulsar 4.0.13 | [tutorials/pulsar.md](tutorials/pulsar.md) |
| Apache Ranger 2.9.0 | [tutorials/ranger.md](tutorials/ranger.md) |
| Apache Solr 9.10.1 | [tutorials/solr.md](tutorials/solr.md) |
| Apache Spark 4.0.1 | [tutorials/spark.md](tutorials/spark.md) |
| Apache Spark Connect 4.0.1 | [tutorials/spark-connect.md](tutorials/spark-connect.md) |
| Apache Storm 2.6.2 | [tutorials/storm.md](tutorials/storm.md) |
| Apache Superset 4.0.2 | [tutorials/superset.md](tutorials/superset.md) |
| Apache ZooKeeper 3.9.3 | [tutorials/zookeeper.md](tutorials/zookeeper.md) |

The IDE files are checked by `DocumentationAssetsTest` (they parse and name the tutorial's endpoints). The
transport they use was exercised with the official MCP Python SDK in the Spark and Iceberg tutorial; the
IDEs themselves were not driven by a test.

## Projects without a tutorial

Atlas, Cassandra, Impala and Paimon have a configuration profile that passes the contract
test, which proves route rendering and governance order, not that the product answers. None has a real
run on record, so none has a tutorial. The status and blocker of each are in
[examples/adopters/README.md](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/{{ site.github.source.branch }}/examples/adopters/README.md) and
[evidence/ADOPTER-REAL-ENGINE-STATUS.md](evidence/ADOPTER-REAL-ENGINE-STATUS.md).
