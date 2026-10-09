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
# Starts the Aegis gateway (with the spark-connect source) on http://127.0.0.1:8080/mcp.
# Needs the four environment variables from the tutorial (AEGIS_SIGNING_KEY and the three tokens).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
jar="$here/../../../examples/aegis-gateway-spark/target/aegis-gateway-spark-2.0.0-SNAPSHOT-all.jar"
[[ -f "$jar" ]] || { echo "Missing $jar. From the repository root, run: mvn -B install -DskipTests" >&2; exit 1; }
for v in AEGIS_SIGNING_KEY ANALYST_TOKEN OPERATOR_TOKEN APPROVER_TOKEN; do
  [[ -n "${!v:-}" ]] || { echo "export $v first (see the tutorial, step 3)" >&2; exit 1; }
done
# The Spark Connect client uses Apache Arrow, which needs these opens on Java 17+.
exec java --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
  --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED \
  -jar "$jar" --config "$here/aegis-spark-tutorial.yaml"
