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
# Minimal MCP 2026-07-28 client for the tutorial: one JSON-RPC request over Streamable HTTP, with the
# required MCP-Protocol-Version, Mcp-Method and Mcp-Name headers. Prints the HTTP status and the response.
#   ./mcp.sh "$ANALYST_TOKEN" tools/list
#   ./mcp.sh "$ANALYST_TOKEN" tools/call flink_get_cluster_info
#   ./mcp.sh "$OPERATOR_TOKEN" tools/call flink_cancel_job '{"jobId":"<32 hex>"}' '{"io.github.vaquarkhan.aegis/dryRun":true}'
set -euo pipefail
token="$1"; method="$2"; name="${3:-}"; args="${4:-"{}"}"; extra_meta="${5:-}"
url="${AEGIS_URL:-http://127.0.0.1:8080/mcp}"
meta='"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}'
if [[ -n "$extra_meta" ]]; then
  inner="${extra_meta#\{}"; inner="${inner%\}}"   # merge the caller's _meta keys
  [[ -n "$inner" ]] && meta="$meta,$inner"
fi
headers=(-H "Content-Type: application/json" -H "Authorization: Bearer $token"
         -H "MCP-Protocol-Version: 2026-07-28" -H "Mcp-Method: $method")
if [[ "$method" == "tools/call" ]]; then
  [[ -n "$name" ]] || { echo "tools/call needs a tool name" >&2; exit 2; }
  body="{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"$name\",\"arguments\":$args,\"_meta\":{$meta}}}"
  headers+=(-H "Mcp-Name: $name")
else
  body="{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"$method\",\"params\":{\"_meta\":{$meta}}}"
fi
curl -sS -w '\nHTTP %{http_code}\n' "${headers[@]}" --data-binary "$body" "$url"
