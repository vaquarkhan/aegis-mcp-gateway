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
# Starts a Kafka Connect standalone worker on 127.0.0.1:18083 with one FileStreamSource connector
# named aegis-file-source, reading work/orders.txt into the topic aegis.orders.
#   $env:KAFKA_HOME = 'C:\path\to\kafka_2.13-3.9.1'; .\start-connect.ps1
# Run start-kafka.ps1 first and leave it running.
param([string]$KafkaHome = $env:KAFKA_HOME)
$ErrorActionPreference = 'Stop'
if (-not $KafkaHome -or -not (Test-Path (Join-Path $KafkaHome 'libs'))) {
    Write-Error "Set `$env:KAFKA_HOME to an Apache Kafka 3.9+ binary distribution (see the tutorial, step 4.2)"
}
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$work = Join-Path $here 'work'
New-Item -ItemType Directory -Force -Path $work | Out-Null
$fwd = { param($p) ($p -replace '\\', '/') }
$src = Join-Path $work 'orders.txt'
if (-not (Test-Path $src)) {
    Set-Content -Path $src -Encoding ASCII -Value @(
        'order-1 widget 9.50', 'order-2 gadget 12.00', 'order-3 gizmo 3.25')
}
$worker = Join-Path $work 'connect-worker.properties'
Set-Content -Path $worker -Encoding ASCII -Value @(
    'bootstrap.servers=127.0.0.1:19092',
    'listeners=http://127.0.0.1:18083',
    'key.converter=org.apache.kafka.connect.json.JsonConverter',
    'value.converter=org.apache.kafka.connect.json.JsonConverter',
    'key.converter.schemas.enable=false',
    'value.converter.schemas.enable=false',
    "offset.storage.file.filename=$(& $fwd (Join-Path $work 'connect.offsets'))",
    'offset.flush.interval.ms=10000',
    "plugin.path=$(& $fwd (Join-Path $KafkaHome 'libs'))")
$conn = Join-Path $work 'aegis-file-source.properties'
Set-Content -Path $conn -Encoding ASCII -Value @(
    'name=aegis-file-source',
    'connector.class=org.apache.kafka.connect.file.FileStreamSourceConnector',
    'tasks.max=1',
    "file=$(& $fwd $src)",
    'topic=aegis.orders')
Write-Host 'Starting the Kafka Connect worker on 127.0.0.1:18083 (Ctrl+C to stop)'
& java -Xmx512M "-Dkafka.logs.dir=$(Join-Path $work 'connect-logs')" `
    "-Dlog4j.configuration=file:$(Join-Path $KafkaHome 'config\connect-log4j.properties')" `
    -cp (Join-Path $KafkaHome 'libs\*') org.apache.kafka.connect.cli.ConnectStandalone $worker $conn
