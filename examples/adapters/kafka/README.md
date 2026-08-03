# Apache Kafka adapter example

Author: Viquar Khan.

Read-only stdio profile for the `kafka` adapter (taxonomy `messaging`). Writes stay locked.

## Prerequisites

- JDK 17+
- Built shade jar: `mvn -q -pl mcp-gateway-dist -am package -DskipTests`
- Reachable backend at `KAFKA_BOOTSTRAP_SERVERS` (default `localhost:9092`)

Optional SCHEMA_REGISTRY_URL for query_schema_registry.

## Run

Bash:

```bash
export MCP_GW_TRANSPORT=stdio
export MCP_GW_ADAPTERS=kafka
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
java -jar ../../mcp-gateway-dist/target/aegis-mcp-gateway-0.1.0-all.jar
```

PowerShell:

```powershell
$env:MCP_GW_TRANSPORT='stdio'
$env:MCP_GW_ADAPTERS='kafka'
$env:KAFKA_BOOTSTRAP_SERVERS='localhost:9092'
java -jar ..\..\mcp-gateway-dist\target\aegis-mcp-gateway-0.1.0-all.jar
```

Settings also live in `env.example`.

## Sample read tool

Call `list_topics` from your MCP client after connect. Mutate and destructive tools stay hidden while writes are locked.

## See also

- Catalog: [docs/adapters.md](../../docs/adapters.md)
- Combined Flink + Kafka smoke: [../08_flink_kafka](../08_flink_kafka)
