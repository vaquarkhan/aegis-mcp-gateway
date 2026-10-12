<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Apache Flink profile

`flink.yaml` governs Flink JobManager REST operations: job discovery/detail, savepoints, cancellation,
stop, rescale and approved-jar execution.

## Use it from Cursor or Kiro

The jar command further down does not connect an editor. Start the JobManager, then start Aegis and leave
it listening, as [docs/tutorials/flink.md](../../../docs/tutorials/flink.md) sections 4 and 5 describe.
Section 3 of that tutorial is a test and stops the gateway.

Copy into the project the editor has open. Cursor:
[`adopters/tutorials/flink/ide/cursor/mcp.json`](../../tutorials/flink/ide/cursor/mcp.json) → `.cursor/mcp.json`.
Kiro: [`adopters/tutorials/flink/ide/kiro/mcp.json`](../../tutorials/flink/ide/kiro/mcp.json) →
`.kiro/settings/mcp.json`. Put the same secret in `AEGIS_VALIDATION_TOKEN_READER` and in place of
`REPLACE_WITH_YOUR_TOKEN` (the person `guest`). Reload MCP. You are ready when the gateway is still
listening on `http://127.0.0.1:8080/mcp` and the editor lists `aegis-flink` with its tools. Then name
`aegis-flink` in the agent chat. The agent calls Aegis, not the JobManager URL.

## Why this source

Flink JobManager is an HTTP control plane, so the tools are declarative `http` configuration. Typed job
and jar resources let policy constrain individual operations. Multipart jar upload and SQL Gateway
session workflows are intentionally not represented as one-call REST tools, because they need multipart or multi-step requests.

## Run

```powershell
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar `
  --config adopters/configurations/flink/flink.yaml
```

Replace the JobManager URL, groups, approved-jar policy, egress CIDRs, audit file and credentials. Do
not enable `flink_rescale_job` unless the target cluster actually serves rescaling.

## Evidence

`RealFlinkTest` runs the canonical profile against a real local Flink 1.20.1 MiniCluster. Rerun it when
the profile or target version changes.

An independent, runnable Flink MCP server with a signed server catalog and a break-glass drill
against a real engine is in [examples/break-glass-mcp-servers/flink](../../../examples/break-glass-mcp-servers/flink/README.md).
