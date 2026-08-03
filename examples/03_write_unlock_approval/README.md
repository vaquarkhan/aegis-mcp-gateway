# Unlock writes + approval token

Author: Viquar Khan.

```bash
export MCP_GW_WRITE_ENABLED=true
export MCP_GW_APPROVAL_SECRET=$(openssl rand -hex 32)
export MCP_GW_ADAPTERS=flink
export FLINK_REST_URL=http://localhost:8081
```

Mint a token for `stop_job` scoped to a job id:

```bash
java -cp ../../mcp-gateway-core/target/classes \
  io.github.vaquarkhan.aegis.core.governance.Approval \
  "$MCP_GW_APPROVAL_SECRET" stop_job job-42 300
```

Pass the printed value as `approvalToken` with `jobId=job-42`. Replay must fail with `APPROVAL_REQUIRED`.
