#!/usr/bin/env bash
# Author: Viquar Khan
# Smoke-test Aegis Flink + Kafka over streamable HTTP (Bearer: test)
set -euo pipefail
BASE="${BASE:-http://127.0.0.1:8090}"
AUTH="Authorization: Bearer test"
CT="Content-Type: application/json"
ACCEPT="Accept: application/json, text/event-stream"

echo "healthz..."
curl -sf "$BASE/healthz" | grep -q UP

echo "initialize..."
INIT_HEADERS=$(mktemp)
INIT_BODY=$(mktemp)
curl -sf -D "$INIT_HEADERS" -o "$INIT_BODY" -H "$AUTH" -H "$CT" -H "$ACCEPT" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"flink-kafka-smoke","version":"0.1.0"}}}' \
  "$BASE/mcp"
SESSION=$(awk -F': ' 'tolower($1)=="mcp-session-id"{print $2}' "$INIT_HEADERS" | tr -d '\r')
test -n "$SESSION" || { echo "missing Mcp-Session-Id"; exit 1; }
echo "session=$SESSION"

curl -sf -H "$AUTH" -H "$CT" -H "$ACCEPT" -H "Mcp-Session-Id: $SESSION" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
  "$BASE/mcp" >/dev/null || true

echo "tools/list..."
LIST=$(curl -sf -H "$AUTH" -H "$CT" -H "$ACCEPT" -H "Mcp-Session-Id: $SESSION" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' \
  "$BASE/mcp")
echo "$LIST" | grep -q 'list_jobs' || { echo "list_jobs missing"; exit 1; }
echo "$LIST" | grep -q 'list_topics' || { echo "list_topics missing"; exit 1; }
if echo "$LIST" | grep -Eq '"name"[[:space:]]*:[[:space:]]*"(create_topic|stop_job|cancel_job)"'; then
  echo "mutate tools must stay hidden while writes are locked"
  exit 1
fi

echo "tools/call list_jobs..."
JOBS=$(curl -sf -H "$AUTH" -H "$CT" -H "$ACCEPT" -H "Mcp-Session-Id: $SESSION" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"list_jobs","arguments":{}}}' \
  "$BASE/mcp")
echo "$JOBS" | grep -q 'denied:' && { echo "list_jobs denied: $JOBS"; exit 1; }

echo "tools/call list_topics..."
TOPICS=$(curl -sf -H "$AUTH" -H "$CT" -H "$ACCEPT" -H "Mcp-Session-Id: $SESSION" \
  -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"list_topics","arguments":{}}}' \
  "$BASE/mcp")
echo "$TOPICS" | grep -q 'denied:' && { echo "list_topics denied: $TOPICS"; exit 1; }
echo "$TOPICS" | grep -Eq 'BACKEND_ERROR|Timed out' && { echo "list_topics backend failure: $TOPICS"; exit 1; }

rm -f "$INIT_HEADERS" "$INIT_BODY"
echo "SMOKE_OK"
