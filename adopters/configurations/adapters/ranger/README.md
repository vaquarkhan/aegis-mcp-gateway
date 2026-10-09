<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Apache Ranger adopter

`ranger.yaml` governs four Ranger service and policy operations, including destructive policy deletion.

## Why this source

Ranger provides the selected service API over HTTP. Policy and service identifiers are governed inputs, while Aegis controls destructive approval and audit behavior.

## Developer workflow

```powershell
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar --config adopters/configurations/adapters/ranger/ranger.yaml
```

Replace the endpoint, credentials, groups and egress rules. Add deny policy for administration objects that must never be changed by agents.

## Evidence

The profile has a route/governance contract test. A target Ranger release run is required.
