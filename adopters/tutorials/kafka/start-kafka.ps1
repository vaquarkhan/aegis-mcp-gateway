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
# Starts a single-node Apache Kafka KRaft broker on 127.0.0.1:19092 from an Apache binary
# distribution. Non-default ports on purpose, so this never collides with a real local cluster.
#   $env:KAFKA_HOME = 'C:\path\to\kafka_2.13-3.9.1'; .\start-kafka.ps1
# Leave it running; start-connect.ps1 goes in a second terminal.
param([string]$KafkaHome = $env:KAFKA_HOME)
$ErrorActionPreference = 'Stop'
if (-not $KafkaHome -or -not (Test-Path (Join-Path $KafkaHome 'libs'))) {
    Write-Error "Set `$env:KAFKA_HOME to an Apache Kafka 3.9+ binary distribution (see the tutorial, step 4.2)"
}
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$work = Join-Path $here 'work'
New-Item -ItemType Directory -Force -Path $work | Out-Null
$logDirs = ((Join-Path $work 'kraft-logs') -replace '\\', '/')
$props = Join-Path $work 'server.properties'
if (-not (Test-Path $props)) {
    # Start from the distribution's own KRaft config and change only ports and paths.
    $src = Get-Content (Join-Path $KafkaHome 'config\kraft\server.properties') -Raw
    $src = $src -replace 'controller\.quorum\.voters=1@localhost:9093', 'controller.quorum.voters=1@127.0.0.1:19093'
    $src = $src -replace 'listeners=PLAINTEXT://:9092,CONTROLLER://:9093', 'listeners=PLAINTEXT://127.0.0.1:19092,CONTROLLER://127.0.0.1:19093'
    $src = $src -replace 'advertised\.listeners=PLAINTEXT://localhost:9092,CONTROLLER://localhost:9093', 'advertised.listeners=PLAINTEXT://127.0.0.1:19092'
    $src = $src -replace 'log\.dirs=/tmp/kraft-combined-logs', "log.dirs=$logDirs"
    Set-Content -Path $props -Value $src -Encoding ASCII
    Write-Host "Wrote $props (broker 19092, controller 19093)"
}
$cp = Join-Path $KafkaHome 'libs\*'
if (-not (Test-Path (Join-Path $work 'kraft-logs\meta.properties'))) {
    $idFile = Join-Path $work 'cluster-id.txt'
    if (-not (Test-Path $idFile)) {
        # Kafka's own tool generates the id. Take the last line that looks like one, because a JVM
        # with JAVA_TOOL_OPTIONS set prints a banner first.
        $out = & java -cp $cp kafka.tools.StorageTool random-uuid 2>&1
        $cid = ($out | ForEach-Object { $_ -split '\s+' } | Where-Object { $_ -match '^[A-Za-z0-9_-]{22}$' } | Select-Object -Last 1)
        if (-not $cid) { Write-Error 'Could not generate a cluster id with kafka.tools.StorageTool' }
        Set-Content -Path $idFile -Value $cid -Encoding ASCII
    }
    $cid = (Get-Content $idFile -Raw).Trim()
    Write-Host "Formatting the metadata log with cluster id $cid"
    & java -cp $cp kafka.tools.StorageTool format -t $cid -c $props
}
Write-Host 'Starting the KRaft broker on 127.0.0.1:19092 (Ctrl+C to stop)'
& java -Xmx1G "-Dkafka.logs.dir=$(Join-Path $work 'broker-logs')" `
    "-Dlog4j.configuration=file:$(Join-Path $KafkaHome 'config\log4j.properties')" `
    -cp $cp kafka.Kafka $props
