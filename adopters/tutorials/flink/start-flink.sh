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
# Starts a single-node Apache Flink session cluster whose REST API listens on 127.0.0.1:18081.
# A non-default port on purpose, so this never collides with a real local cluster on 8081.
#   FLINK_HOME=/path/to/flink-1.20.1 ./start-flink.sh
# Stop it again with:  FLINK_HOME=... ./stop-flink.sh
set -euo pipefail
: "${FLINK_HOME:?Set FLINK_HOME to an Apache Flink 1.20+ binary distribution (see the tutorial, step 4.2)}"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
work="$here/work"
mkdir -p "$work/conf" "$work/log" "$work/aegis-savepoints" "$work/checkpoints" "$work/jars" "$work/audit"

# Flink 1.20 reads conf/config.yaml (standard YAML). Copy the distribution's file, which carries the
# --add-exports/--add-opens flags Java 17 needs, then append the tutorial overrides. Later keys win.
if [[ ! -f "$work/conf/config.yaml" ]]; then
  cp "$FLINK_HOME/conf/config.yaml" "$work/conf/config.yaml"
  cp "$FLINK_HOME"/conf/log4j*.properties "$work/conf/" 2>/dev/null || true
  cp "$FLINK_HOME"/conf/logback*.xml "$work/conf/" 2>/dev/null || true
  cp "$FLINK_HOME/conf/masters" "$FLINK_HOME/conf/workers" "$work/conf/" 2>/dev/null || true
  cat >> "$work/conf/config.yaml" <<YAML

# ---------------------------------------------------------------- Aegis tutorial overrides
rest:
  address: localhost
  bind-address: localhost
  port: 18081
taskmanager:
  numberOfTaskSlots: 4
parallelism:
  default: 1
execution:
  checkpointing:
    interval: 10s
state:
  backend:
    type: hashmap
  checkpoints:
    dir: file://$work/checkpoints
  savepoints:
    dir: file://$work/aegis-savepoints
web:
  upload:
    dir: $work/jars
YAML
  echo "Wrote $work/conf/config.yaml (REST on 127.0.0.1:18081, 4 slots, checkpoints every 10s)"
fi

export FLINK_CONF_DIR="$work/conf"
export FLINK_LOG_DIR="$work/log"
"$FLINK_HOME/bin/start-cluster.sh"
echo
echo 'Waiting for the JobManager REST API on http://127.0.0.1:18081 ...'
for _ in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:18081/overview >/dev/null 2>&1; then
    curl -fsS http://127.0.0.1:18081/overview; echo
    echo 'Flink is up.'
    exit 0
  fi
  sleep 1
done
echo 'The REST API did not come up; look in '"$work/log" >&2
exit 1
