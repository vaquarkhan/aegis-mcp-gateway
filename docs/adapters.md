# Adapters

Author: Viquar Khan.

An adapter teaches the gateway about one engine. It contributes tool definitions, optional
resources, an egress allow list and an optional credential resolver. It contributes no governance:
exposure, scope, policy, approval, rate limiting, redaction and audit stay in the core, so every
engine is governed the same way.

Adapters are discovered at runtime through `ServiceLoader`. `MCP_GW_ADAPTERS` narrows the set; an
empty value means every adapter on the classpath.

```bash
export MCP_GW_ADAPTERS=flink,kafka
```

## Tool classes

Every tool declares a class, and the class decides whether it is registered at all.

| Class | Meaning | Registered when writes are locked |
| --- | --- | --- |
| `READ` | Observes state, changes nothing | Yes |
| `MUTATE` | Changes remote state, reversible | No |
| `DESTRUCTIVE` | Changes remote state, hard or impossible to reverse | No |

With `MCP_GW_WRITE_ENABLED=false` a model never sees the `MUTATE` and `DESTRUCTIVE` tools, so it
cannot be talked into calling one. With writes unlocked they appear but each call needs an approval
token. Narrow the surface further with `MCP_GW_TOOLS_ALLOWED`.

## Settings resolution

Each adapter reads its settings through `GatewayConfig.adapterProperty`, which resolves in this
order: an explicit YAML key from `MCP_GW_CONFIG`, then an environment variable of the conventional
name, then the built-in default. Both spellings are listed below.

Each adapter also contributes the host of its configured backend to the egress allow list, so the
usual case needs no `MCP_GW_EGRESS_ALLOW_HOSTS` entry. An unparsable URL contributes nothing, which
keeps egress closed rather than open.

## Apache Flink

Engine id `flink`, taxonomy class `streaming`. Backed by the Flink REST API and the SQL Gateway.

| YAML key | Environment variable | Default |
| --- | --- | --- |
| `flink.rest.url` | `FLINK_REST_URL` | `http://localhost:8081` |
| `flink.gateway.url` | `MCP_FLINK_GATEWAY_URL` | `http://localhost:8083` |
| `flink.rest.auth.header` | `MCP_FLINK_REST_AUTH_HEADER` | none |
| `flink.gateway.auth.header` | `MCP_FLINK_GATEWAY_AUTH_HEADER` | none |
| `flink.jar.upload.allow.dirs` | `MCP_FLINK_JAR_UPLOAD_ALLOW_DIRS` | none |

Tools:

- `READ`: `list_jobs`, `get_job`, `get_job_status`, `get_job_exceptions`, `get_job_metrics`,
  `list_checkpoints`, `list_jars`, `run_sql_readonly`, `get_cluster_info`, `list_taskmanagers`,
  `get_job_config`, `get_flink_config`
- `MUTATE`: `trigger_savepoint`, `rescale_job`, `upload_jar`
- `DESTRUCTIVE`: `run_jar`, `stop_job`, `cancel_job`, `run_sql_ddl_dml`

Flink is the reason `CallerIdentity` carries separate job and jar allow lists: those are the two
resources a destructive tool binds to most often. Give `ops-bot` the job ids it actually operates
and nothing else.

`upload_jar` and `run_jar` are the sharpest tools in the project. Set
`MCP_GW_JAR_UPLOAD_ALLOW_DIRS` so a jar can only come from a directory you control, and keep the
jar allow list narrow. Running an arbitrary jar on a Flink cluster is remote code execution by
design; the allow lists are what make it an operation rather than an exploit.

## Apache Kafka

Engine id `kafka`. Backed by the Kafka `AdminClient`, a Schema Registry HTTP client, and a short
lived tail consumer for dead-letter inspection. Clients are created lazily on the first tool call,
so listing tools opens no connection.

| YAML key | Environment variable | Default |
| --- | --- | --- |
| `kafka.bootstrap.servers` | `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` |
| `kafka.schema.registry.url` | `SCHEMA_REGISTRY_URL` | `http://localhost:8081` |
| `kafka.dlq.max.records` | `KAFKA_DLQ_MAX_RECORDS` | `5` |

Tools:

- `READ`: `list_topics`, `describe_topic`, `query_schema_registry`, `inspect_dlq`
- `MUTATE`: `create_topic`, `alter_config`
- `DESTRUCTIVE`: `reset_offsets`, `delete_records`

`reset_offsets` and `delete_records` move or destroy consumer progress and data. Both are
approval-gated when writes are unlocked, and `inspect_dlq` is bounded by the record cap so a poison
topic cannot fill the model's context.

## Apache Spark

Engine id `spark`. Backed by the History Server, optionally Livy for submission, and an optional
SQL HTTP endpoint.

| YAML key | Environment variable | Default |
| --- | --- | --- |
| `spark.history.url` | `SPARK_HISTORY_URL` | `http://localhost:18080` |
| `spark.livy.url` | `SPARK_LIVY_URL` | `http://localhost:8998` |
| `spark.sql.http.url` | `SPARK_SQL_HTTP_URL` | none |

Tools:

- `READ`: `list_applications`, `get_application`, `run_sql_readonly`
- `DESTRUCTIVE`: `submit_batch`, `kill_application`

`submit_batch` submits arbitrary work to a cluster, which is why it is `DESTRUCTIVE` rather than
`MUTATE` even though it destroys nothing directly.

## Apache Iceberg

Engine id `iceberg`, taxonomy class `lakehouse`. Backed by an Iceberg REST catalog.

