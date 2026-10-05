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
# Starts the official Apache Iceberg REST catalog reference server on 127.0.0.1:18181, backed by
# JDBC/SQLite with an in-memory FileIO. This is the same server the apache/iceberg-rest-fixture image
# runs: org.apache.iceberg.rest.RESTCatalogServer, the Main-Class of iceberg-open-api.
#
# On first run it downloads the jar from Maven Central and verifies its SHA-512.
#   .\start-catalog.ps1
$ErrorActionPreference = 'Stop'
$version = '1.10.1'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$work = Join-Path $here 'work'
New-Item -ItemType Directory -Force -Path (Join-Path $work 'warehouse') | Out-Null
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
$env:CATALOG_REST_PORT = '18181'
$env:CATALOG_WAREHOUSE = (Join-Path $work 'warehouse')
$env:CATALOG_IO__IMPL = 'org.apache.iceberg.inmemory.InMemoryFileIO'
$env:CATALOG_CATALOG__IMPL = 'org.apache.iceberg.jdbc.JdbcCatalog'
$env:CATALOG_URI = "jdbc:sqlite:file:$(Join-Path $work 'catalog.db')"
# Local tutorial-only SQLite catalog identity. These values are not deployable credentials.
$env:CATALOG_JDBC_USER = 'aegis'
$env:CATALOG_JDBC_PASSWORD = 'aegis'
Write-Host 'Starting the Iceberg REST catalog on 127.0.0.1:18181 (Ctrl+C to stop)'
& java -Xmx1G -jar $jar
