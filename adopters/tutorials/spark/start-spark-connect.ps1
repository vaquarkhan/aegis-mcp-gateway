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
# Starts a local Apache Spark 4.0.1 Spark Connect server (the same main class sbin/start-connect-server.sh
# runs) on 127.0.0.1:15002, with its driver UI and monitoring REST API on 127.0.0.1:4040.
# Needs: mvn -B -Preal-engines -pl :aegis-validation-spark-connect-server package  (writes the classpath file)
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$cpFile = Join-Path $here '..\..\..\examples\aegis-validation-spark-connect-server\target\server.classpath'
if (-not (Test-Path $cpFile)) {
    Write-Error "Missing $cpFile. Run: mvn -B -Preal-engines -pl :aegis-validation-spark-connect-server package"
}
$work = Join-Path $here 'work'
# Pre-create the warehouse: on Windows, Hadoop would otherwise need winutils.exe to set its permissions.
New-Item -ItemType Directory -Force -Path (Join-Path $work 'warehouse') | Out-Null
$warehouse = ([System.Uri](Join-Path $work 'warehouse')).AbsoluteUri
$cp = (Get-Content -Raw $cpFile).Trim()
$opens = @('java.lang', 'java.lang.invoke', 'java.lang.reflect', 'java.io', 'java.net', 'java.nio', 'java.util',
    'java.util.concurrent', 'java.util.concurrent.atomic', 'sun.nio.ch', 'sun.nio.cs', 'sun.security.action',
    'sun.util.calendar') | ForEach-Object { "--add-opens=java.base/$_=ALL-UNNAMED" }
$javaArgs = $opens + @(
    '-Djdk.reflect.useDirectMethodHandle=false', '-Dio.netty.tryReflectionSetAccessible=true',
    '-Dspark.master=local[2]', '-Dspark.app.name=aegis-tutorial',
    '-Dspark.connect.grpc.binding.address=127.0.0.1', '-Dspark.connect.grpc.binding.port=15002',
    '-Dspark.driver.host=127.0.0.1', '-Dspark.driver.bindAddress=127.0.0.1',
    '-Dspark.ui.port=4040', "-Dspark.sql.warehouse.dir=$warehouse",
    '-cp', $cp, 'org.apache.spark.sql.connect.service.SparkConnectServer')
Write-Host "Starting Spark Connect on sc://127.0.0.1:15002 (UI http://127.0.0.1:4040). Ctrl+C stops it."
& java @javaArgs
