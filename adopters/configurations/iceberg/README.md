<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Apache Iceberg REST Catalog profile

`iceberg.yaml` governs seven Iceberg REST Catalog operations, including namespace/table discovery,
load, property update and destructive table drop.

## Use it from Cursor or Kiro

The jar command further down does not connect an editor. Start the catalog, then start Aegis and leave
it listening, as [docs/tutorials/iceberg.md](../../../docs/tutorials/iceberg.md) sections 4 and 5 describe.
Section 3 of that tutorial is a test and stops the gateway.

Copy into the project the editor has open. Cursor:
[`adopters/tutorials/iceberg/ide/cursor/mcp.json`](../../tutorials/iceberg/ide/cursor/mcp.json) → `.cursor/mcp.json`.
Kiro: [`adopters/tutorials/iceberg/ide/kiro/mcp.json`](../../tutorials/iceberg/ide/kiro/mcp.json) →
`.kiro/settings/mcp.json`. Put the same secret in `AEGIS_VALIDATION_TOKEN_READER` and in place of
`REPLACE_WITH_YOUR_TOKEN` (the person `guest`). Reload MCP. You are ready when the gateway is still
listening on `http://127.0.0.1:8080/mcp` and the editor lists `aegis-iceberg` with its tools. Then name
`aegis-iceberg` in the agent chat. The agent calls Aegis, not the catalog URL.

## Why this source

Iceberg REST Catalog is a real HTTP protocol, so this profile is declarative `http` configuration.
Namespace/table inputs become typed resources and the profile demonstrates resource scopes. Engine
maintenance procedures belong in Spark or Flink sources, not fabricated catalog routes.

## Run

```powershell
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar `
  --config adopters/configurations/iceberg/iceberg.yaml
```

Replace catalog URL, gateway-owned bearer credential, groups, scopes, egress CIDRs and audit path.

## Evidence

`RealIcebergRestCatalogTest` runs when `AEGIS_ICEBERG_REST_URL` names a local catalog. It skips when
that endpoint is absent; a skip is not compatibility evidence.

An independent, runnable Iceberg MCP server with a signed server catalog and a break-glass drill
against a real engine is in [examples/break-glass-mcp-servers/iceberg](../../../examples/break-glass-mcp-servers/iceberg/README.md).
