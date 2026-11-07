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
# Starts Apache Spark 4.0.1 as a Spark Connect server on 127.0.0.1:15002 (the main class
# sbin/start-connect-server.sh runs) with Apache Iceberg's Spark runtime and one Iceberg catalog, "lake",
# backed by the REST catalog on 127.0.0.1:8181. Start the catalog first.
# Needs: mvn -B -Preal-engines -DskipTests -pl :aegis-validation-spark-connect-server,:aegis-windows-localfs install
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = (Resolve-Path (Join-Path $here '..\..\..')).Path
$cpFile = Join-Path $repo 'examples\aegis-validation-spark-connect-server\target\server.classpath'
$localFs = Join-Path $repo 'examples\aegis-windows-localfs\target\aegis-windows-localfs-2.0.0-SNAPSHOT.jar'
foreach ($f in $cpFile, $localFs) {
    if (-not (Test-Path $f)) {
        Write-Error "Missing $f. Run: mvn -B -Preal-engines -DskipTests -pl :aegis-validation-spark-connect-server,:aegis-windows-localfs install"
    }
}
$work = Join-Path $here 'work'
# Pre-created: on Windows, Hadoop would otherwise need winutils.exe to set the directory's permissions.
New-Item -ItemType Directory -Force -Path (Join-Path $work 'spark-warehouse') | Out-Null
$sparkWarehouse = ([System.Uri](Join-Path $work 'spark-warehouse')).AbsoluteUri
$cp = (Get-Content -Raw $cpFile).Trim() + ';' + $localFs
$opens = @('java.lang', 'java.lang.invoke', 'java.lang.reflect', 'java.io', 'java.net', 'java.nio', 'java.util',
    'java.util.concurrent', 'java.util.concurrent.atomic', 'sun.nio.ch', 'sun.nio.cs', 'sun.security.action',
    'sun.util.calendar') | ForEach-Object { "--add-opens=java.base/$_=ALL-UNNAMED" }
$javaArgs = $opens + @(
    '-Djdk.reflect.useDirectMethodHandle=false', '-Dio.netty.tryReflectionSetAccessible=true',
    '-Dspark.master=local[2]', '-Dspark.app.name=aegis-spark-iceberg-tutorial',
    '-Dspark.connect.grpc.binding.address=127.0.0.1', '-Dspark.connect.grpc.binding.port=15002',
    '-Dspark.driver.host=127.0.0.1', '-Dspark.driver.bindAddress=127.0.0.1', '-Dspark.ui.port=4040',
    "-Dspark.sql.warehouse.dir=$sparkWarehouse",
    # Iceberg: SQL extensions (DELETE, UPDATE, MERGE) and the "lake" catalog on the REST catalog.
    '-Dspark.sql.extensions=org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions',
    '-Dspark.sql.catalog.lake=org.apache.iceberg.spark.SparkCatalog',
    '-Dspark.sql.catalog.lake.type=rest',
    '-Dspark.sql.catalog.lake.uri=http://127.0.0.1:8181',
    # Windows only (see start-catalog.ps1): Hadoop's local file system without winutils.exe.
    '-Dspark.hadoop.fs.file.impl=io.github.vaquarkhan.aegis.examples.localfs.WindowsLocalFileSystem',
    '-cp', $cp, 'org.apache.spark.sql.connect.service.SparkConnectServer')
Write-Host 'Starting Spark Connect on sc://127.0.0.1:15002 with Iceberg catalog "lake" (Ctrl+C to stop)'
# Windows PowerShell turns any JVM notice on stderr (for example JAVA_TOOL_OPTIONS) into an error
# record; with 'Stop' that would end the script, so native-command errors are left to the JVM.
$ErrorActionPreference = 'Continue'
& java @javaArgs
