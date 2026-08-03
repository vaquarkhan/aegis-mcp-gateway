# Examples index

Author: Viquar Khan.

Runnable recipes for local development. Prefer read-only profiles unless you intentionally unlock writes.

## Workflow examples

| Example | Purpose |
| --- | --- |
| [01_stdio_flink_readonly](01_stdio_flink_readonly) | Stdio MCP + Flink read tools |
| [02_http_tokenfile](02_http_tokenfile) | HTTP + bearer token file |
| [03_write_unlock_approval](03_write_unlock_approval) | Mint approval token and call a mutate tool |
| [04_multi_adapter](04_multi_adapter) | Enable flink, kafka, spark, iceberg together |
| [05_cursor_mcp_config](05_cursor_mcp_config) | Cursor IDE MCP server snippet |
| [06_oauth_jwks](06_oauth_jwks) | HTTP OAuth resource server with JWKS |
| [07_sample_tool_calls](07_sample_tool_calls) | Sample `tools/call` JSON payloads |
| [08_flink_kafka](08_flink_kafka) | Docker Compose Flink + Kafka + Aegis smoke |
| [docker-compose](docker-compose) | Gateway-only container beside your engines |

## Per-adapter examples

One read-only stdio recipe for every engine adapter lives under [adapters/](adapters/).

| Adapter | Example |
| --- | --- |
| Flink | [adapters/flink](adapters/flink) |
| Kafka | [adapters/kafka](adapters/kafka) |
| Spark | [adapters/spark](adapters/spark) |
| Iceberg | [adapters/iceberg](adapters/iceberg) |
| Pulsar | [adapters/pulsar](adapters/pulsar) |
| ActiveMQ | [adapters/activemq](adapters/activemq) |
| NiFi | [adapters/nifi](adapters/nifi) |
| Beam | [adapters/beam](adapters/beam) |
| Storm | [adapters/storm](adapters/storm) |
| Flume | [adapters/flume](adapters/flume) |
| Hudi | [adapters/hudi](adapters/hudi) |
| Paimon | [adapters/paimon](adapters/paimon) |
| Hive | [adapters/hive](adapters/hive) |
| Pinot | [adapters/pinot](adapters/pinot) |
| Druid | [adapters/druid](adapters/druid) |
| Doris | [adapters/doris](adapters/doris) |
| Calcite | [adapters/calcite](adapters/calcite) |
| Impala | [adapters/impala](adapters/impala) |
| Hadoop HDFS | [adapters/hadoop](adapters/hadoop) |
| HBase | [adapters/hbase](adapters/hbase) |
| Cassandra | [adapters/cassandra](adapters/cassandra) |
| Kudu | [adapters/kudu](adapters/kudu) |
| Ignite | [adapters/ignite](adapters/ignite) |
| BookKeeper | [adapters/bookkeeper](adapters/bookkeeper) |
| Ozone | [adapters/ozone](adapters/ozone) |
| Airflow | [adapters/airflow](adapters/airflow) |
| ZooKeeper | [adapters/zookeeper](adapters/zookeeper) |
| Solr | [adapters/solr](adapters/solr) |
| Superset | [adapters/superset](adapters/superset) |
| Arrow Flight | [adapters/arrow](adapters/arrow) |
| Ranger | [adapters/ranger](adapters/ranger) |
| Atlas | [adapters/atlas](adapters/atlas) |
| CouchDB | [adapters/couchdb](adapters/couchdb) |

Full table with taxonomies and sample tools: [adapters/README.md](adapters/README.md).

Build the dist jar first:

```bash
mvn -q -pl mcp-gateway-dist -am package -DskipTests
```
