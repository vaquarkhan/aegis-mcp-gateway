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
  End-to-end run of the Apache Iceberg MCP server in this folder, against its real engine.
.DESCRIPTION
  Starts the engine, reviews the definitions (aegis.yaml without serverCatalog), signs the server catalog
  with the gateway jar, serves aegis.yaml itself and checks real MCP calls, proves an edited source is
  refused, signs and applies a break-glass order with a separate key, and verifies the audit journal.
  Results: run\RESULT.md. Exit code 0 only when every check passed. Stops everything it started.
#>
Import-Module (Join-Path $PSScriptRoot '..\lib\BreakGlassMcp.psm1') -Force -DisableNameChecking
$ErrorActionPreference = 'Stop'
$failed = Invoke-BgEndToEnd 'iceberg'
if ($failed -gt 0) { exit 1 }
exit 0
