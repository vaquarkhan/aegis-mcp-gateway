<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Apache Calcite adopter

`calcite.yaml` governs six JDBC metadata and read-query operations through Avatica.

## Why this source

Calcite is a library, not a REST server. The old HTTP facade model was invalid. Aegis therefore uses the generic `jdbc` source and an Avatica JDBC driver, with typed metadata resources and read-only query controls.

## Developer workflow

```powershell
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar --config adopters/configurations/adapters/calcite/calcite.yaml
```

Provide the Avatica driver, JDBC URL, credentials and a read-only database account. Do not add `/sql` or `/explain` routes; Avatica does not serve them.

## Evidence

The generic JDBC source is proven against real Apache Derby. An Avatica target-version run remains required.
