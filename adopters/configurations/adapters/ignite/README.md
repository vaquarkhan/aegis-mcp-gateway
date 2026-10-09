<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Apache Ignite adopter

`ignite.yaml` governs four Apache Ignite REST operations.

## Why this source

The selected Ignite administration surface is HTTP, so declarative `http` tools are sufficient. Aegis owns caller policy, source credentials, egress and audit decisions.

## Developer workflow

```powershell
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar --config adopters/configurations/adapters/ignite/ignite.yaml
```

Replace the endpoint, credentials, groups and egress CIDRs. Add typed resource and policy rules as the deployment's protected objects become known.

## Evidence

The profile has a route/governance contract test. A target Ignite release run is required.
