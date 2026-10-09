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
# Uploads the same Flink example jar bytes twice, under two names:
#   approved-StateMachineExample.jar   matches the run-approved-jars filename policy
#   StateMachineExample.jar            does not, and stays unrunnable through Aegis
#
# In this tutorial, "approved" describes only a matching filename and resource id. It does not
# authenticate the bytes or establish artifact provenance. A production release pipeline should
# verify a digest and signed artifact or attestation before a restricted identity uploads the jar.
#
# This runs against Flink directly, NOT through the gateway, on purpose: POST /jars/upload is a
# multipart/form-data upload and Aegis 2.0 has no governed tool for it (an HTTP source builds a JSON
# body). Uploading a jar is a release step an operator or CI does; running one is the governed action.
#   FLINK_HOME=/path/to/flink-1.20.1 ./upload-jars.sh
set -euo pipefail
: "${FLINK_HOME:?Set FLINK_HOME to the same Apache Flink distribution start-flink.sh used}"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
src="$FLINK_HOME/examples/streaming/StateMachineExample.jar"
[[ -f "$src" ]] || { echo "Missing $src" >&2; exit 1; }
staging="$here/work/staging"
mkdir -p "$staging"
cp "$src" "$staging/approved-StateMachineExample.jar"
cp "$src" "$staging/StateMachineExample.jar"
for f in approved-StateMachineExample.jar StateMachineExample.jar; do
  echo "Uploading $f"
  curl -fsS -X POST -H 'Expect:' -F "jarfile=@$staging/$f" http://127.0.0.1:18081/jars/upload; echo
done
echo
echo 'Jar ids the JobManager assigned (note the uuid prefix Flink adds):'
curl -fsS http://127.0.0.1:18081/jars; echo
