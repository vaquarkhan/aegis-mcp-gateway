<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Apache Pulsar adopter

`pulsar.yaml` governs six Pulsar administration operations over the Pulsar admin API, including namespace/topic reads, creation and deletion.

## Why this source

Pulsar exposes the selected administration API over HTTP. Namespace and topic are separate governed inputs so policy can constrain each resource precisely.

## Developer workflow

```powershell
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar --config adopters/configurations/adapters/pulsar/pulsar.yaml
```

Replace the admin endpoint, authentication, groups, egress CIDRs and protected namespace/topic policy.

## Evidence

The profile has a route/governance contract test. A target Pulsar release run is required.
