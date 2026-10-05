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
# Uploads the same Flink example jar twice, under two names:
#   approved-StateMachineExample.jar   matches the run-approved-jars policy rule
#   StateMachineExample.jar            does not, and stays unrunnable through Aegis
#
# This runs against Flink directly, NOT through the gateway, on purpose: POST /jars/upload is a
# multipart/form-data upload and Aegis 2.0 has no governed tool for it (an HTTP source builds a JSON
# body). Uploading a jar is a release step an operator or CI does; running one is the governed action.
$ErrorActionPreference = 'Stop'
if (-not $env:FLINK_HOME) { Write-Error 'Set $env:FLINK_HOME to the same Apache Flink distribution start-flink.ps1 used' }
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$src = Join-Path (Resolve-Path $env:FLINK_HOME) 'examples\streaming\StateMachineExample.jar'
if (-not (Test-Path $src)) { Write-Error "Missing $src" }
$staging = Join-Path $here 'work\staging'
New-Item -ItemType Directory -Force -Path $staging | Out-Null
Copy-Item $src (Join-Path $staging 'approved-StateMachineExample.jar') -Force
Copy-Item $src (Join-Path $staging 'StateMachineExample.jar') -Force
foreach ($f in 'approved-StateMachineExample.jar', 'StateMachineExample.jar') {
    Write-Host "Uploading $f"
    # curl.exe ships with Windows 10 1803 and later; Invoke-WebRequest cannot post multipart on
    # Windows PowerShell 5.1.
    & curl.exe -s -X POST -H 'Expect:' -F "jarfile=@$staging\$f" http://127.0.0.1:18081/jars/upload
    Write-Host ''
}
Write-Host ''
Write-Host 'Jar ids the JobManager assigned (note the uuid prefix Flink adds):'
& curl.exe -s http://127.0.0.1:18081/jars
Write-Host ''
