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
# Starts a Kafka Connect standalone worker on 127.0.0.1:18083 with one FileStreamSource connector
# named aegis-file-source, reading work/orders.txt into the topic aegis.orders.
#   KAFKA_HOME=/path/to/kafka_2.13-3.9.1 ./start-connect.sh
# Run start-kafka.sh first and leave it running.
set -euo pipefail
: "${KAFKA_HOME:?Set KAFKA_HOME to an Apache Kafka 3.9.x binary distribution in this terminal too (see the tutorial, step 4.2)}"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
work="$here/work"
mkdir -p "$work"
[[ -f "$work/orders.txt" ]] || printf 'order-1 widget 9.50\norder-2 gadget 12.00\norder-3 gizmo 3.25\n' > "$work/orders.txt"
[[ -f "$work/cdc.txt" ]] || printf 'order-cdc-1 widget 9.50\n' > "$work/cdc.txt"
cat > "$work/connect-worker.properties" <<EOF
bootstrap.servers=127.0.0.1:19092
listeners=http://127.0.0.1:18083
key.converter=org.apache.kafka.connect.json.JsonConverter
value.converter=org.apache.kafka.connect.json.JsonConverter
key.converter.schemas.enable=false
value.converter.schemas.enable=false
offset.storage.file.filename=$work/connect.offsets
offset.flush.interval.ms=10000
plugin.path=$KAFKA_HOME/libs
EOF
cat > "$work/aegis-file-source.properties" <<EOF
name=aegis-file-source
connector.class=org.apache.kafka.connect.file.FileStreamSourceConnector
tasks.max=1
file=$work/orders.txt
topic=aegis.orders
EOF
echo 'Starting the Kafka Connect worker on 127.0.0.1:18083 (Ctrl+C to stop)'
exec "$KAFKA_HOME/bin/connect-standalone.sh" "$work/connect-worker.properties" "$work/aegis-file-source.properties"
