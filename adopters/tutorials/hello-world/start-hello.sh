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
# Hello World: starts the example inventory service (Python standard library only) on 127.0.0.1:9109
# and the Aegis gateway on http://127.0.0.1:8080/mcp, both in the background, waits until the gateway
# is ready, then returns so you can keep using the same shell. Stop both with ./stop-hello.sh.
#   ./start-hello.sh          # gateway on port 8080
#   ./start-hello.sh 18080    # if 8080 is already in use on this machine
# Needs AEGIS_SIGNING_KEY, ANALYST_TOKEN, OPERATOR_TOKEN and APPROVER_TOKEN (see the guide, step 2).
set -euo pipefail
port="${1:-8080}"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../../.." && pwd)"
jar="$repo/aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar"
work="$here/work"
[[ -f "$jar" ]] || { echo "Missing $jar. From the repository root run: mvn -B -DskipTests install" >&2; exit 1; }
for v in AEGIS_SIGNING_KEY ANALYST_TOKEN OPERATOR_TOKEN APPROVER_TOKEN; do
  [[ -n "${!v:-}" ]] || { echo "Export $v first (see the Hello World guide, step 2)" >&2; exit 1; }
done
python="$(command -v python3 || command -v python || true)"
[[ -n "$python" ]] || { echo 'Python 3 is required for the example inventory service' >&2; exit 1; }
command -v java > /dev/null || { echo 'Java 17 or newer is required (java on PATH)' >&2; exit 1; }
for p in 9109 "$port"; do
  if (exec 3<>"/dev/tcp/127.0.0.1/$p") 2>/dev/null; then
    echo "Port $p is already in use. Run ./stop-hello.sh, stop that process, or pass a free port." >&2; exit 1
  fi
done
mkdir -p "$work"
config="$here/aegis-hello-world.yaml"
if [[ "$port" != "8080" ]]; then
  # Keep the checked-in file unchanged; write a gitignored copy next to it (relative paths in a config
  # resolve against the config's folder).
  config="$here/aegis-hello-world.local.yaml"
  sed "s/port: 8080}/port: $port}/" "$here/aegis-hello-world.yaml" > "$config"
fi
"$python" "$repo/adopters/clients/example_inventory_service.py" 9109 > "$work/inventory.log" 2>&1 &
svc=$!
# Run from this folder: the audit journal path in the configuration is relative.
(cd "$here" && exec java -jar "$jar" --config "$config") > "$work/gateway.log" 2>&1 &
gw=$!
printf '%s\n%s\n' "$svc" "$gw" > "$work/pids.txt"
for _ in $(seq 1 60); do
  sleep 0.5
  kill -0 "$gw" 2>/dev/null || break
  if curl -fsS -o /dev/null "http://127.0.0.1:$port/readyz" 2>/dev/null; then
    echo "Aegis is ready on http://127.0.0.1:$port/mcp (governing the inventory service on 127.0.0.1:9109)."
    echo 'Stop both with ./stop-hello.sh'
    exit 0
  fi
done
echo 'The gateway did not become ready. Last lines of work/gateway.log:' >&2
tail -n 15 "$work/gateway.log" >&2 || true
"$here/stop-hello.sh"
exit 1
