<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Apache Cassandra adopter

`cassandra.yaml` governs four Cassandra Sidecar operations: keyspace listing, table listing, token-range read and destructive table drop.

## Why this source

The selected Cassandra Sidecar surface is HTTP, so the adopter is declarative `http` configuration. Aegis applies policy and approval around the typed keyspace/table target.

## Developer workflow

```powershell
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar --config adopters/configurations/adapters/cassandra/cassandra.yaml
```

Replace the Sidecar endpoint, credentials, groups and egress rules. Add deny policy for system keyspaces before enabling destructive operations.

## Evidence

The profile has a route/governance contract test. Run the selected Sidecar/Cassandra version before claiming compatibility.
