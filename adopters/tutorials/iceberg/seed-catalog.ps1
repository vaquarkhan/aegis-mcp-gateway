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
# Creates the tutorial fixture DIRECTLY against the catalog, not through Aegis, so the starting state
# is not itself a governed action: three namespaces (sales, marketing, finance) and four tables
# (sales.orders, sales.returns, marketing.campaigns, finance.ledger). Safe to run again: an existing
# namespace is skipped and an existing table reports the catalog's conflict error.
$ErrorActionPreference = 'Stop'
$base = if ($env:AEGIS_ICEBERG_REST_URL) { $env:AEGIS_ICEBERG_REST_URL } else { 'http://127.0.0.1:18181' }
$schema = '{"type":"struct","schema-id":0,"fields":[{"id":1,"name":"id","required":true,"type":"long"},{"id":2,"name":"item","required":false,"type":"string"},{"id":3,"name":"amount","required":false,"type":"double"}]}'
foreach ($ns in 'sales', 'marketing', 'finance') {
    $body = '{"namespace":["' + $ns + '"],"properties":{}}'
    try { Invoke-RestMethod -Method Post -Uri "$base/v1/namespaces" -ContentType 'application/json' -Body $body | Out-Null } catch { }
    Write-Host "namespace $ns"
}
function New-Table($ns, $name) {
    $body = '{"name":"' + $name + '","schema":' + $schema + '}'
    try {
        Invoke-RestMethod -Method Post -Uri "$base/v1/namespaces/$ns/tables" -ContentType 'application/json' -Body $body | Out-Null
        Write-Host "table $ns.$name -> created"
    } catch {
        Write-Host "table $ns.$name -> $($_.Exception.Message)"
    }
}
New-Table sales orders
New-Table sales returns
New-Table marketing campaigns
New-Table finance ledger
Write-Host ''
Write-Host "namespaces now: $((Invoke-WebRequest -UseBasicParsing -Uri "$base/v1/namespaces").Content)"
