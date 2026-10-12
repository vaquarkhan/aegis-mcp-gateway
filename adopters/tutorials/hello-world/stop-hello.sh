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
# Stops the two processes start-hello.sh started, using the process ids it recorded in work/pids.txt.
set -uo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
pids="$here/work/pids.txt"
[[ -f "$pids" ]] || { echo 'Nothing to stop (no work/pids.txt).'; exit 0; }
while read -r pid; do
  [[ -n "$pid" ]] || continue
  if kill "$pid" 2>/dev/null; then echo "Stopped pid $pid"; fi
done < "$pids"
rm -f "$pids"
