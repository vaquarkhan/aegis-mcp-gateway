# SPDX-License-Identifier: Apache-2.0
# https://www.apache.org/licenses/LICENSE-2.0
$ErrorActionPreference = 'Continue'
$root = $PSScriptRoot
$repo = (Resolve-Path (Join-Path $root '..\..')).Path
$jar = Join-Path $repo 'aegis-gateway\target\aegis-gateway-2.0.0-SNAPSHOT-all.jar'
if (-not (Test-Path $jar)) {
    Write-Host "JAR_MISSING: $jar (run mvn -B -DskipTests install from the repo root first)"
    exit 4
}
foreach ($port in 18771, 18772) {
    Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue |
        ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue }
}
Remove-Item -Force -ErrorAction SilentlyContinue "$root\pins.json","$root\audit.jsonl","$root\hits.json","$root\results.json","$root\RESULTS.md","$root\gateway.log","$root\vulnerable.log","$root\driver.log"
$yaml = (Get-Content "$root\aegis.yaml" -Raw).Replace('UPSTREAM_PORT','18771')
[System.IO.File]::WriteAllText("$root\aegis.resolved.yaml", $yaml)
$env:VULN_PORT = '18771'
$env:AEGIS_E2E_ANALYST = 'analyst-token-e2e-0123456789'
$env:AEGIS_E2E_OPERATOR = 'operator-token-e2e-0123456789'
$env:AEGIS_E2E_SIGNING_KEY = '0123456789abcdef0123456789abcdef'
$vuln = Start-Process -FilePath python -ArgumentList "$root\vulnerable_mcp.py" -WorkingDirectory $root -RedirectStandardOutput "$root\vulnerable.log" -RedirectStandardError "$root\vulnerable.err" -PassThru -WindowStyle Hidden
$ready = $false
for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Milliseconds 300
    if (Test-Path "$root\vulnerable.log") {
        $t = Get-Content "$root\vulnerable.log" -Raw -ErrorAction SilentlyContinue
        if ($t -match 'listening') { $ready = $true; break }
    }
    if ($vuln.HasExited) { break }
}
if (-not $ready) { "VULN_NOT_READY"; Get-Content "$root\vulnerable.log","$root\vulnerable.err" -ErrorAction SilentlyContinue; exit 2 }
$gw = Start-Process -FilePath java -ArgumentList @('-jar', $jar, '--config', "$root\aegis.resolved.yaml") -WorkingDirectory $root -RedirectStandardOutput "$root\gateway.log" -RedirectStandardError "$root\gateway.err" -PassThru -WindowStyle Hidden
$gready = $false
for ($i = 0; $i -lt 40; $i++) {
    Start-Sleep -Milliseconds 500
    $err = Get-Content "$root\gateway.err" -Raw -ErrorAction SilentlyContinue
    $out = Get-Content "$root\gateway.log" -Raw -ErrorAction SilentlyContinue
    if (($out + $err) -match 'listening') { $gready = $true; break }
    if ($gw.HasExited) { break }
}
if (-not $gready) {
    "GATEWAY_NOT_READY"
    Get-Content "$root\gateway.err" -Tail 40 -ErrorAction SilentlyContinue
    Get-Content "$root\gateway.log" -Tail 20 -ErrorAction SilentlyContinue
    Stop-Process -Id $vuln.Id -Force -ErrorAction SilentlyContinue
    exit 3
}
& python "$root\drive.py" *> "$root\driver.log"
"DRIVER_EXIT=$LASTEXITCODE"
Stop-Process -Id $gw.Id,$vuln.Id -Force -ErrorAction SilentlyContinue
"DONE"
