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
  Installs the two downstream MCP servers this example fronts, at pinned versions, outside the repository:
  the official MCP Python SDK (mcp==2.2.0) in a virtual environment, and the official
  @modelcontextprotocol/server-filesystem 2026.8.31. Prints the environment variables the test reads.
.EXAMPLE
  ./setup.ps1 -Destination C:\aegis-engines
#>
param([Parameter(Mandatory = $true)][string]$Destination)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force $Destination | Out-Null
$venv = Join-Path $Destination 'py-mcp'
python -m venv $venv
& "$venv\Scripts\python.exe" -m pip install --disable-pip-version-check 'mcp==2.2.0'
if ($LASTEXITCODE -ne 0) { throw 'pip install mcp failed' }
$fs = Join-Path $Destination 'mcp-filesystem'
New-Item -ItemType Directory -Force $fs | Out-Null
Push-Location $fs
npm install --no-fund --no-audit --save-exact '@modelcontextprotocol/server-filesystem@2026.8.31'
$rc = $LASTEXITCODE
Pop-Location
if ($rc -ne 0) { throw 'npm install failed' }
$nodeExe = (Get-Command node).Source
Write-Host "Set:"
Write-Host "  `$env:AEGIS_MCP_PYTHON = '$venv\Scripts\python.exe'"
Write-Host "  `$env:AEGIS_NODE = '$nodeExe'"
Write-Host "  `$env:AEGIS_MCP_FILESYSTEM_JS = '$fs\node_modules\@modelcontextprotocol\server-filesystem\dist\index.js'"
