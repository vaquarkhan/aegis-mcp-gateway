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
# Minimal MCP 2026-07-28 client (curl): one tools/call, or one admin API request.
#   ./mcp.sh call  <tool> '<arguments json>' ['<_meta json>']
#   ./mcp.sh admin <GET|POST> <path>                        e.g. POST approvals/apr_.../approve
# Credentials and URL come from the environment:
#   own host:  AUTH="Bearer $READER_TOKEN"            MCP_URL=http://127.0.0.1:8080/mcp (default)
#   Knox:      AUTH="Basic <base64-local-credential>"  MCP_URL=https://localhost:8443/gateway/aegis-spark-iceberg/aegis-mcp/v1/mcp  MCP_CA_FILE=/path/to/gateway-client-trust.pem
set -euo pipefail
url="${MCP_URL:-http://127.0.0.1:8080/mcp}"
: "${AUTH:?set AUTH, for example AUTH=\"Bearer \$READER_TOKEN\"}"
curl_args=(-sS --fail)
if [[ "$url" == https://* ]]; then
  : "${MCP_CA_FILE:?set MCP_CA_FILE to the PEM certificate that verifies this HTTPS endpoint}"
  curl_args+=(--cacert "$MCP_CA_FILE")
fi
case "${1:-}" in
  call)
    tool="$2"; args="${3:-{\}}"; extra="${4:-{\}}"
    meta="$(printf '%s' "$extra" | sed 's/^{//; s/}$//')"
    [ -n "$meta" ] && meta=",$meta"
    curl "${curl_args[@]}" "$url" -H "Authorization: $AUTH" -H 'Content-Type: application/json' \
      -H 'MCP-Protocol-Version: 2026-07-28' -H 'Mcp-Method: tools/call' -H "Mcp-Name: $tool" \
      -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"$tool\",\"arguments\":$args,\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\",\"io.modelcontextprotocol/clientCapabilities\":{}$meta}}}"
    echo ;;
  admin)
    case "$url" in
      */aegis-mcp/v1/mcp) base="${url%/mcp}/admin/" ;;
      *) base="${url%/mcp}/aegis/v1/" ;;
    esac
    curl "${curl_args[@]}" -X "$2" "$base$3" -H "Authorization: $AUTH"
    echo ;;
  *) echo "usage: $0 call <tool> '<args json>' ['<_meta json>'] | $0 admin <GET|POST> <path>" >&2; exit 2 ;;
esac
