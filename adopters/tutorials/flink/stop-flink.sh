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
# Stops the tutorial Flink session cluster started by start-flink.sh.
set -euo pipefail
: "${FLINK_HOME:?Set FLINK_HOME to the same Apache Flink distribution start-flink.sh used}"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export FLINK_CONF_DIR="$here/work/conf"
export FLINK_LOG_DIR="$here/work/log"
"$FLINK_HOME/bin/stop-cluster.sh"
