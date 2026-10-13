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
# Hello World: starts the example inventory service (Python standard library only) on
# 127.0.0.1:9109 and the Aegis gateway on http://127.0.0.1:8080/mcp, both in the background, waits
# until both the gateway and the inventory service answer, then returns so you can keep using the same terminal (and the same
# token variables). Stop both with .\stop-hello.ps1.
#   .\start-hello.ps1              # gateway on port 8080
#   .\start-hello.ps1 -Port 18080  # if 8080 is already in use on this machine
# Needs AEGIS_SIGNING_KEY, ANALYST_TOKEN, OPERATOR_TOKEN and APPROVER_TOKEN (see the guide, step 2).
param([int]$Port = 8080)
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = (Resolve-Path (Join-Path $here '..\..\..')).Path
$jar = Join-Path $repo 'aegis-gateway\target\aegis-gateway-2.0.0-SNAPSHOT-all.jar'
$service = Join-Path $repo 'adopters\clients\example_inventory_service.py'
$work = Join-Path $here 'work'
if (-not (Test-Path $jar)) { throw "Missing $jar. From the repository root run: mvn -B -DskipTests install" }
foreach ($v in 'AEGIS_SIGNING_KEY', 'ANALYST_TOKEN', 'OPERATOR_TOKEN', 'APPROVER_TOKEN') {
    if (-not [Environment]::GetEnvironmentVariable($v)) { throw "Set `$env:$v first (see the Hello World guide, step 2)" }
}
$python = Get-Command python -ErrorAction SilentlyContinue
if (-not $python) { $python = Get-Command py -ErrorAction SilentlyContinue }
if (-not $python) { throw 'Python 3 is required for the example inventory service (python or py on PATH)' }
$java = Get-Command java -ErrorAction SilentlyContinue
if (-not $java) { throw 'Java 17 or newer is required (java on PATH)' }
foreach ($p in 9109, $Port) {
    if (Get-NetTCPConnection -State Listen -LocalPort $p -ErrorAction SilentlyContinue) {
        throw "Port $p is already in use. Run .\stop-hello.ps1, stop that process, or pass -Port <free port>."
    }
}
New-Item -ItemType Directory -Force -Path $work | Out-Null

$config = Join-Path $here 'aegis-hello-world.yaml'
if ($Port -ne 8080) {
    # Keep the checked-in file unchanged; write a gitignored local copy with the other port. It sits
    # next to the original because relative paths in a config resolve against the config's folder.
    $config = Join-Path $here 'aegis-hello-world.local.yaml'
    $text = [IO.File]::ReadAllText((Join-Path $here 'aegis-hello-world.yaml')) -replace 'port: 8080\}', "port: $Port}"
    [IO.File]::WriteAllText($config, $text, (New-Object Text.UTF8Encoding($false)))
}

# Child processes inherit this terminal's environment, including the four variables above.
$svc = Start-Process -FilePath $python.Source -ArgumentList "`"$service`"", '9109' -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $work 'inventory.log') -RedirectStandardError (Join-Path $work 'inventory.err.log')
# WorkingDirectory is this folder because the audit journal path in the configuration is relative.
$gw = Start-Process -FilePath $java.Source -ArgumentList '-jar', "`"$jar`"", '--config', "`"$config`"" -PassThru `
    -WindowStyle Hidden -WorkingDirectory $here `
    -RedirectStandardOutput (Join-Path $work 'gateway.log') -RedirectStandardError (Join-Path $work 'gateway.err.log')
Set-Content -Path (Join-Path $work 'pids.txt') -Value @($svc.Id, $gw.Id) -Encoding ascii

$gatewayReady = $false
$inventoryReady = $false
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Milliseconds 500
    if ($gw.HasExited -or $svc.HasExited) { break }
    # Both probes must succeed in this iteration. /_received does not add a tutorial request.
    $gatewayReady = $false
    $inventoryReady = $false
    try {
        if ((Invoke-WebRequest "http://127.0.0.1:$Port/readyz" -UseBasicParsing -TimeoutSec 2).StatusCode -eq 200) { $gatewayReady = $true }
    } catch { }
    try {
        if ((Invoke-WebRequest 'http://127.0.0.1:9109/_received' -UseBasicParsing -TimeoutSec 2).StatusCode -eq 200) { $inventoryReady = $true }
    } catch { }
    if ($gatewayReady -and $inventoryReady) { break }
}
if (-not $gatewayReady -or -not $inventoryReady) {
    if (-not $gatewayReady) {
        Write-Host 'The gateway did not become ready. Last lines of work\gateway.err.log:'
        Get-Content (Join-Path $work 'gateway.err.log') -Tail 15 -ErrorAction SilentlyContinue
    }
    if (-not $inventoryReady) {
        Write-Host 'The inventory service is not answering on http://127.0.0.1:9109/_received. Last lines of work\inventory.err.log:'
        Get-Content (Join-Path $work 'inventory.err.log') -Tail 15 -ErrorAction SilentlyContinue
    }
    & (Join-Path $here 'stop-hello.ps1')
    throw 'Hello World did not start; see the log lines above.'
}
Write-Host "Aegis is ready on http://127.0.0.1:$Port/mcp (governing the inventory service on 127.0.0.1:9109)."
Write-Host 'Stop both with .\stop-hello.ps1'
