# Multi-adapter gateway

Author: Viquar Khan.

```bash
export MCP_GW_ADAPTERS=flink,kafka,spark,iceberg
export FLINK_REST_URL=http://localhost:8081
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
export SCHEMA_REGISTRY_URL=http://localhost:8081
export SPARK_HISTORY_URL=http://localhost:18080
export SPARK_LIVY_URL=http://localhost:8998
export ICEBERG_REST_CATALOG_URL=http://localhost:8181
java -jar ../../mcp-gateway-dist/target/aegis-mcp-gateway-0.1.0-all.jar
```

If Schema Registry is on a different host/port than Flink, set `SCHEMA_REGISTRY_URL` accordingly
(for Confluent-style local stacks that is often `http://localhost:8081` when Flink is elsewhere, or
`http://localhost:8085`).

Taxonomy: Flink `streaming`, Kafka `messaging`, Spark `batch`, Iceberg `lakehouse`.

Optional file baseline: set `MCP_GW_CONFIG=./gateway.yaml`.

See [07_sample_tool_calls](../07_sample_tool_calls) for sample `tools/call` payloads per engine.
