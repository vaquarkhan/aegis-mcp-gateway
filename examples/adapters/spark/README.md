# Apache Spark adapter example

Author: Viquar Khan.

Read-only stdio profile for the `spark` adapter (taxonomy `batch`). Writes stay locked.

## Prerequisites

- JDK 17+
- Built shade jar: `mvn -q -pl mcp-gateway-dist -am package -DskipTests`
- Reachable backend at `SPARK_HISTORY_URL` (default `http://localhost:18080`)

Livy submit uses SPARK_LIVY_URL; SQL needs SPARK_SQL_HTTP_URL.

## Run

Bash:

```bash
export MCP_GW_TRANSPORT=stdio
export MCP_GW_ADAPTERS=spark
export SPARK_HISTORY_URL=http://localhost:18080
java -jar ../../mcp-gateway-dist/target/aegis-mcp-gateway-0.1.0-all.jar
```

PowerShell:

```powershell
$env:MCP_GW_TRANSPORT='stdio'
$env:MCP_GW_ADAPTERS='spark'
$env:SPARK_HISTORY_URL='http://localhost:18080'
java -jar ..\..\mcp-gateway-dist\target\aegis-mcp-gateway-0.1.0-all.jar
```

Settings also live in `env.example`.

## Sample read tool

Call `list_applications` from your MCP client after connect. Mutate and destructive tools stay hidden while writes are locked.

## See also

- Catalog: [docs/adapters.md](../../docs/adapters.md)
- Combined Flink + Kafka smoke: [../08_flink_kafka](../08_flink_kafka)
