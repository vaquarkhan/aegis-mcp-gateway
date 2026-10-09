<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Apache Solr adopter

`solr.yaml` governs three Solr read operations: system information, collection listing and collection query.

## Why this source

The selected Solr endpoints are HTTP and read-only. Query inputs remain schema-bounded and output passes through Aegis result controls.

## Developer workflow

```powershell
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar --config adopters/configurations/adapters/solr/solr.yaml
```

Replace the endpoint, credentials, groups and egress policy. Add collection resource restrictions where the deployment requires them.

## Evidence

The profile has a route/governance contract test. A target Solr release run is required.
