<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Apache Pinot adopter

`pinot.yaml` governs four Pinot controller operations: table and tenant discovery plus destructive table deletion.

## Why this source

The selected Pinot controller API is HTTP. Aegis turns table and tenant inputs into policy-visible resources before executing changes.

## Developer workflow

```powershell
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar --config adopters/configurations/adapters/pinot/pinot.yaml
```

Replace the controller endpoint, credentials, groups, egress CIDRs and protected-table policy.

## Evidence

The profile has a route/governance contract test. A target Pinot release run is required.
