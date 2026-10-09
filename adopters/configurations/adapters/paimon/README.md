<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Apache Paimon adopter

`paimon.yaml` governs four Paimon REST catalog operations: database/table discovery, table detail and destructive table deletion.

## Why this source

Paimon's catalog surface is HTTP, so a declarative profile can define the operations while Aegis enforces typed database/table policy and destructive approval controls.

## Developer workflow

```powershell
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar --config adopters/configurations/adapters/paimon/paimon.yaml
```

Replace the catalog endpoint, credentials, groups, egress policy and protected table rules.

## Evidence

The profile has a route/governance contract test. A target Paimon release run is required.
