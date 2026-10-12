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
  Runs one adopter example through both front doors (own host, and inside Apache Knox 3.0.0)
  against the real engine, and optionally keeps the result logs in examples/adopters/<adopter>/results.
.EXAMPLE
  ./run-example.ps1 flink -KnoxHome C:\knox\knox-3.0.0 -Keep
#>
param(
    [Parameter(Mandatory = $true)][string]$Adopter,
    [string]$KnoxHome = $env:AEGIS_KNOX_HOME,
    [switch]$Keep
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
# adopter -> Maven module that holds its two-front-door test, and the test class.
# Module paths are relative to examples/pom.xml, which owns the real-engines profile.
$examples = @{
    'flink'         = @('aegis-validation', 'FlinkFrontDoorsTest')
    'spark'         = @('aegis-validation', 'SparkFrontDoorsTest')
    'zookeeper'     = @('aegis-validation', 'ZooKeeperFrontDoorsTest')
    'calcite'       = @('aegis-validation', 'CalciteFrontDoorsTest')
    'kafka'         = @('aegis-validation', 'KafkaFrontDoorsTest')
    'iceberg'       = @('aegis-validation', 'IcebergFrontDoorsTest')
    'activemq'      = @('aegis-validation', 'ActiveMqFrontDoorsTest')
    'couchdb'       = @('aegis-validation', 'CouchdbFrontDoorsTest')   # needs Docker (official couchdb image)
    'pinot'         = @('aegis-validation', 'PinotFrontDoorsTest')     # needs Docker (apachepinot/pinot QuickStart)
    'hadoop'        = @('aegis-validation', 'HadoopFrontDoorsTest')     # needs Docker (bde2020 namenode+datanode)
    'nifi'          = @('aegis-validation', 'NifiFrontDoorsTest')       # needs Docker (apache/nifi)
    'airflow'       = @('aegis-validation', 'AirflowFrontDoorsTest')    # needs Docker (apache/airflow standalone)
    'superset'      = @('aegis-validation', 'SupersetFrontDoorsTest')   # needs Docker (apache/superset)
    'storm'         = @('aegis-validation', 'StormFrontDoorsTest')      # needs Docker (storm + zookeeper)
    'doris'         = @('aegis-validation', 'DorisFrontDoorsTest')      # needs Docker (apache/doris)
    'druid'         = @('aegis-validation', 'DruidFrontDoorsTest')      # needs Docker (apache/druid nano-quickstart)
    'hbase'         = @('aegis-validation', 'HbaseFrontDoorsTest')      # needs Docker (dajobe/hbase)
    'hive'          = @('aegis-validation', 'HiveFrontDoorsTest')       # needs Docker (apache/hive HiveServer2)
    'ignite'        = @('aegis-validation', 'IgniteFrontDoorsTest')
    # needs AEGIS_MCP_PYTHON, AEGIS_NODE, AEGIS_MCP_FILESYSTEM_JS (existing-mcp-server/setup.ps1)
    'existing-mcp-server' = @('aegis-validation', 'ExistingMcpServerFrontDoorsTest')
    'burr'          = @('aegis-validation', 'BurrFrontDoorsTest')     # needs AEGIS_BURR_PYTHON (burr/setup.ps1)
    'a2a'           = @('aegis-validation', 'A2aAgentFrontDoorsTest') # needs AEGIS_A2A_PYTHON (a2a/setup.ps1)
    'solr'          = @('aegis-validation', 'SolrFrontDoorsTest')     # needs AEGIS_SOLR_HOME (solr/install-solr.ps1)
    'flume'         = @('aegis-validation', 'FlumeFrontDoorsTest')
    'bookkeeper'    = @('aegis-validation', 'BookKeeperFrontDoorsTest')
    'pulsar'        = @('aegis-validation', 'PulsarFrontDoorsTest')   # needs AEGIS_PULSAR_HOME (pulsar/install-pulsar.ps1)
    'ranger'        = @('aegis-validation', 'RangerFrontDoorsTest')     # needs Docker (apache/ranger and apache/ranger-db)
    'spark-connect' = @('aegis-validation-spark-connect', 'SparkConnectFrontDoorsTest')
    'spark-iceberg' = @('aegis-validation-spark-connect', 'SparkIcebergFrontDoorsTest')   # docs/TUTORIAL-SPARK-ICEBERG.md
}
if (-not $examples.ContainsKey($Adopter)) {
    throw "no runnable example for '$Adopter'; runnable: $($examples.Keys -join ', ')"
}
$module, $test = $examples[$Adopter]
if ($KnoxHome) {
    $env:AEGIS_KNOX_HOME = (Resolve-Path $KnoxHome).Path
} else {
    Write-Warning 'AEGIS_KNOX_HOME is not set: the Knox front door will be reported as SKIPPED, not passed.'
}
$mvn = if ($env:MAVEN_CMD) { $env:MAVEN_CMD } elseif (Get-Command mvn -ErrorAction SilentlyContinue) { 'mvn' } else {
    throw 'Maven not found: put mvn on PATH or set MAVEN_CMD to mvn.cmd' }
# Windows PowerShell turns any stderr line of a native command into an error record; Maven and the JVM
# write harmless notices there, so native exit codes are checked explicitly instead.
$ErrorActionPreference = 'Continue'

# The Knox front door installs the Knox bundle and source plugins. Build the root reactor first; its POM
# intentionally does not declare the examples-only real-engines profile.
& $mvn -B -q -f "$root\pom.xml" -DskipTests install
if ($LASTEXITCODE -ne 0) { throw 'reactor install failed' }
# Knox copies typed source jars plus target/plugin.classpath from the root reactor. That file is written
# only with -Preal-engines on the source module (see aegis-source-zookeeper and aegis-source-a2a).
$knoxSourcePlugins = @{
    'zookeeper' = 'aegis-source-zookeeper'
    'a2a'       = 'aegis-source-a2a'
}
if ($knoxSourcePlugins.ContainsKey($Adopter)) {
    $srcMod = $knoxSourcePlugins[$Adopter]
    & $mvn -B -q -f "$root\pom.xml" -Preal-engines -pl $srcMod -am -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw "Knox plugin source build failed: $srcMod" }
}
# Build the profile-specific artifacts through the lifecycle that creates plugin.classpath files. Then clean
# only the selected validation module so its test classes, Surefire report, and adopter results are fresh
# without deleting the profile-generated artifacts required by its dependencies.
& $mvn -B -q -f "$root\examples\pom.xml" -Preal-engines -DskipTests install
if ($LASTEXITCODE -ne 0) { throw 'real-engine preparation install failed' }
& $mvn -B -q -f "$root\examples\pom.xml" -Preal-engines -pl $module clean
if ($LASTEXITCODE -ne 0) { throw 'validation-module clean failed' }
& $mvn -B -f "$root\examples\pom.xml" -Preal-engines -pl $module -am test "-Dtest=$test" "-Dsurefire.failIfNoSpecifiedTests=false"
$status = $LASTEXITCODE

