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
# Starts the official Apache Iceberg 1.10.1 REST catalog server (org.apache.iceberg.rest.RESTCatalogServer,
# the Main-Class of iceberg-open-api) on 127.0.0.1:8181, with a SQLite catalog database and a real
# file warehouse in work/warehouse: table metadata and Parquet data files are written to disk.
# On first run it downloads the jar from Maven Central and verifies its SHA-512.
#   .\start-catalog.ps1
$ErrorActionPreference = 'Stop'
$version = '1.10.1'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = (Resolve-Path (Join-Path $here '..\..\..')).Path
$work = Join-Path $here 'work'
$warehouse = Join-Path $work 'warehouse'
New-Item -ItemType Directory -Force -Path $warehouse | Out-Null
$jar = Join-Path $work "iceberg-open-api-$version.jar"
$base = "https://repo1.maven.org/maven2/org/apache/iceberg/iceberg-open-api/$version"
if (-not (Test-Path $jar)) {
    Write-Host "Downloading iceberg-open-api $version (about 210 MB, once)"
    curl.exe -sSL --retry 3 -o $jar "$base/iceberg-open-api-$version.jar"
    curl.exe -sSL --retry 3 -o "$jar.sha512" "$base/iceberg-open-api-$version.jar.sha512"
    $expected = (Get-Content "$jar.sha512" -Raw).Trim().ToLower()
    $actual = (Get-FileHash $jar -Algorithm SHA512).Hash.ToLower()
    if ($expected -ne $actual) { Remove-Item $jar; Write-Error 'SHA-512 mismatch, download discarded' }
    Write-Host 'SHA-512 verified against Maven Central'
}
# Windows only: Hadoop's local file system needs winutils.exe to set file permissions, and Apache Hadoop
# does not ship it. The tutorial's WindowsLocalFileSystem is Hadoop's own local file system without those
# permission calls. Build it once: mvn -B -Preal-engines -DskipTests -pl :aegis-windows-localfs install
$localFs = Join-Path $repo 'examples\aegis-windows-localfs\target\aegis-windows-localfs-2.0.0-SNAPSHOT.jar'
if (-not (Test-Path $localFs)) {
    Write-Error "Missing $localFs. Run: mvn -B -Preal-engines -DskipTests -pl :aegis-windows-localfs install"
}
$conf = Join-Path $work 'catalog-conf'
New-Item -ItemType Directory -Force -Path $conf | Out-Null
Set-Content -Encoding ascii -Path (Join-Path $conf 'core-site.xml') -Value @'
<?xml version="1.0"?>
<configuration>
  <property><name>fs.file.impl</name><value>io.github.vaquarkhan.aegis.examples.localfs.WindowsLocalFileSystem</value></property>
</configuration>
'@
$env:CATALOG_REST_PORT = '8181'
$env:CATALOG_WAREHOUSE = ([System.Uri]$warehouse).AbsoluteUri + '/'
$env:CATALOG_IO__IMPL = 'org.apache.iceberg.hadoop.HadoopFileIO'
$env:CATALOG_CATALOG__IMPL = 'org.apache.iceberg.jdbc.JdbcCatalog'
$env:CATALOG_URI = 'jdbc:sqlite:file:' + ((Join-Path $work 'catalog.db') -replace '\\', '/')
# Local tutorial-only SQLite catalog identity. These values are not deployable credentials.
$env:CATALOG_JDBC_USER = 'aegis'
$env:CATALOG_JDBC_PASSWORD = 'aegis'
Write-Host 'Starting the Iceberg REST catalog on http://127.0.0.1:8181 (Ctrl+C to stop)'
# Windows PowerShell turns any JVM notice on stderr (for example JAVA_TOOL_OPTIONS) into an error
# record; with 'Stop' that would end the script, so native-command errors are left to the JVM.
$ErrorActionPreference = 'Continue'
& java -Xmx1G -cp "$jar;$conf;$localFs" org.apache.iceberg.rest.RESTCatalogServer
