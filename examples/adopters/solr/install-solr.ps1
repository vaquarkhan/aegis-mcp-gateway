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
  Downloads the official Apache Solr 9.10.1 slim binary distribution, checks its published SHA-512, and
  unpacks it. Point AEGIS_SOLR_HOME at the printed directory to run the example. Solr 9 runs on Java 11+;
  Solr 10 needs Java 21.
.EXAMPLE
  ./install-solr.ps1 -Destination C:\solr
#>
param([Parameter(Mandatory = $true)][string]$Destination)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$version = '9.10.1'
$name = "solr-$version-slim.tgz"
New-Item -ItemType Directory -Force $Destination | Out-Null
$tgz = Join-Path $Destination $name
$sources = "https://dlcdn.apache.org/solr/solr/$version", "https://archive.apache.org/dist/solr/solr/$version"
foreach ($s in $sources) {
    curl.exe -fsSL --retry 3 -o $tgz "$s/$name"
    if ($LASTEXITCODE -eq 0) { break }
}
curl.exe -fsSL -o "$tgz.sha512" "https://downloads.apache.org/solr/solr/$version/$name.sha512"
if ($LASTEXITCODE -ne 0) { curl.exe -fsSL -o "$tgz.sha512" "https://archive.apache.org/dist/solr/solr/$version/$name.sha512" }
$expected = (Get-Content "$tgz.sha512" -Raw).Trim().Split(' ')[0].ToLower()
$actual = (Get-FileHash -Algorithm SHA512 $tgz).Hash.ToLower()
if ($expected -ne $actual) { throw "SHA-512 mismatch for $tgz" }
tar.exe -xzf $tgz -C $Destination
if ($LASTEXITCODE -ne 0) { throw 'unpack failed' }
$solrHome = Join-Path $Destination "solr-$version-slim"
Write-Host "SHA-512 verified. Set: `$env:AEGIS_SOLR_HOME = '$solrHome'"
