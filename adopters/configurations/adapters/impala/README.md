<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Apache Impala adopter

`impala.yaml` governs four Impala coordinator reads: status, metrics, query listing and query cancellation.

## Why this source

The selected coordinator surface is HTTP, so the profile is declarative. Query identifiers become Aegis resources before a cancellation action is considered.

## Developer workflow

```powershell
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar --config adopters/configurations/adapters/impala/impala.yaml
```

Replace the coordinator endpoint, authentication, groups and egress policy. Review cancellation classification and protected-query policy for the deployment.

## Evidence

The profile has a route/governance contract test. A target Impala release run is required.
