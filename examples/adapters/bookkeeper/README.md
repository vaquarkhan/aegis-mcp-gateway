# Apache BookKeeper adapter example

Author: Viquar Khan.

Read-only stdio profile for the `bookkeeper` adapter (taxonomy `logstore`). Writes stay locked.

## Prerequisites

- JDK 17+
- Built shade jar: `mvn -q -pl mcp-gateway-dist -am package -DskipTests`
- Reachable backend at `BOOKKEEPER_HTTP_URL` (default `http://localhost:8000`)

Bookie HTTP admin.

## Run

Bash:

```bash
export MCP_GW_TRANSPORT=stdio
export MCP_GW_ADAPTERS=bookkeeper
export BOOKKEEPER_HTTP_URL=http://localhost:8000
java -jar ../../mcp-gateway-dist/target/aegis-mcp-gateway-0.1.0-all.jar
```

PowerShell:

```powershell
$env:MCP_GW_TRANSPORT='stdio'
$env:MCP_GW_ADAPTERS='bookkeeper'
$env:BOOKKEEPER_HTTP_URL='http://localhost:8000'
java -jar ..\..\mcp-gateway-dist\target\aegis-mcp-gateway-0.1.0-all.jar
```

Settings also live in `env.example`.

## Sample read tool

Call `bookie_info` from your MCP client after connect. Mutate and destructive tools stay hidden while writes are locked.

## See also

- Catalog: [docs/adapters.md](../../docs/adapters.md)
- Combined Flink + Kafka smoke: [../08_flink_kafka](../08_flink_kafka)
