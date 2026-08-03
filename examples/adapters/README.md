# Per-adapter examples

Author: Viquar Khan.

One read-only stdio recipe per engine adapter. Prefer enabling a single adapter with
`MCP_GW_ADAPTERS` so the tool list stays small.

| Adapter | Taxonomy | Sample read tool | Example |
| --- | --- | --- | --- |
| Apache Flink | `streaming` | `list_jobs` | [flink](flink) |
| Apache Kafka | `messaging` | `list_topics` | [kafka](kafka) |
| Apache Spark | `batch` | `list_applications` | [spark](spark) |
| Apache Iceberg | `lakehouse` | `list_namespaces` | [iceberg](iceberg) |
| Apache Pulsar | `messaging` | `list_clusters` | [pulsar](pulsar) |
| Apache ActiveMQ | `messaging` | `get_broker` | [activemq](activemq) |
| Apache NiFi | `dataflow` | `get_about` | [nifi](nifi) |
| Apache Beam | `pipeline` | `list_jobs` | [beam](beam) |
| Apache Storm | `streaming` | `cluster_summary` | [storm](storm) |
| Apache Flume | `ingest` | `get_metrics` | [flume](flume) |
| Apache Hudi | `lakehouse` | `list_tables` | [hudi](hudi) |
| Apache Paimon | `lakehouse` | `list_databases` | [paimon](paimon) |
| Apache Hive | `query` | `get_webui` | [hive](hive) |
| Apache Pinot | `olap` | `list_tables` | [pinot](pinot) |
| Apache Druid | `olap` | `list_datasources` | [druid](druid) |
| Apache Doris | `olap` | `cluster_overview` | [doris](doris) |
| Apache Calcite | `query` | `get_status` | [calcite](calcite) |
| Apache Impala | `query` | `get_metrics` | [impala](impala) |
| Apache Hadoop HDFS | `storage` | `list_status` | [hadoop](hadoop) |
| Apache HBase | `datastore` | `cluster_version` | [hbase](hbase) |
| Apache Cassandra | `datastore` | `list_keyspaces` | [cassandra](cassandra) |
| Apache Kudu | `datastore` | `list_tables` | [kudu](kudu) |
| Apache Ignite | `datastore` | `get_version` | [ignite](ignite) |
| Apache BookKeeper | `logstore` | `bookie_info` | [bookkeeper](bookkeeper) |
| Apache Ozone | `objectstore` | `get_service` | [ozone](ozone) |
| Apache Airflow | `orchestration` | `get_health` | [airflow](airflow) |
| Apache ZooKeeper | `coordination` | `ruok` | [zookeeper](zookeeper) |
| Apache Solr | `search` | `system_info` | [solr](solr) |
| Apache Superset | `bi` | `list_databases` | [superset](superset) |
| Apache Arrow Flight | `analytics` | `list_flights` | [arrow](arrow) |
| Apache Ranger | `governance` | `list_services` | [ranger](ranger) |
| Apache Atlas | `metadata` | `list_typedefs` | [atlas](atlas) |
| Apache CouchDB | `datastore` | `list_dbs` | [couchdb](couchdb) |

Build the jar first:

```bash
mvn -q -pl mcp-gateway-dist -am package -DskipTests
```

Workflow examples (HTTP, approvals, multi-adapter, Compose) live under [../](../).

