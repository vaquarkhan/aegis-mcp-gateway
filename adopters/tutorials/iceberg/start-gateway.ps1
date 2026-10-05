# Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Starts the Aegis gateway on http://127.0.0.1:8080/mcp. Iceberg is a plain http source, so the base
# gateway jar is enough: no plugin, no engine client, no Iceberg dependency.
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$jar = Join-Path $here '..\..\aegis-gateway\target\aegis-gateway-2.0.0-SNAPSHOT-all.jar'
if (-not (Test-Path $jar)) {
    Write-Error "Missing $jar. Run: mvn -B install -DskipTests"
}
foreach ($v in 'AEGIS_SIGNING_KEY', 'ANALYST_TOKEN', 'ENGINEER_TOKEN', 'STEWARD_TOKEN') {
    if (-not [Environment]::GetEnvironmentVariable($v)) { Write-Error "Set `$env:$v first (see the tutorial, step 4.3)" }
}
# The catalog credential the gateway presents upstream. The reference fixture ignores it; a real
# catalog would not.
if (-not $env:ICEBERG_CATALOG_TOKEN) { $env:ICEBERG_CATALOG_TOKEN = 'unused-by-the-reference-fixture' }
& java -jar $jar --config (Join-Path $here 'aegis-iceberg-tutorial.yaml')
