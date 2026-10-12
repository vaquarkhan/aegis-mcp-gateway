<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Apache Spark profile

`spark.yaml` governs Spark History Server or driver-UI reads plus standalone-master submission status,
kill and approved-jar submission operations.

## Use it from Cursor or Kiro

The jar command further down does not connect an editor. Start Spark, then start Aegis and leave it
listening, as [docs/tutorials/spark.md](../../../docs/tutorials/spark.md) sections 4 and 5 describe.
Section 3 of that tutorial is a test and stops the gateway.

Copy into the project the editor has open. Cursor:
[`adopters/tutorials/spark/ide/cursor/mcp.json`](../../tutorials/spark/ide/cursor/mcp.json) → `.cursor/mcp.json`.
Kiro: [`adopters/tutorials/spark/ide/kiro/mcp.json`](../../tutorials/spark/ide/kiro/mcp.json) →
`.kiro/settings/mcp.json`. Put the same secret in `AEGIS_VALIDATION_TOKEN_READER` and in place of
`REPLACE_WITH_YOUR_TOKEN` (the person `guest`). Reload MCP. You are ready when the gateway is still
listening on `http://127.0.0.1:8080/mcp` and the editor lists `aegis-spark` with its tools. Then name
`aegis-spark` in the agent chat. The agent calls Aegis, not the Spark UI URL.

## Why this source

The selected Spark UI and standalone-master surfaces are HTTP. The standalone write routes apply only
when the target master enables its REST server. Spark Connect is a different protocol and has its own
[spark-connect](../spark-connect/README.md) profile.

## Run

```powershell
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar `
  --config adopters/configurations/spark/spark.yaml
```

Replace History/driver and master URLs, groups, egress CIDRs, audit path and jar allow-list policy.

## Evidence

`RealSparkTest` validates the read path against a real local Spark 4.0.1 driver UI. Standalone submission
and kill require a separately configured standalone-master run.

An independent, runnable Spark MCP server with a signed server catalog and a break-glass drill
against a real engine is in [examples/break-glass-mcp-servers/spark](../../../examples/break-glass-mcp-servers/spark/README.md).
