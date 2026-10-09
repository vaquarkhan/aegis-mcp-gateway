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
# Own-host flavor: starts the Aegis gateway on http://127.0.0.1:8080/mcp with the tutorial governance
# (aegis-spark-iceberg.yaml) plus the development identities (host-own.yaml).
#   .\start-gateway.ps1            HTTP, for curl, scripts and IDEs that connect to a URL
#   .\start-gateway.ps1 -Stdio     stdio, for IDEs that launch the MCP server as a process
#   .\start-gateway.ps1 -WriteConfigOnly   only write the two generated configurations, then exit
# Needs AEGIS_SIGNING_KEY, READER_TOKEN, ENGINEER_TOKEN and APPROVER_TOKEN in the environment.
param([switch]$Stdio, [switch]$WriteConfigOnly)
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = (Resolve-Path (Join-Path $here '..\..\..')).Path
$jar = Join-Path $repo 'examples\aegis-gateway-spark\target\aegis-gateway-spark-2.0.0-SNAPSHOT-all.jar'
if (-not (Test-Path $jar)) { Write-Error "Missing $jar. Run: mvn -B -DskipTests install" }
foreach ($v in 'AEGIS_SIGNING_KEY', 'READER_TOKEN', 'ENGINEER_TOKEN', 'APPROVER_TOKEN') {
    if (-not [Environment]::GetEnvironmentVariable($v)) { Write-Error "Set `$env:$v first (tutorial step 3)" }
}
# One governance file for both flavors; the own host adds who the callers are. The stdio variant keeps
# its own audit journal, because one journal file has exactly one writer and the HTTP gateway may be running.
$text = (Get-Content -Raw (Join-Path $here 'aegis-spark-iceberg.yaml')) + "`n" + (Get-Content -Raw (Join-Path $here 'host-own.yaml'))
$utf8 = New-Object System.Text.UTF8Encoding($false)
$httpConfig = Join-Path $here 'aegis-own-host.local.yaml'
$stdioConfig = Join-Path $here 'aegis-stdio.local.yaml'
[System.IO.File]::WriteAllText($httpConfig, $text, $utf8)
[System.IO.File]::WriteAllText($stdioConfig, $text.Replace('work/audit/aegis-spark-iceberg.jsonl', 'work/audit/aegis-spark-iceberg-stdio.jsonl'), $utf8)
if ($WriteConfigOnly) { Write-Host "Wrote $httpConfig and $stdioConfig"; return }
$config = if ($Stdio) { $stdioConfig } else { $httpConfig }
$opens = @('--add-opens=java.base/java.nio=ALL-UNNAMED', '--add-opens=java.base/sun.nio.ch=ALL-UNNAMED',
    '--add-opens=java.base/java.lang=ALL-UNNAMED', '--add-opens=java.base/java.util=ALL-UNNAMED')
$mode = if ($Stdio) { @('--stdio') } else { @() }
# Windows PowerShell turns any JVM notice on stderr (for example JAVA_TOOL_OPTIONS) into an error
# record; with 'Stop' that would end the script, so native-command errors are left to the JVM.
$ErrorActionPreference = 'Continue'
& java @opens -jar $jar --config $config @mode
