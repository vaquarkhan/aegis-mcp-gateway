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
# Creates the tutorial's tiny dataset through Spark Connect, outside Aegis (SeedLake.java).
# Needs: mvn -B -Preal-engines -DskipTests -pl :aegis-spark-connect install  (writes plugin.classpath)
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../../.." && pwd)"
cp_file="$repo/examples/aegis-spark-connect/target/plugin.classpath"
if [ ! -f "$cp_file" ]; then
  echo "Missing $cp_file. Run: mvn -B -Preal-engines -DskipTests -pl :aegis-spark-connect install" >&2
  exit 1
fi
exec java --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
  --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED \
  -cp "$(cat "$cp_file")" "$here/SeedLake.java" sc://127.0.0.1:15002
