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
# Starts the Aegis gateway on http://127.0.0.1:8080/mcp in front of the tutorial Flink cluster.
# The base distribution is enough here: Flink's control plane is HTTP, so no plugin is needed.
# Needs the four environment variables from the tutorial (AEGIS_SIGNING_KEY and the three tokens).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
jar="$here/../../../aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar"
[[ -f "$jar" ]] || { echo "Missing $jar. Run 'mvn -B clean install' from the repository root." >&2; exit 1; }
for v in AEGIS_SIGNING_KEY ANALYST_TOKEN OPERATOR_TOKEN APPROVER_TOKEN; do
  [[ -n "${!v:-}" ]] || { echo "export $v first (see the tutorial, step 4.3)" >&2; exit 1; }
done
cd "$here"     # so the audit journal lands in adopters/tutorials/flink/work/audit
exec java -jar "$jar" --config "$here/aegis-flink-tutorial.yaml"
