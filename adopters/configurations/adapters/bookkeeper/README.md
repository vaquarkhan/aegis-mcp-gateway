<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Apache BookKeeper adopter

`bookkeeper.yaml` governs four BookKeeper HTTP operations: bookie information, bookie listing, ledger listing and destructive ledger deletion.

## Why this source

The selected BookKeeper administration surface is HTTP, so YAML describes the exact operations while Aegis supplies authorization, approval and audit controls.

## Developer workflow

```powershell
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar --config adopters/configurations/adapters/bookkeeper/bookkeeper.yaml
```

Replace the base URL, groups, credentials, egress policy and protected-ledger rules for the deployment.

## Evidence

The profile has a route/governance contract test. Verify the routes against the deployed BookKeeper version before promotion.