| YAML key | Environment variable | Default |
| --- | --- | --- |
| `iceberg.rest.catalog.url` | `ICEBERG_REST_CATALOG_URL` | `http://localhost:8181` |

Tools:

- `READ`: `list_namespaces`, `list_tables`, `get_table`, `get_table_metadata`,
  `dry_run_maintenance`
- `MUTATE`: `create_namespace`, `alter_table`
- `DESTRUCTIVE`: `drop_table`, `expire_snapshots`, `remove_orphan_files`, `rewrite_data_files`,
  `commit_transaction`

Resource: `iceberg://catalog` exposes the namespace listing, redacted and size bounded like any
tool output.

Maintenance operations are `DESTRUCTIVE` because `expire_snapshots` and `remove_orphan_files`
delete data that time travel would otherwise reach. `dry_run_maintenance` is the `READ` companion:
use it to see what a maintenance call would do before minting an approval for the real one.

## Writing a new adapter

1. Create a `mcp-adapter-<engine>` module depending on `mcp-gateway-core`.
2. Implement `EngineAdapter`: `engineId`, `taxonomyClass`, `tools`, and optionally `resources`,
   `egressAllowHosts` and `credentialResolver`.
3. Register it in `META-INF/services/io.github.vaquarkhan.aegis.core.spi.EngineAdapter`.
4. Declare the tools in `src/main/resources/adapters/<engine>/tools.yaml`, each with a `class`.
5. Read settings through `adapterProperty` with both a dotted YAML key and an environment variable.
6. Let backend failures propagate as exceptions. Returning a placeholder body would hide the
   failure from the circuit breaker and tell the caller a dead backend looks healthy.

Two rules matter more than the rest. Classify honestly: anything that changes remote state is
`MUTATE` or `DESTRUCTIVE`, even when it looks harmless, because the class is what keeps the tool
out of the model's sight. And contribute the backend host to `egressAllowHosts` rather than asking
operators to widen the global list.

See [CONTRIBUTING.md](../CONTRIBUTING.md) and
[LLD-apache-mcp-gateway.md](LLD-apache-mcp-gateway.md) for the SPI contract in full.

## Apache adapter catalog (0.1.0)

Reference / deep backends: `flink`, `kafka`, `spark`, `iceberg`.

HTTP/REST scaffolds (JDK `HttpJsonClient`; failures propagate to the breaker). These are **not**
full native clients: they compile, register tools, and call a configured URL. Enable explicitly
with `MCP_GW_ADAPTERS` so agents do not see every engine at once. Depth work is tracked on the
roadmap; only Flink, Kafka, Spark, and Iceberg claim reference-backend status in 0.1.0.

| Adapter | Taxonomy | Env URL | Notes |
| --- | --- | --- | --- |
| pulsar | messaging | `PULSAR_ADMIN_URL` | Admin REST |
| activemq | messaging | `ACTIVEMQ_API_URL` | Jolokia |
| nifi | dataflow | `NIFI_API_URL` | NiFi REST |
| beam | pipeline | `BEAM_JOB_SERVER_URL` | Job API |
| storm | streaming | `STORM_UI_URL` | Storm UI API |
| flume | ingest | `FLUME_METRICS_URL` | Metrics JSON |
| hudi | lakehouse | `HUDI_REST_URL` | REST facade |
| paimon | lakehouse | `PAIMON_REST_URL` | REST facade |
| hive | query | `HIVE_SERVER2_HTTP_URL` | HS2 / SQL HTTP |
| pinot | olap | `PINOT_CONTROLLER_URL` | Controller |
| druid | olap | `DRUID_ROUTER_URL` | Router / coordinator |
| doris | olap | `DORIS_FE_HTTP_URL` | FE HTTP |
| calcite | query | `CALCITE_HTTP_URL` | SQL HTTP facade |
| impala | query | `IMPALA_HTTP_URL` | Impala Web UI |
| hadoop | storage | `HDFS_WEBHDFS_URL` | WebHDFS |
| hbase | datastore | `HBASE_REST_URL` | HBase REST |
| cassandra | datastore | `CASSANDRA_SIDECAR_URL` | Sidecar |
| kudu | datastore | `KUDU_REST_URL` | REST facade |
| ignite | datastore | `IGNITE_REST_URL` | Ignite REST |
| bookkeeper | logstore | `BOOKKEEPER_HTTP_URL` | Bookie HTTP |
| ozone | objectstore | `OZONE_OM_HTTP_URL` | OM HTTP |
| airflow | orchestration | `AIRFLOW_API_URL` | Stable REST |
| zookeeper | coordination | `ZOOKEEPER_REST_URL` | Admin / znodes facade |
| solr | search | `SOLR_URL` | Solr admin |
| superset | bi | `SUPERSET_URL` | Superset API |
| arrow | analytics | `ARROW_FLIGHT_HTTP_URL` | Flight HTTP facade |
| ranger | governance | `RANGER_URL` | Ranger public API |
| atlas | metadata | `ATLAS_URL` | Atlas v2 |
| couchdb | datastore | `COUCHDB_URL` | CouchDB HTTP |

These scaffolds target published Apache HTTP surfaces where possible. Some engines need an operator
HTTP facade in front of a binary protocol (for example native Arrow Flight or Kudu RPC); until that
URL is configured, calls fail closed and trip the breaker rather than inventing success bodies.

This is not every Apache Software Foundation project (Tomcat, httpd, Maven, etc. are out of scope
for a data governance gateway). New adapters follow the SPI steps above.

Per-adapter run recipes: [../examples/adapters](../examples/adapters).
