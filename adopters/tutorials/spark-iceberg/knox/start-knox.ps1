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
# Knox flavor: starts Knox's demo LDAP (port 33389) in the background and the Knox gateway in the
# foreground on https://localhost:8443. The gateway gets Spark Connect's documented Java 17 options,
# because the spark-connect source runs inside the Knox JVM. Needs AEGIS_SIGNING_KEY in the environment.
#   .\start-knox.ps1 -KnoxHome C:\knox\knox-3.0.0
param([string]$KnoxHome = $env:AEGIS_KNOX_HOME)
$ErrorActionPreference = 'Stop'
if (-not $KnoxHome -or -not (Test-Path (Join-Path $KnoxHome 'bin\gateway.jar'))) { Write-Error 'Pass -KnoxHome' }
if (-not $env:AEGIS_SIGNING_KEY) { Write-Error 'Set $env:AEGIS_SIGNING_KEY first (tutorial step 3)' }
$KnoxHome = (Resolve-Path $KnoxHome).Path
$listening = Get-NetTCPConnection -State Listen -LocalPort 33389 -ErrorAction SilentlyContinue
$ldap = $null
if (-not $listening) {
    $ldap = Start-Process -FilePath java -ArgumentList @('-jar', 'bin\ldap.jar', 'conf') -WorkingDirectory $KnoxHome -WindowStyle Hidden -PassThru
    Write-Host "Started Knox demo LDAP on ldap://localhost:33389 (pid $($ldap.Id)); it stops when Knox stops"
} else {
    Write-Host 'Knox demo LDAP is already listening on 33389; this script did not start it and will not stop it'
}
$opens = @('java.lang', 'java.lang.invoke', 'java.lang.reflect', 'java.io', 'java.net', 'java.nio', 'java.util',
    'java.util.concurrent', 'java.util.concurrent.atomic', 'sun.nio.ch', 'sun.nio.cs', 'sun.security.action',
    'sun.util.calendar') | ForEach-Object { "--add-opens=java.base/$_=ALL-UNNAMED" }
Write-Host 'Starting Knox on https://localhost:8443 (Ctrl+C to stop)'
Push-Location $KnoxHome
try {
# Windows PowerShell turns any JVM notice on stderr (for example JAVA_TOOL_OPTIONS) into an error
# record; with 'Stop' that would end the script, so native-command errors are left to the JVM.
$ErrorActionPreference = 'Continue'
    & java @opens '-Djdk.reflect.useDirectMethodHandle=false' '-Dio.netty.tryReflectionSetAccessible=true' -jar bin\gateway.jar
} finally {
    Pop-Location
    # Runs when the gateway exits and on Ctrl+C. An LDAP this script did not start is left alone.
    if ($ldap -and -not $ldap.HasExited) {
        Stop-Process -Id $ldap.Id -Force -ErrorAction SilentlyContinue
        Write-Host "Stopped Knox demo LDAP (pid $($ldap.Id))"
    }
}
