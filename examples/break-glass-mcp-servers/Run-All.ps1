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

<#
.SYNOPSIS
  Runs each MCP server's own Run-EndToEnd.ps1, one after another, each in its own PowerShell process.
.PARAMETER Servers
  Any of flink, iceberg, spark, kafka (default: all four). "-Servers kafka,spark" also works with -File.
#>
param([string[]]$Servers = @('flink', 'iceberg', 'spark', 'kafka'))
$Servers = @($Servers | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$rows = @()
foreach ($s in $Servers) {
    $script = Join-Path $PSScriptRoot "$s\Run-EndToEnd.ps1"
    if (-not (Test-Path $script)) { throw "unknown MCP server '$s'" }
    & powershell -NoProfile -ExecutionPolicy Bypass -File $script
    $rows += [pscustomobject]@{ Server = $s; Passed = ($LASTEXITCODE -eq 0) }
}
$md = "# Break-glass MCP servers: end-to-end summary`n`nRun $(Get-Date -Format s).`n`n| MCP server | Result | Details |`n| --- | --- | --- |`n"
foreach ($r in $rows) {
    $res = 'FAIL'; if ($r.Passed) { $res = 'PASS' }
    $md += "| $($r.Server) | $res | [$($r.Server)/run/RESULT.md]($($r.Server)/run/RESULT.md) |`n"
}
$summary = Join-Path $PSScriptRoot 'SUMMARY.generated.md'
[System.IO.File]::WriteAllText($summary, $md, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "`n$(@($rows | Where-Object { $_.Passed }).Count) of $($rows.Count) MCP servers passed. $summary"
if (@($rows | Where-Object { -not $_.Passed }).Count -gt 0) { exit 1 }
exit 0
