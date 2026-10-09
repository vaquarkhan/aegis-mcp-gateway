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
# Starts a single-node Apache Kafka KRaft broker on 127.0.0.1:19092 from an Apache binary
# distribution. Non-default ports on purpose, so this never collides with a real local cluster.
#   KAFKA_HOME=/path/to/kafka_2.13-3.9.1 ./start-kafka.sh
# Leave it running; start-connect.sh goes in a second terminal.
set -euo pipefail
: "${KAFKA_HOME:?Set KAFKA_HOME to an Apache Kafka 3.9+ binary distribution (see the tutorial, step 4.2)}"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
work="$here/work"
mkdir -p "$work"
props="$work/server.properties"
if [[ ! -f "$props" ]]; then
  # Start from the distribution's own KRaft config and change only ports and paths.
  sed -e 's|controller\.quorum\.voters=1@localhost:9093|controller.quorum.voters=1@127.0.0.1:19093|' \
      -e 's|listeners=PLAINTEXT://:9092,CONTROLLER://:9093|listeners=PLAINTEXT://127.0.0.1:19092,CONTROLLER://127.0.0.1:19093|' \
      -e 's|advertised\.listeners=PLAINTEXT://localhost:9092,CONTROLLER://localhost:9093|advertised.listeners=PLAINTEXT://127.0.0.1:19092|' \
      -e "s|log\.dirs=/tmp/kraft-combined-logs|log.dirs=$work/kraft-logs|" \
      "$KAFKA_HOME/config/kraft/server.properties" > "$props"
  echo "Wrote $props (broker 19092, controller 19093)"
fi
if [[ ! -f "$work/kraft-logs/meta.properties" ]]; then
  if [[ ! -f "$work/cluster-id.txt" ]]; then
    "$KAFKA_HOME/bin/kafka-storage.sh" random-uuid | tr -d '\r' | tail -n 1 > "$work/cluster-id.txt"
  fi
  cid="$(cat "$work/cluster-id.txt")"
  echo "Formatting the metadata log with cluster id $cid"
  "$KAFKA_HOME/bin/kafka-storage.sh" format -t "$cid" -c "$props"
fi
echo 'Starting the KRaft broker on 127.0.0.1:19092 (Ctrl+C to stop)'
exec "$KAFKA_HOME/bin/kafka-server-start.sh" "$props"
