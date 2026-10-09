<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Apache Storm adopter

`storm.yaml` governs four Storm UI operations: cluster summary, topology summary/detail and destructive topology kill.

## Why this source

Storm exposes the selected UI API over HTTP. Topology identifiers become governed resources before a kill request can proceed.

## Developer workflow

```powershell
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar --config adopters/configurations/adapters/storm/storm.yaml
```

Replace the UI endpoint, credentials, groups, egress rules and protected topology policy.

## Evidence

The profile has a route/governance contract test. A target Storm release run is required.
