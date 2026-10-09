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
  Downloads the official Apache Knox 3.0.0 binary distribution, checks its published SHA-512, and
  unpacks it. Point AEGIS_KNOX_HOME at the printed directory to run the Knox front door.
.EXAMPLE
  ./install-knox.ps1 -Destination C:\knox
#>
param([Parameter(Mandatory = $true)][string]$Destination)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
New-Item -ItemType Directory -Force $Destination | Out-Null
$zip = Join-Path $Destination 'knox-3.0.0.zip'
$sha = "$zip.sha512"
# dlcdn serves the current release quickly; archive.apache.org keeps every release and is the fallback.
$sources = 'https://dlcdn.apache.org/knox/3.0.0', 'https://archive.apache.org/dist/knox/3.0.0'
foreach ($s in $sources) {
    curl.exe -fsSL --retry 3 -o $zip "$s/knox-3.0.0.zip"
    if ($LASTEXITCODE -eq 0) { break }
}
curl.exe -fsSL -o $sha 'https://downloads.apache.org/knox/3.0.0/knox-3.0.0.zip.sha512'
if ($LASTEXITCODE -ne 0) { curl.exe -fsSL -o $sha 'https://archive.apache.org/dist/knox/3.0.0/knox-3.0.0.zip.sha512' }
$expected = (Get-Content $sha -Raw).Trim().Split(' ')[0].ToLower()
$actual = (Get-FileHash -Algorithm SHA512 $zip).Hash.ToLower()
if ($expected -ne $actual) { throw "SHA-512 mismatch for $zip" }
Expand-Archive -Force $zip $Destination
$knoxHome = Join-Path $Destination 'knox-3.0.0'
Write-Host "SHA-512 verified. Set: `$env:AEGIS_KNOX_HOME = '$knoxHome'"
