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
# Knox flavor: installs Aegis into a stock Apache Knox 3.0.0 the way an operator would.
#   1. the Aegis extension (knox-bundle jar) and the spark-connect source plugin into ext/
#      (jars Knox already ships are kept, logging jars are never added)
#   2. the tutorial governance file into conf/aegis/, and the topology into conf/topologies/
#   3. a master secret and a localhost TLS certificate, if Knox has none yet
#   .\install-into-knox.ps1 -KnoxHome C:\knox\knox-3.0.0
# Needs: mvn -B -Preal-engines -DskipTests -pl :aegis-spark-connect install  (and mvn -B -DskipTests install)
param([string]$KnoxHome = $env:AEGIS_KNOX_HOME)
$ErrorActionPreference = 'Stop'
if (-not $KnoxHome -or -not (Test-Path (Join-Path $KnoxHome 'bin\gateway.jar'))) {
    Write-Error 'Pass -KnoxHome (a Knox 3.0.0 directory; examples/adopters/knox/install-knox.ps1 installs one)'
}
$KnoxHome = (Resolve-Path $KnoxHome).Path
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$tutorial = Split-Path -Parent $here
$repo = (Resolve-Path (Join-Path $tutorial '..\..\..')).Path
$bundle = Join-Path $repo 'aegis-knox3-extension\target\aegis-knox3-extension-2.0.0-SNAPSHOT-knox-bundle.jar'
$plugin = Join-Path $repo 'examples\aegis-spark-connect\target\aegis-spark-connect-2.0.0-SNAPSHOT.jar'
$pluginCp = Join-Path $repo 'examples\aegis-spark-connect\target\plugin.classpath'
foreach ($f in $bundle, $plugin, $pluginCp) {
    if (-not (Test-Path $f)) { Write-Error "Missing $f. Run: mvn -B -DskipTests install; mvn -B -Preal-engines -DskipTests -pl :aegis-spark-connect install" }
}
$ext = Join-Path $KnoxHome 'ext'
$bundleDestination = Join-Path $ext (Split-Path $bundle -Leaf)
if (Test-Path $bundleDestination) {
    Write-Error "Refusing to overwrite existing tutorial extension $bundleDestination. Remove only that reviewed file before reinstalling."
}
Copy-Item $bundle $bundleDestination
# The plugin and its runtime jars; Knox's own copy wins for anything it already ships.
$shipped = @{}
foreach ($dir in 'lib', 'dep') {
    Get-ChildItem (Join-Path $KnoxHome $dir) -Filter '*.jar' -ErrorAction SilentlyContinue |
        ForEach-Object { $shipped[($_.BaseName -replace '-\d[\w.\-]*$', '')] = $true }
}
$jars = @($plugin) + ((Get-Content -Raw $pluginCp).Trim() -split ';' | Where-Object { $_ })
foreach ($j in $jars) {
    $name = Split-Path $j -Leaf
    $artifact = ([IO.Path]::GetFileNameWithoutExtension($name)) -replace '-\d[\w.\-]*$', ''
    if ($name -match '^(log4j|slf4j|logback)|-to-slf4j|-over-slf4j') { Write-Host "SKIP $name (logging)"; continue }
    if ($shipped.ContainsKey($artifact)) { Write-Host "KEEP $name (Knox ships $artifact)"; continue }
    $destination = Join-Path $ext "aegis-plugin-$name"
    if (Test-Path $destination) { Write-Error "Refusing to overwrite existing tutorial extension $destination" }
    Copy-Item $j $destination
    Write-Host "ADD  $name"
}
New-Item -ItemType Directory -Force (Join-Path $KnoxHome 'conf\aegis') | Out-Null
Copy-Item (Join-Path $tutorial 'aegis-spark-iceberg.yaml') (Join-Path $KnoxHome 'conf\aegis\aegis-spark-iceberg.yaml') -Force
$topology = (Get-Content -Raw (Join-Path $here 'aegis-spark-iceberg.xml')).Replace('@KNOX_HOME@', ($KnoxHome -replace '\\', '/'))
[System.IO.File]::WriteAllText((Join-Path $KnoxHome 'conf\topologies\aegis-spark-iceberg.xml'), $topology,
    (New-Object System.Text.UTF8Encoding($false)))
$ErrorActionPreference = 'Continue'
if (-not (Test-Path (Join-Path $KnoxHome 'data\security\master'))) {
    if (-not $env:AEGIS_KNOX_MASTER_SECRET) {
        Write-Error 'Set AEGIS_KNOX_MASTER_SECRET to a newly generated value before installing Knox tutorial assets.'
    }
    & java -jar (Join-Path $KnoxHome 'bin\knoxcli.jar') create-master --master $env:AEGIS_KNOX_MASTER_SECRET
}
& java -jar (Join-Path $KnoxHome 'bin\knoxcli.jar') create-cert --hostname localhost
Write-Host "Installed. Start Knox with .\start-knox.ps1 -KnoxHome $KnoxHome"