if ($Keep -and $status -eq 0) {
    $from = Join-Path $root "examples\aegis-validation\target\adopter-results\$Adopter"
    $to = Join-Path $PSScriptRoot "$Adopter\results"
    New-Item -ItemType Directory -Force $to | Out-Null
    Get-ChildItem $from -File | Where-Object { $_.Name -match '-(transcript\.log|audit\.jsonl)$' } |
        Copy-Item -Destination $to -Force
    $plugins = Join-Path $from 'knox-logs\knox-ext-plugins.log'
    if (Test-Path $plugins) { Copy-Item $plugins (Join-Path $to 'knox-ext-plugins.log') -Force }
    $report = Join-Path $root "examples\$($module -replace '/', '\')\target\surefire-reports\io.github.vaquarkhan.aegis.validation.$test.txt"
    Copy-Item $report (Join-Path $to 'test-report.txt') -Force
    # What the run used, recorded by the harness: the Aegis YAML per front door, the Knox topology, and
    # HOW-TO-RUN.md (engine command lines, start-up steps, the requests sent). Kept beside results/.
    $example = Join-Path $PSScriptRoot $Adopter
    if (Test-Path (Join-Path $from 'aegis')) {
        New-Item -ItemType Directory -Force (Join-Path $example 'aegis') | Out-Null
        Copy-Item (Join-Path $from 'aegis\*') (Join-Path $example 'aegis') -Force
    }
    if (Test-Path (Join-Path $from 'engine')) {
        New-Item -ItemType Directory -Force (Join-Path $example 'engine') | Out-Null
        Copy-Item (Join-Path $from 'engine\*') (Join-Path $example 'engine') -Force
    }
    if (Test-Path (Join-Path $from 'HOW-TO-RUN.md')) { Copy-Item (Join-Path $from 'HOW-TO-RUN.md') $example -Force }
    Write-Host "kept results in $to"
}
exit $status
