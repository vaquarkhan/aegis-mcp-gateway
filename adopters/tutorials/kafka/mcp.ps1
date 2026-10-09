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
# required MCP-Protocol-Version, Mcp-Method and Mcp-Name headers. Prints the JSON-RPC response.
#   .\mcp.ps1 -Token $env:ANALYST_TOKEN -Method tools/list
#   .\mcp.ps1 -Token $env:ANALYST_TOKEN -Name kafka_describe_topic -Arguments @{topic='aegis.orders'}
#   $r = .\mcp.ps1 -Token $env:ANALYST_TOKEN -Method tools/list -PassThru   # parsed object for scripting
param(
    [Parameter(Mandatory = $true)][string]$Token,
    [string]$Method = 'tools/call',
    [string]$Name,
    [hashtable]$Arguments = @{},
    [hashtable]$Meta = @{},
    [string]$Url = 'http://127.0.0.1:8080/mcp',
    # Return the parsed response object (for scripting) instead of printing it.
    [switch]$PassThru
)
$ErrorActionPreference = 'Stop'
$m = @{ 'io.modelcontextprotocol/protocolVersion' = '2026-07-28'; 'io.modelcontextprotocol/clientCapabilities' = @{} }
foreach ($k in $Meta.Keys) { $m[$k] = $Meta[$k] }
$params = @{ '_meta' = $m }
$headers = @{ 'Authorization' = "Bearer $Token"; 'MCP-Protocol-Version' = '2026-07-28'; 'Mcp-Method' = $Method }
if ($Method -eq 'tools/call') {
    if (-not $Name) { throw '-Name is required for tools/call' }
    $params['name'] = $Name
    $params['arguments'] = $Arguments
    $headers['Mcp-Name'] = $Name
}
$body = @{ jsonrpc = '2.0'; id = 1; method = $Method; params = $params } | ConvertTo-Json -Depth 20 -Compress
try {
    $resp = Invoke-WebRequest -UseBasicParsing -Method Post -Uri $Url -Headers $headers -ContentType 'application/json' `
        -Body ([System.Text.Encoding]::UTF8.GetBytes($body))
    $status = [int]$resp.StatusCode
    $content = $resp.Content
} catch {
    # Non-2xx (401, 400 header mismatch, ...): show the status and the body instead of a PowerShell error.
    $r = $_.Exception.Response
    if ($null -eq $r) { throw }
    $status = [int]$r.StatusCode
    if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $content = $_.ErrorDetails.Message }
    else { $content = (New-Object System.IO.StreamReader($r.GetResponseStream())).ReadToEnd() }
}
Write-Host "HTTP $status"
if (-not $content) { return }
$parsed = $content | ConvertFrom-Json
if ($PassThru) { return $parsed }
$parsed | ConvertTo-Json -Depth 20
