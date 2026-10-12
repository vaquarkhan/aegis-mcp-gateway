<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Spark Connect profile

`spark-connect.yaml` governs Spark Connect through the typed `spark-connect` source. It exposes
metadata and dataframe reads, explain, and destructive table drop or SQL only under explicit controls.

## Use it from Cursor or Kiro

The jar command further down does not connect an editor. Start Spark Connect, then start Aegis and leave
it listening, as [docs/tutorials/spark-connect.md](../../../docs/tutorials/spark-connect.md) sections 4
and 5 describe. Section 3 of that tutorial is a test and stops the gateway. This profile needs the
Spark Connect plugin jars named in that tutorial, not the plain gateway jar alone.

Copy into the project the editor has open. Cursor:
[`adopters/tutorials/spark-connect/ide/cursor/mcp.json`](../../tutorials/spark-connect/ide/cursor/mcp.json) → `.cursor/mcp.json`.
Kiro: [`adopters/tutorials/spark-connect/ide/kiro/mcp.json`](../../tutorials/spark-connect/ide/kiro/mcp.json) →
`.kiro/settings/mcp.json`. Put the same secret in `AEGIS_VALIDATION_TOKEN_READER` and in place of
`REPLACE_WITH_YOUR_TOKEN` (the person `guest`). Reload MCP. You are ready when the gateway is still
listening on `http://127.0.0.1:8080/mcp` and the editor lists `aegis-spark-connect` with its tools. Then
name `aegis-spark-connect` in the agent chat. The agent calls Aegis, not the Spark Connect port.

## Why this source

Spark Connect is gRPC, not a REST facade. The profile supplies policy/classes/resources while the shared
`aegis-spark-connect` Java source implements the actual protocol.

## Run

Use the gateway assembly containing `aegis-spark-connect`:

```powershell
java -jar examples/aegis-gateway-spark/target/aegis-gateway-spark-2.0.0-SNAPSHOT-all.jar `
  --config adopters/configurations/spark-connect/spark-connect.yaml
```

Replace host, port, TLS/token settings, groups, egress policy and protected-table deny rules.

## Evidence

`RealSparkConnectServerTest` starts a real Spark Connect 4.0.1 server and verifies reads, dry-run,
independent approval and a real destructive drop on the current profile.
