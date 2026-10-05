<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Apache NiFi adopter

`nifi.yaml` governs five NiFi API operations: about, root/process-group reads and controlled process-group start or stop actions.

## Why this source

NiFi exposes the selected API over HTTP. Process-group identifiers are typed resources, while Aegis classifies lifecycle actions separately from reads.

## Developer workflow

```powershell
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar --config adopters/configurations/adapters/nifi/nifi.yaml
```

Replace the endpoint, credentials, groups, egress CIDRs and protected process-group rules before enabling lifecycle changes.

## Evidence

The profile has a route/governance contract test. A target NiFi version must be validated before promotion.
