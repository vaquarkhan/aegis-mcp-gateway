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

# Project tutorials

One tutorial per project that has a kept, clean two-door real run (own host and Knox 3.0.0). Each is built by `tools/build-project-tutorials.py` from that run's files, so the table shows exactly what was run and when.

| Project | Tutorial | Kept run | Tests | IDE files |
| --- | --- | --- | --- | --- |
| Apache ActiveMQ | [activemq.md](activemq.md) | `ActiveMqFrontDoorsTest`, 2026-10-04 | 2 run, 0 failed, 0 skipped | [`adopters/tutorials/activemq/ide/`](../../adopters/tutorials/activemq/ide/) |
| Apache BookKeeper | [bookkeeper.md](bookkeeper.md) | `BookKeeperFrontDoorsTest`, 2026-10-04 | 2 run, 0 failed, 0 skipped | [`adopters/tutorials/bookkeeper/ide/`](../../adopters/tutorials/bookkeeper/ide/) |
| Apache Burr (incubating) | [burr.md](burr.md) | `BurrFrontDoorsTest`, 2026-10-04 | 2 run, 0 failed, 0 skipped | [`adopters/tutorials/burr/ide/`](../../adopters/tutorials/burr/ide/) |
| Apache Calcite (Avatica) | [calcite.md](calcite.md) | `CalciteFrontDoorsTest`, 2026-10-04 | 2 run, 0 failed, 0 skipped | [`adopters/tutorials/calcite/ide/`](../../adopters/tutorials/calcite/ide/) |
| an existing MCP server | [existing-mcp-server.md](existing-mcp-server.md) | `ExistingMcpServerFrontDoorsTest`, 2026-10-04 | 3 run, 0 failed, 0 skipped | [`adopters/tutorials/existing-mcp-server/ide/`](../../adopters/tutorials/existing-mcp-server/ide/) |
| Apache Flink | [flink.md](flink.md) | `FlinkFrontDoorsTest`, 2026-10-04 | 2 run, 0 failed, 0 skipped | [`adopters/tutorials/flink/ide/`](../../adopters/tutorials/flink/ide/) |
| Apache Flume | [flume.md](flume.md) | `FlumeFrontDoorsTest`, 2026-10-04 | 2 run, 0 failed, 0 skipped | [`adopters/tutorials/flume/ide/`](../../adopters/tutorials/flume/ide/) |
| Apache Iceberg REST catalog | [iceberg.md](iceberg.md) | `IcebergFrontDoorsTest`, 2026-10-04 | 2 run, 0 failed, 0 skipped | [`adopters/tutorials/iceberg/ide/`](../../adopters/tutorials/iceberg/ide/) |
| Apache Ignite | [ignite.md](ignite.md) | `IgniteFrontDoorsTest`, 2026-10-04 | 2 run, 0 failed, 0 skipped | [`adopters/tutorials/ignite/ide/`](../../adopters/tutorials/ignite/ide/) |
| Apache Kafka and Kafka Connect | [kafka.md](kafka.md) | `KafkaFrontDoorsTest`, 2026-10-04 | 2 run, 0 failed, 0 skipped | [`adopters/tutorials/kafka/ide/`](../../adopters/tutorials/kafka/ide/) |
| Apache Pulsar | [pulsar.md](pulsar.md) | `PulsarFrontDoorsTest`, 2026-10-04 | 2 run, 0 failed, 0 skipped | [`adopters/tutorials/pulsar/ide/`](../../adopters/tutorials/pulsar/ide/) |
| Apache Solr | [solr.md](solr.md) | `SolrFrontDoorsTest`, 2026-10-04 | 2 run, 0 failed, 0 skipped | [`adopters/tutorials/solr/ide/`](../../adopters/tutorials/solr/ide/) |
| Apache Spark | [spark.md](spark.md) | `SparkFrontDoorsTest`, 2026-10-04 | 2 run, 0 failed, 0 skipped | [`adopters/tutorials/spark/ide/`](../../adopters/tutorials/spark/ide/) |
| Apache Spark Connect | [spark-connect.md](spark-connect.md) | `SparkConnectFrontDoorsTest`, 2026-10-04 | 2 run, 0 failed, 0 skipped | [`adopters/tutorials/spark-connect/ide/`](../../adopters/tutorials/spark-connect/ide/) |
| Apache ZooKeeper | [zookeeper.md](zookeeper.md) | `ZooKeeperFrontDoorsTest`, 2026-10-04 | 2 run, 0 failed, 0 skipped | [`adopters/tutorials/zookeeper/ide/`](../../adopters/tutorials/zookeeper/ide/) |

Apache Spark with Apache Iceberg has its own end-to-end tutorial with seed data, scripts, IDE files and Knox: [TUTORIAL-SPARK-ICEBERG.md](../TUTORIAL-SPARK-ICEBERG.md).

Projects with a configuration but no real run on record (Airflow, Atlas, Cassandra, CouchDB, Doris, Druid, Hadoop, HBase, Hive, Impala, NiFi, Paimon, Pinot, Ranger, Storm, Superset) have no tutorial here: their profiles pass the contract test only, which proves route rendering and governance order, not that the product answers. Their status and blockers are in [examples/adopters/README.md](../../examples/adopters/README.md).
