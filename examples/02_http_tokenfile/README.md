# HTTP + token file

Author: Viquar Khan.

Starts the gateway on streamable HTTP with a local bearer token file.

## Prerequisites

- JDK 17+
- Built shade jar

## Token file

Copy `tokens.example.txt` to `tokens.txt` (do not commit real secrets):

```text
# callerId:sha256hex(token):jobs:jars:readonly
demo:9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08:*:*:true
```

The SHA-256 above is of the literal string `test` (UTF-8). Present `Authorization: Bearer test`.

## Run

```bash
export MCP_GW_TRANSPORT=http
export MCP_GW_HTTP_HOST=127.0.0.1
export MCP_GW_HTTP_PORT=8090
export MCP_GW_AUTH_MODE=tokenfile
export MCP_GW_AUTH_TOKENS_FILE=./tokens.txt
export MCP_GW_ADAPTERS=flink
export FLINK_REST_URL=http://localhost:8081
java -jar ../../mcp-gateway-dist/target/aegis-mcp-gateway-0.1.0-all.jar
```

Smoke:

```bash
curl -s http://127.0.0.1:8090/healthz
curl -s -H "Authorization: Bearer test" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' \
  http://127.0.0.1:8090/mcp
```
