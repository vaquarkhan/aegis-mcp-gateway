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
  Installs Apache Burr 0.42.0 with its tracking server in a virtual environment outside the repository,
  and prints the AEGIS_BURR_PYTHON variable the example test reads. setuptools is pinned too: the 0.42.0
  server's /api/v0/version route imports pkg_resources, which current Python venvs no longer include.
.EXAMPLE
  ./setup.ps1 -Destination C:\aegis-engines
#>
param([Parameter(Mandatory = $true)][string]$Destination)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force $Destination | Out-Null
$venv = Join-Path $Destination 'py-burr'
python -m venv $venv
& "$venv\Scripts\python.exe" -m pip install --disable-pip-version-check 'burr[tracking]==0.42.0' 'setuptools==80.9.0'
if ($LASTEXITCODE -ne 0) { throw 'pip install failed' }
Write-Host "Set: `$env:AEGIS_BURR_PYTHON = '$venv\Scripts\python.exe'"
