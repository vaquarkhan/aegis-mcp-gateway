<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Apache Superset adopter

`superset.yaml` governs four Superset API operations: database, dataset and chart listing plus destructive chart deletion.

## Why this source

The selected Superset API is HTTP. Chart identifiers are governed resources and destructive deletion uses Aegis write controls.

## Developer workflow

```powershell
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar --config adopters/configurations/adapters/superset/superset.yaml
```

Replace the endpoint, credentials, groups, egress CIDRs and protected-chart policy.

## Evidence

The profile has a route/governance contract test. A target Superset release run is required.
