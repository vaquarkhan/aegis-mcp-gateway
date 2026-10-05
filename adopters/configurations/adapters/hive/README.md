<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Apache Hive adopter

`hive.yaml` governs six HiveServer2 JDBC metadata and read-query operations.

## Why this source

HiveServer2's real programmable surface is JDBC. The old HTTP `/sql`, `/sessions` and `/queries` model had no real HiveServer2 API behind it and is deliberately absent. The generic `aegis-source-jdbc` source implements JDBC while this YAML controls visibility, resources, policy and result limits.

## Developer workflow

```powershell
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar --config adopters/configurations/adapters/hive/hive.yaml
```

Provide `hive-jdbc`, `HIVE_USER`, `HIVE_PASSWORD`, the real JDBC URL and a database account that cannot write. The SQL readonly guard is defense in depth, not a replacement for a read-only Hive credential.

## Evidence

The generic JDBC source is proven against real Apache Derby. HiveServer2 target-version validation remains pending.
