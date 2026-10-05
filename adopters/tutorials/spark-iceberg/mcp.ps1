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
# Minimal MCP 2026-07-28 client: one JSON-RPC request over Streamable HTTP with the MCP-Protocol-Version,
# Mcp-Method and Mcp-Name headers. Prints the JSON-RPC response.
#   Own host (bearer token):
#     .\mcp.ps1 -Token $env:READER_TOKEN -Name iceberg_list_tables -Arguments @{namespace='sales'}
#   Knox (HTTP Basic against Knox's demo LDAP, Knox's self-signed certificate):
#     .\mcp.ps1 -Basic '<knox-demo-user>:<knox-demo-password>' -Url $env:KNOX_MCP -Insecure -Name iceberg_list_tables -Arguments @{namespace='sales'}
#   Admin API (approvals): add -Admin and a -Path such as approvals/<id>/approve (POST) or approvals/<id> (GET).
param(
    [string]$Token,
    [string]$Basic,
    [string]$Method = 'tools/call',
    [string]$Name,
    [hashtable]$Arguments = @{},
    [hashtable]$Meta = @{},
    [string]$Url = 'http://127.0.0.1:8080/mcp',
    [switch]$Admin,
    [string]$Path,
    [string]$HttpMethod = 'POST',
    [switch]$Insecure,
    [switch]$PassThru
)
$ErrorActionPreference = 'Stop'
$headers = @{}
if ($Token) { $headers['Authorization'] = "Bearer $Token" }
elseif ($Basic) { $headers['Authorization'] = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Basic)) }
else { throw 'pass -Token (own host) or -Basic user:password (Knox)' }
$request = @{ UseBasicParsing = $true; Headers = $headers }
if ($Insecure) {
    # Only for Knox's self-signed localhost certificate in this tutorial.
    if ($PSVersionTable.PSVersion.Major -ge 6) { $request['SkipCertificateCheck'] = $true }
    else { [System.Net.ServicePointManager]::ServerCertificateValidationCallback = { $true } }
}
if ($Admin) {
    # The admin API sits beside /mcp: .../mcp -> .../aegis/v1/<path> (own host) or .../admin/<path> (Knox).
    $base = if ($Url -match '/aegis-mcp/v1/mcp$') { $Url -replace '/mcp$', '/admin/' } else { $Url -replace '/mcp$', '/aegis/v1/' }
    $request['Uri'] = $base + $Path
    $request['Method'] = $HttpMethod
} else {
    $m = @{ 'io.modelcontextprotocol/protocolVersion' = '2026-07-28'; 'io.modelcontextprotocol/clientCapabilities' = @{} }
    foreach ($k in $Meta.Keys) { $m[$k] = $Meta[$k] }
    $params = @{ '_meta' = $m }
    $headers['MCP-Protocol-Version'] = '2026-07-28'
    $headers['Mcp-Method'] = $Method
    if ($Method -eq 'tools/call') {
        if (-not $Name) { throw '-Name is required for tools/call' }
        $params['name'] = $Name
        $params['arguments'] = $Arguments
        $headers['Mcp-Name'] = $Name
    }
    $body = @{ jsonrpc = '2.0'; id = 1; method = $Method; params = $params } | ConvertTo-Json -Depth 20 -Compress
    $request['Uri'] = $Url
    $request['Method'] = 'POST'
    $request['ContentType'] = 'application/json'
    $request['Body'] = [System.Text.Encoding]::UTF8.GetBytes($body)
}
try {
    $resp = Invoke-WebRequest @request
    $status = [int]$resp.StatusCode
    $content = $resp.Content
} catch {
    $r = $_.Exception.Response
    if ($null -eq $r) { throw }
    $status = [int]$r.StatusCode
    if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $content = $_.ErrorDetails.Message }
    else { $content = (New-Object System.IO.StreamReader($r.GetResponseStream())).ReadToEnd() }
}
Write-Host "HTTP $status"
if (-not $content) { return }
if ($content -is [byte[]]) { $content = [System.Text.Encoding]::UTF8.GetString($content) }
try { $parsed = $content | ConvertFrom-Json } catch {
    # Not JSON, for example Knox's own error page for a failed login: show the start of it as text.
    Write-Host ($content.Substring(0, [Math]::Min(300, $content.Length)))
    return
}
if ($PassThru) { return $parsed }
$parsed | ConvertTo-Json -Depth 20
