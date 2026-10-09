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
  Starts the Apache Iceberg engine and this folder's MCP server, and leaves them running.
.PARAMETER Mode
  signed (default): serves aegis.yaml as it is. break-glass: serves aegis.yaml with its breakGlass block
  enabled (the order from the last Run-EndToEnd.ps1, valid for 60 minutes; afterwards start-up is refused).
#>
param([ValidateSet('signed', 'break-glass')][string]$Mode = 'signed')
Import-Module (Join-Path $PSScriptRoot '..\lib\BreakGlassMcp.psm1') -Force -DisableNameChecking
$ErrorActionPreference = 'Stop'
Start-BgMcpServer 'iceberg' $Mode
