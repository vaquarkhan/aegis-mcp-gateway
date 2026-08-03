# Stdio + Flink (read-only)

Author: Viquar Khan.

Starts the gateway on stdio with only the Flink adapter. Writes stay locked.

## Prerequisites

- JDK 17+
- Built shade jar
- Flink REST at `FLINK_REST_URL` (default `http://localhost:8081`)

## Run

```bash
export MCP_GW_TRANSPORT=stdio
export MCP_GW_ADAPTERS=flink
export FLINK_REST_URL=http://localhost:8081
java -jar ../../mcp-gateway-dist/target/aegis-mcp-gateway-0.1.0-all.jar
```

Windows PowerShell:

```powershell
$env:MCP_GW_TRANSPORT='stdio'
$env:MCP_GW_ADAPTERS='flink'
$env:FLINK_REST_URL='http://localhost:8081'
java -jar ..\..\mcp-gateway-dist\target\aegis-mcp-gateway-0.1.0-all.jar
```

Mutate tools such as `stop_job` must not appear while writes are locked.
