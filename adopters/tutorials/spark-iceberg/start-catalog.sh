#!/usr/bin/env bash
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
# file warehouse in work/warehouse. On first run it downloads the jar and verifies its SHA-512.
#   ./start-catalog.sh
set -euo pipefail
version=1.10.1
here="$(cd "$(dirname "$0")" && pwd)"
work="$here/work"
mkdir -p "$work/warehouse"
jar="$work/iceberg-open-api-$version.jar"
base="https://repo1.maven.org/maven2/org/apache/iceberg/iceberg-open-api/$version"
if [ ! -f "$jar" ]; then
  echo "Downloading iceberg-open-api $version (about 210 MB, once)"
  curl -sSL --retry 3 -o "$jar" "$base/iceberg-open-api-$version.jar"
  expected="$(curl -sSL --retry 3 "$base/iceberg-open-api-$version.jar.sha512" | tr -d '[:space:]')"
  actual="$( (sha512sum "$jar" 2>/dev/null || shasum -a 512 "$jar") | cut -d' ' -f1)"
  if [ "$expected" != "$actual" ]; then rm -f "$jar"; echo 'SHA-512 mismatch, download discarded' >&2; exit 1; fi
  echo 'SHA-512 verified against Maven Central'
fi
export CATALOG_REST_PORT=8181
export CATALOG_WAREHOUSE="file://$work/warehouse/"
export CATALOG_IO__IMPL=org.apache.iceberg.hadoop.HadoopFileIO
export CATALOG_CATALOG__IMPL=org.apache.iceberg.jdbc.JdbcCatalog
export CATALOG_URI="jdbc:sqlite:file:$work/catalog.db"
# Local tutorial-only SQLite catalog identity. These values are not deployable credentials.
export CATALOG_JDBC_USER=aegis
export CATALOG_JDBC_PASSWORD=aegis
echo 'Starting the Iceberg REST catalog on http://127.0.0.1:8181 (Ctrl+C to stop)'
exec java -Xmx1G -jar "$jar"
