<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->
# Apache Pinot example: with Knox and without Knox

This example runs
[`adopters/configurations/adapters/pinot/pinot.yaml`](../../../adopters/configurations/adapters/pinot/pinot.yaml)
against a real Apache Pinot 1.2.0 QuickStart controller (`apachepinot/pinot` Docker image) through own-host
Aegis and stock Knox 3.0.0.

Status: **verified when Docker is available**, both front doors. Keep evidence with
`../run-example.ps1 pinot -Keep`.

## Prerequisites

- Docker Engine (`docker` on `PATH`)
- `AEGIS_KNOX_HOME` for the Knox door
- JDK 17+ and Maven

```powershell
$env:AEGIS_KNOX_HOME = 'C:\knox\knox-3.0.0'
../run-example.ps1 pinot -Keep
```

## What the run proves

List tables/tenants (READ), outsider denial, destructive table delete with independent approval and dry-run
receipt against a seeded scratch table, with `baseballStats` left untouched. Audit journals verify.
