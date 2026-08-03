# Apache Hive adapter example

Author: Viquar Khan.

Read-only stdio profile for the `hive` adapter (taxonomy `query`). Writes stay locked.

## Prerequisites

- JDK 17+
- Built shade jar: `mvn -q -pl mcp-gateway-dist -am package -DskipTests`
- Reachable backend at `HIVE_SERVER2_HTTP_URL` (default `http://localhost:10002`)

HiveServer2 Web UI / SQL HTTP facade.

## Run

Bash:

```bash
export MCP_GW_TRANSPORT=stdio
export MCP_GW_ADAPTERS=hive
export HIVE_SERVER2_HTTP_URL=http://localhost:10002
java -jar ../../mcp-gateway-dist/target/aegis-mcp-gateway-0.1.0-all.jar
```

PowerShell:

```powershell
$env:MCP_GW_TRANSPORT='stdio'
$env:MCP_GW_ADAPTERS='hive'
$env:HIVE_SERVER2_HTTP_URL='http://localhost:10002'
java -jar ..\..\mcp-gateway-dist\target\aegis-mcp-gateway-0.1.0-all.jar
```

Settings also live in `env.example`.

## Sample read tool

Call `get_webui` from your MCP client after connect. Mutate and destructive tools stay hidden while writes are locked.

## See also

- Catalog: [docs/adapters.md](../../docs/adapters.md)
- Combined Flink + Kafka smoke: [../08_flink_kafka](../08_flink_kafka)
