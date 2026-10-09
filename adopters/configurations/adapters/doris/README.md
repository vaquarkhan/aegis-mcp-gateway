<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Apache Doris adopter

`doris.yaml` governs four Doris Frontend operations: status, cluster overview, backend listing and read-only SQL.

## Why this source

Doris exposes the selected administration and SQL HTTP surface. The SQL tool is declared READ only because the enforcing `sql-readonly` hook is part of the profile; it is not a general write SQL interface.

## Developer workflow

```powershell
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar --config adopters/configurations/adapters/doris/doris.yaml
```

Replace the endpoint, credentials, groups and egress policy. Use a database credential that cannot write; keyword screening alone cannot guarantee a query has no side effects.

## Evidence

The profile has a route/governance contract test. A real Doris target-version run is required.
