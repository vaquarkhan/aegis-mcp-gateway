<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Apache Atlas adopter

`atlas.yaml` governs four Atlas v2 operations: type definition listing, entity search, entity detail and destructive entity deletion.

## Why this source

Atlas publishes a REST v2 surface, so this adopter uses declarative `http` tools. Entity identifiers become governed typed resources before deletion is considered.

## Developer workflow

```powershell
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar --config adopters/configurations/adapters/atlas/atlas.yaml
```

Replace the reference endpoint, credentials, groups and egress CIDRs. Add organization-specific resource deny rules before enabling deletion.

## Evidence

The profile has a route/governance contract test. A real Atlas target-version run remains required.
