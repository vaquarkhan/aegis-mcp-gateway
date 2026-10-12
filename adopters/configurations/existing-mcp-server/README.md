<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Existing MCP server profile

`existing-mcp-server.yaml` is the reference profile for a project that already has an MCP server. Aegis
fronts the server without changing its implementation.

## Use it from Cursor or Kiro

The jar command further down does not connect an editor. Start the downstream MCP server, then start
Aegis and leave it listening, as
[docs/tutorials/existing-mcp-server.md](../../../docs/tutorials/existing-mcp-server.md) sections 4 and 5
describe. Section 3 of that tutorial is a test and stops the gateway.

Copy into the project the editor has open. Cursor:
[`adopters/tutorials/existing-mcp-server/ide/cursor/mcp.json`](../../tutorials/existing-mcp-server/ide/cursor/mcp.json) → `.cursor/mcp.json`.
Kiro: [`adopters/tutorials/existing-mcp-server/ide/kiro/mcp.json`](../../tutorials/existing-mcp-server/ide/kiro/mcp.json) →
`.kiro/settings/mcp.json`. Put the same secret in `AEGIS_VALIDATION_TOKEN_READER` and in place of
`REPLACE_WITH_YOUR_TOKEN` (the person `guest`). Reload MCP. You are ready when the gateway is still
listening on `http://127.0.0.1:8080/mcp` and the editor lists `aegis-existing-mcp-server` with its tools.
Then name that server in the agent chat. The agent calls Aegis, not the downstream MCP server.

## What it demonstrates

- HTTP downstream MCP with automatic 2026-07-28 discovery and 2025 fallback;
- gateway-owned backend bearer credential, never caller-token passthrough;
- tool prefixing and explicit READ/DESTRUCTIVE classification;
- tool-definition pinning, refresh and quarantine;
- local stdio MCP with `unlistedTools: HIDE`.

## Run

```powershell
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar `
  --config adopters/configurations/existing-mcp-server/existing-mcp-server.yaml
```

Replace the downstream URL or stdio command, credentials, groups, policy, egress CIDRs and audit path.
For production, collect reviewed tool digests and use `catalog.mode: REQUIRE_PINS` rather than TOFU.

## Evidence

The protocol path is tested against handler-independent HTTP and stdio MCP test servers. A real target
MCP server still needs its own compatibility run.
