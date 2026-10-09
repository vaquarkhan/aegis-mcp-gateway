<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Apache Flume adopter

`flume.yaml` governs two Flume monitoring reads: metrics and service status.

## Why this source

The selected Flume monitoring surface is HTTP and read-only, so a small declarative profile is the correct 2.0 adopter.

## Developer workflow

```powershell
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar --config adopters/configurations/adapters/flume/flume.yaml
```

Replace the endpoint, groups and egress CIDRs. Do not imply that this profile manages Flume flows or configuration.

## Evidence

The profile has a route/governance contract test. A target Flume release run is required.
