<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Apache Airflow adopter

`airflow.yaml` governs five Airflow stable REST operations: health, DAG listing/detail, DAG run creation and DAG deletion.

## Why this source

Airflow has a documented HTTP API, so the adopter is declarative `http` configuration. Tool class, approval, typed resources and policy stay in Aegis rather than in custom adapter code.

## Developer workflow

```powershell
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar --config adopters/configurations/adapters/airflow/airflow.yaml
```

Replace the base URL, authentication, groups, egress policy and audit location. Keep destructive DAG deletion behind the configured Aegis controls.

## Evidence

The profile has a route/governance contract test. Run it against the selected Airflow release before calling it compatible.
