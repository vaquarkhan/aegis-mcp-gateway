<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Apache Druid adopter

`druid.yaml` governs four Druid operations: datasource listing/detail, read-only SQL and destructive datasource disable.

## Why this source

The selected Druid APIs are HTTP. The SQL tool uses the enforcing `sql-readonly` hook, while datasource disable is explicitly classified for Aegis write controls.

## Developer workflow

```powershell
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar --config adopters/configurations/adapters/druid/druid.yaml
```

Replace the endpoint, credentials, groups, egress rules and datasource policy. Use a read-only Druid credential for SQL.

## Evidence

The profile has a route/governance contract test. A real Druid target-version run is required.
