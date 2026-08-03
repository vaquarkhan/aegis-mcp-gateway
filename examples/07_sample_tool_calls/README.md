# Sample MCP tool calls

Author: Viquar Khan.

Illustrative JSON-RPC bodies for an MCP client talking to Aegis (`POST /mcp` or stdio).

## List tools

```json
{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
```

## Flink read

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "list_jobs",
    "arguments": {}
  }
}
```

## Kafka read

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "list_topics",
    "arguments": {}
  }
}
```

## Iceberg read

```json
{
  "jsonrpc": "2.0",
  "id": 4,
  "method": "tools/call",
  "params": {
    "name": "list_namespaces",
    "arguments": {}
  }
}
```

## Spark history read

```json
{
  "jsonrpc": "2.0",
  "id": 5,
  "method": "tools/call",
  "params": {
    "name": "list_applications",
    "arguments": {}
  }
}
```

## Mutate (requires write unlock + approvalToken)

```json
{
  "jsonrpc": "2.0",
  "id": 6,
  "method": "tools/call",
  "params": {
    "name": "stop_job",
    "arguments": {
      "jobId": "job-42",
      "approvalToken": "<minted-hmac-token>"
    }
  }
}
```

Mint tokens with `io.github.vaquarkhan.aegis.core.governance.Approval` after setting
`MCP_GW_WRITE_ENABLED=true` and `MCP_GW_APPROVAL_SECRET`. See
[03_write_unlock_approval](../03_write_unlock_approval).
