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
# Runs the whole client-language demo: example inventory service, gateway, then one client per language.
# See README.md for what it proves and the recorded output.
#
#   pwsh ./run-demo.ps1

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$jar = Join-Path $here '..\..\aegis-gateway\target\aegis-gateway-2.0.0-SNAPSHOT-all.jar'
if (-not (Test-Path $jar)) { throw "Build first: mvn -B clean install (missing $jar)" }

$env:AEGIS_SIGNING_KEY = 'demo-signing-key-0123456789-abcdefghijkl'
$env:ANALYST_TOKEN     = 'analyst-token-aaaa'
$env:OPERATOR_TOKEN    = 'operator-token-bbbb'
$env:APPROVER_TOKEN    = 'approver-token-cccc'

$service = Start-Process python -ArgumentList "$here\example_inventory_service.py",'9109' -PassThru -WindowStyle Hidden
$gw = Start-Process java -ArgumentList '-jar',$jar,'--config',"$here\aegis-clients-demo.yaml" `
    -PassThru -WindowStyle Hidden -WorkingDirectory $here

try {
    # Wait for readiness instead of sleeping a guessed amount.
    $ready = $false
    foreach ($i in 1..40) {
        try {
            if ((Invoke-WebRequest 'http://127.0.0.1:8099/readyz' -UseBasicParsing -TimeoutSec 2).StatusCode -eq 200) {
                $ready = $true; break
            }
        } catch { Start-Sleep -Milliseconds 500 }
    }
    if (-not $ready) { throw 'gateway did not become ready' }

    Write-Host "`n--- python ---"
    python "$here\python\aegis_client.py" http://127.0.0.1:8099/mcp

    Write-Host "`n--- node ---"
    node "$here\node\aegis-client.mjs" http://127.0.0.1:8099/mcp

    Write-Host "`n--- rust ---"
    Push-Location "$here\rust"
    try {
        # A linker is needed to produce an executable. Where one is missing, at least prove the client
        # compiles and type-checks, and say so rather than skipping silently.
        rustc -O aegis_client.rs -o aegis_client.exe 2>$null
        if ($LASTEXITCODE -eq 0) {
            & .\aegis_client.exe 127.0.0.1 8099
        } else {
            rustc --emit=metadata --edition 2021 -o "$env:TEMP\aegis_client.rmeta" aegis_client.rs
            if ($LASTEXITCODE -eq 0) {
                Write-Host 'rust client compiles and type-checks, but no linker is installed to produce an executable'
            } else {
                Write-Host 'rust client FAILED to compile'
            }
        }
    } finally { Pop-Location }

    Write-Host "`n--- what the backend actually received ---"
    (Invoke-WebRequest 'http://127.0.0.1:9109/_received' -UseBasicParsing).Content
}
finally {
    foreach ($p in @($gw, $service)) {
        if ($p -and -not $p.HasExited) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }
    }
}
