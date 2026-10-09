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
# Own-host flavor: starts the Aegis gateway on http://127.0.0.1:8080/mcp with the tutorial governance
# (aegis-spark-iceberg.yaml) plus the development identities (host-own.yaml).
#   ./start-gateway.sh           HTTP
#   ./start-gateway.sh --stdio   stdio, for IDEs that launch the MCP server as a process
#   ./start-gateway.sh --write-config-only   only write the two generated configurations
# Needs AEGIS_SIGNING_KEY, READER_TOKEN, ENGINEER_TOKEN and APPROVER_TOKEN in the environment.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../../.." && pwd)"
jar="$repo/examples/aegis-gateway-spark/target/aegis-gateway-spark-2.0.0-SNAPSHOT-all.jar"
[ -f "$jar" ] || { echo "Missing $jar. Run: mvn -B -DskipTests install" >&2; exit 1; }
for v in AEGIS_SIGNING_KEY READER_TOKEN ENGINEER_TOKEN APPROVER_TOKEN; do
  [ -n "${!v:-}" ] || { echo "export $v first (tutorial step 3)" >&2; exit 1; }
done
# The stdio variant keeps its own audit journal: one journal file has exactly one writer.
http_config="$here/aegis-own-host.local.yaml"
stdio_config="$here/aegis-stdio.local.yaml"
{ cat "$here/aegis-spark-iceberg.yaml"; echo; cat "$here/host-own.yaml"; } > "$http_config"
sed 's#work/audit/aegis-spark-iceberg.jsonl#work/audit/aegis-spark-iceberg-stdio.jsonl#' "$http_config" > "$stdio_config"
if [ "${1:-}" = "--write-config-only" ]; then echo "Wrote $http_config and $stdio_config"; exit 0; fi
config="$http_config"; [ "${1:-}" = "--stdio" ] && config="$stdio_config"
exec java --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
  --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED \
  -jar "$jar" --config "$config" "$@"
