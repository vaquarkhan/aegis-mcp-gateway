<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Apache HBase adopter

`hbase.yaml` governs four HBase REST operations: cluster version, table listing, schema read and destructive table deletion.

## Why this source

HBase REST exposes the selected operations over HTTP. Table names are governed resources, so policy can protect system tables before an operation reaches HBase.

## Developer workflow

```powershell
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar --config adopters/configurations/adapters/hbase/hbase.yaml
```

Replace the REST endpoint, credentials, groups and egress policy. Add explicit deny rules for protected HBase tables.

## Evidence

The profile has a route/governance contract test. A target HBase REST release run is required.
