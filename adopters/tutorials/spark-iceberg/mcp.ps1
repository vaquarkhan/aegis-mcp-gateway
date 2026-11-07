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
#   Knox (HTTP Basic against a locally configured Knox identity, Knox's exported PEM as the only trusted CA):
#     .\mcp.ps1 -Basic $basic -CaCert $caCert -Url $knox -Name spark_preview_table -Arguments @{table='lake.sales.orders'}
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
    # PEM file of the CA (or self-signed server certificate) to trust for this one request, for example
    # Knox's exported gateway-client-trust.pem. Nothing is added to the Windows certificate store, and
    # certificate and host-name validation stay on. Without it, HTTPS uses the normal system trust.
    [string]$CaCert,
    [switch]$PassThru
)
$ErrorActionPreference = 'Stop'
$headers = @{}
if ($Token) { $headers['Authorization'] = "Bearer $Token" }
elseif ($Basic) { $headers['Authorization'] = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Basic)) }
else { throw 'pass -Token (own host) or -Basic user:password (Knox)' }
$request = @{ UseBasicParsing = $true; Headers = $headers }
if ($CaCert) {
    if ($Url -notlike 'https://*') { throw '-CaCert applies to https:// URLs only' }
    if (-not (Test-Path -LiteralPath $CaCert)) { throw "CA file not found: $CaCert" }
    $CaCert = (Resolve-Path -LiteralPath $CaCert).Path
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
if ($CaCert) {
    # Invoke-WebRequest in Windows PowerShell 5.1 cannot trust one extra CA without changing the user's
    # certificate store, so this path uses curl.exe (part of Windows 10 1803 and later) with --cacert.
    # The request goes through a temporary curl config file in the per-user TEMP folder, so the
    # Authorization header never appears on a command line other local processes can read.
    $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
    if (-not $curl) { throw '-CaCert needs curl.exe (included with Windows 10 1803 and later)' }
    $fwd = { param($p) $p -replace '\\', '/' }   # curl config strings treat a backslash as an escape
    $cfg = [IO.Path]::GetTempFileName(); $bodyFile = [IO.Path]::GetTempFileName(); $outFile = [IO.Path]::GetTempFileName()
    try {
        $lines = @('silent', 'show-error',
            ('url = "{0}"' -f $request['Uri']),
            ('request = {0}' -f $request['Method']),
            ('cacert = "{0}"' -f (& $fwd $CaCert)),
            ('output = "{0}"' -f (& $fwd $outFile)),
            'write-out = "%{http_code}"')
        foreach ($k in $headers.Keys) { $lines += ('header = "{0}: {1}"' -f $k, $headers[$k]) }
        if ($request.ContainsKey('Body')) {
            [IO.File]::WriteAllBytes($bodyFile, $request['Body'])
            $lines += 'header = "Content-Type: application/json"'
            $lines += ('data-binary = "@{0}"' -f (& $fwd $bodyFile))
        }
        [IO.File]::WriteAllLines($cfg, [string[]]$lines, (New-Object Text.UTF8Encoding($false)))
        $ErrorActionPreference = 'Continue'   # curl's own stderr must not become a PowerShell error
        $result = & $curl.Source -K $cfg 2>&1
        $exit = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        if ($exit -ne 0) { throw "curl.exe failed (exit $exit): $(($result | ForEach-Object { "$_" }) -join ' ')" }
        $status = [int](($result | ForEach-Object { "$_" }) -join '').Trim()
        $content = [IO.File]::ReadAllText($outFile, [Text.Encoding]::UTF8)
    } finally {
        Remove-Item -LiteralPath $cfg, $bodyFile, $outFile -Force -ErrorAction SilentlyContinue
    }
} else {
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
