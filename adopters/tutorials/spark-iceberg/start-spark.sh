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
# Starts Apache Spark 4.0.1 as a Spark Connect server on 127.0.0.1:15002 with Apache Iceberg's Spark
# runtime and one Iceberg catalog, "lake", backed by the REST catalog on 127.0.0.1:8181.
# Needs: mvn -B -Preal-engines -DskipTests -pl :aegis-validation-spark-connect-server install
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../../.." && pwd)"
cp_file="$repo/examples/aegis-validation-spark-connect-server/target/server.classpath"
if [ ! -f "$cp_file" ]; then
  echo "Missing $cp_file. Run: mvn -B -Preal-engines -DskipTests -pl :aegis-validation-spark-connect-server install" >&2
  exit 1
fi
mkdir -p "$here/work/spark-warehouse"
opens=""
for p in java.lang java.lang.invoke java.lang.reflect java.io java.net java.nio java.util java.util.concurrent \
         java.util.concurrent.atomic sun.nio.ch sun.nio.cs sun.security.action sun.util.calendar; do
  opens="$opens --add-opens=java.base/$p=ALL-UNNAMED"
done
echo 'Starting Spark Connect on sc://127.0.0.1:15002 with Iceberg catalog "lake" (Ctrl+C to stop)'
# shellcheck disable=SC2086
exec java $opens -Djdk.reflect.useDirectMethodHandle=false -Dio.netty.tryReflectionSetAccessible=true \
  -Dspark.master='local[2]' -Dspark.app.name=aegis-spark-iceberg-tutorial \
  -Dspark.connect.grpc.binding.address=127.0.0.1 -Dspark.connect.grpc.binding.port=15002 \
  -Dspark.driver.host=127.0.0.1 -Dspark.driver.bindAddress=127.0.0.1 -Dspark.ui.port=4040 \
  -Dspark.sql.warehouse.dir="file://$here/work/spark-warehouse" \
  -Dspark.sql.extensions=org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions \
  -Dspark.sql.catalog.lake=org.apache.iceberg.spark.SparkCatalog \
  -Dspark.sql.catalog.lake.type=rest \
  -Dspark.sql.catalog.lake.uri=http://127.0.0.1:8181 \
  -cp "$(cat "$cp_file")" org.apache.spark.sql.connect.service.SparkConnectServer
