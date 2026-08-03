# Author: Viquar Khan
# Smoke-test Aegis Flink + Kafka over streamable HTTP (Bearer: test)
$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:8090'

function Invoke-McpRaw([hashtable]$headers, [string]$body) {
  return Invoke-WebRequest -Uri "$base/mcp" -Method Post -Headers $headers -Body $body -UseBasicParsing
}

Write-Host 'healthz...'
$health = Invoke-WebRequest -Uri "$base/healthz" -UseBasicParsing
if ($health.StatusCode -ne 200) { throw "healthz status $($health.StatusCode)" }
if ($health.Content -notmatch 'UP') { throw "unexpected health body: $($health.Content)" }

$baseHeaders = @{
  Authorization = 'Bearer test'
  'Content-Type' = 'application/json'
  Accept = 'application/json, text/event-stream'
}

Write-Host 'initialize...'
$initBody = '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"flink-kafka-smoke","version":"0.1.0"}}}'
$init = Invoke-McpRaw $baseHeaders $initBody
$session = $init.Headers['Mcp-Session-Id']
if (-not $session) { throw 'missing Mcp-Session-Id from initialize' }
Write-Host "session=$session"

$headers = $baseHeaders.Clone()
$headers['Mcp-Session-Id'] = $session

# optional initialized notification (no id)
try {
  Invoke-McpRaw $headers '{"jsonrpc":"2.0","method":"notifications/initialized"}' | Out-Null
} catch {
  # some transports return 202/empty; ignore non-fatal notify failures
}

Write-Host 'tools/list...'
$listResp = Invoke-McpRaw $headers '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
$listBody = $listResp.Content
Write-Host ($listBody.Substring(0, [Math]::Min(240, $listBody.Length)))
if ($listBody -notmatch 'list_jobs') { throw 'list_jobs missing from tools/list' }
if ($listBody -notmatch 'list_topics') { throw 'list_topics missing from tools/list' }
if ($listBody -match '"name"\s*:\s*"(create_topic|stop_job|cancel_job)"') {
  throw 'mutate/destructive tools must stay hidden while writes are locked'
}

Write-Host 'tools/call list_jobs...'
$jobsResp = Invoke-McpRaw $headers '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"list_jobs","arguments":{}}}'
$jobsBody = $jobsResp.Content
if ($jobsBody -match 'denied:') { throw "list_jobs denied: $jobsBody" }
if ($jobsBody -notmatch 'jobs|content|result') { throw "unexpected list_jobs body: $jobsBody" }
Write-Host 'list_jobs ok'

Write-Host 'tools/call list_topics...'
$topicsResp = Invoke-McpRaw $headers '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"list_topics","arguments":{}}}'
$topicsBody = $topicsResp.Content
if ($topicsBody -match 'denied:') { throw "list_topics denied: $topicsBody" }
if ($topicsBody -match 'BACKEND_ERROR|IllegalStateException|Timed out') {
  throw "list_topics backend failure: $topicsBody"
}
Write-Host 'list_topics ok'

Write-Host 'SMOKE_OK'
