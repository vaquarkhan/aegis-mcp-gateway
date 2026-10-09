<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Apache CouchDB adopter

`couchdb.yaml` governs four CouchDB operations: database listing, database detail, creation and destructive deletion.

## Why this source

CouchDB has a straightforward HTTP API, so the adopter is declarative `http` configuration. Database names are governed resources and protected system databases can be denied by policy.

## Developer workflow

```powershell
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar --config adopters/configurations/adapters/couchdb/couchdb.yaml
```

Replace the endpoint, credentials, groups, egress CIDRs and protected-database policy for the deployment.

## Evidence

The profile has a route/governance contract test. A target CouchDB version still needs a real-engine run.
