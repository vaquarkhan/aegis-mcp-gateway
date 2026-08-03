# Apache Storm adapter example

Author: Viquar Khan.

Read-only stdio profile for the `storm` adapter (taxonomy `streaming`). Writes stay locked.

## Prerequisites

- JDK 17+
- Built shade jar: `mvn -q -pl mcp-gateway-dist -am package -DskipTests`
- Reachable backend at `STORM_UI_URL` (default `http://localhost:8080`)

Storm UI JSON API.

## Run

Bash:

```bash
export MCP_GW_TRANSPORT=stdio
export MCP_GW_ADAPTERS=storm
export STORM_UI_URL=http://localhost:8080
java -jar ../../mcp-gateway-dist/target/aegis-mcp-gateway-0.1.0-all.jar
```

PowerShell:

```powershell
$env:MCP_GW_TRANSPORT='stdio'
$env:MCP_GW_ADAPTERS='storm'
$env:STORM_UI_URL='http://localhost:8080'
java -jar ..\..\mcp-gateway-dist\target\aegis-mcp-gateway-0.1.0-all.jar
```

Settings also live in `env.example`.

## Sample read tool

Call `cluster_summary` from your MCP client after connect. Mutate and destructive tools stay hidden while writes are locked.

## See also

- Catalog: [docs/adapters.md](../../docs/adapters.md)
- Combined Flink + Kafka smoke: [../08_flink_kafka](../08_flink_kafka)
