<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Apache Iceberg REST Catalog profile

`iceberg.yaml` governs seven Iceberg REST Catalog operations, including namespace/table discovery,
load, property update and destructive table drop.

## Why this source

Iceberg REST Catalog is a real HTTP protocol, so this profile is declarative `http` configuration.
Namespace/table inputs become typed resources and the profile demonstrates resource scopes. Engine
maintenance procedures belong in Spark or Flink sources, not fabricated catalog routes.

## Run

```powershell
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar `
  --config adopters/configurations/iceberg/iceberg.yaml
```

Replace catalog URL, gateway-owned bearer credential, groups, scopes, egress CIDRs and audit path.

## Evidence

`RealIcebergRestCatalogTest` runs when `AEGIS_ICEBERG_REST_URL` names a local catalog. It skips when
that endpoint is absent; a skip is not compatibility evidence.
