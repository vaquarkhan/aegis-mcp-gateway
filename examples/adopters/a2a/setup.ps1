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
  Installs the official A2A Python SDK 1.2.1 with its HTTP server extras in a virtual environment outside
  the repository, and prints the AEGIS_A2A_PYTHON variable the example test reads.
.EXAMPLE
  ./setup.ps1 -Destination C:\aegis-engines
#>
param([Parameter(Mandatory = $true)][string]$Destination)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force $Destination | Out-Null
$venv = Join-Path $Destination 'py-a2a'
python -m venv $venv
& "$venv\Scripts\python.exe" -m pip install --disable-pip-version-check 'a2a-sdk[http-server]==1.2.1' 'uvicorn==0.54.0'
if ($LASTEXITCODE -ne 0) { throw 'pip install failed' }
Write-Host "Set: `$env:AEGIS_A2A_PYTHON = '$venv\Scripts\python.exe'"
