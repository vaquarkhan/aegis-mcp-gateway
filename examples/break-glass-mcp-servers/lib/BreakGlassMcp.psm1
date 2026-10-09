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

# Shared functions for the break-glass MCP server example: real engines, the Aegis gateway jar as an MCP
# server, the offline signed server-catalog and break-glass workflow, and plain MCP calls over HTTP.
# Windows PowerShell 5.1 compatible.

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$script:Here = Split-Path -Parent $PSScriptRoot                     # examples/break-glass-mcp-servers
$script:Repo = (Resolve-Path (Join-Path $script:Here '..\..')).Path  # repository root
$script:Run = $null                                                  # <server>\run, set by Get-BgEngine
Add-Type -AssemblyName System.Net.Http
$handler = New-Object System.Net.Http.HttpClientHandler
$handler.UseProxy = $false   # every call here is to loopback; never route it through a system proxy
$script:Http = New-Object System.Net.Http.HttpClient($handler)
$script:Http.Timeout = [TimeSpan]::FromSeconds(60)
$script:McpId = 0

# Flink 1.20 and Spark 4 on Java 17 need these opens (the same list the real-engine tests use).
$script:Opens = @('--add-opens=java.base/java.util=ALL-UNNAMED', '--add-opens=java.base/java.lang=ALL-UNNAMED',
    '--add-opens=java.base/java.lang.reflect=ALL-UNNAMED', '--add-opens=java.base/java.net=ALL-UNNAMED',
    '--add-opens=java.base/java.io=ALL-UNNAMED', '--add-opens=java.base/java.nio=ALL-UNNAMED',
    '--add-opens=java.base/sun.nio.ch=ALL-UNNAMED', '--add-opens=java.base/java.time=ALL-UNNAMED',
    '--add-opens=java.base/java.util.concurrent=ALL-UNNAMED', '--add-opens=java.base/java.text=ALL-UNNAMED',
    '--add-opens=java.base/java.lang.invoke=ALL-UNNAMED', '--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED',
    '--add-opens=java.base/sun.nio.cs=ALL-UNNAMED', '--add-opens=java.base/sun.security.action=ALL-UNNAMED',
    '--add-opens=java.base/sun.util.calendar=ALL-UNNAMED', '-Djdk.reflect.useDirectMethodHandle=false',
    '-Dio.netty.tryReflectionSetAccessible=true')

# Runs a native command with all output in $Log and returns its exit code. Windows PowerShell 5.1 turns any
# stderr line (for example the JVM's "Picked up JAVA_TOOL_OPTIONS") into an error record, which would stop
# the script under ErrorActionPreference=Stop; the exit code is what decides success here.
function Invoke-Native([string]$Exe, [string[]]$Arguments, [string]$Log) {
    $err = "$Log.stderr"
    $quoted = @($Arguments | ForEach-Object { if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ } })
    $p = Start-Process -FilePath $Exe -ArgumentList $quoted -NoNewWindow -Wait -PassThru `
        -RedirectStandardOutput $Log -RedirectStandardError $err
    # The JVM announces JAVA_TOOL_OPTIONS on stderr; keep everything else after stdout, in one log.
    $rest = @(Get-Content $err -ErrorAction SilentlyContinue | Where-Object { $_ -notmatch '^Picked up JAVA_TOOL_OPTIONS' })
    if ($rest.Count -gt 0) { Add-Content -Path $Log -Value $rest }
    Remove-Item $err -ErrorAction SilentlyContinue
    return $p.ExitCode
}

function Get-Java {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { return (Join-Path $env:JAVA_HOME 'bin\java.exe') }
    return 'java'
}

function Get-Javac {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) { return (Join-Path $env:JAVA_HOME 'bin\javac.exe') }
    return 'javac'
}

function Get-Mvn {
    if (Test-Path 'C:\tools\apache-maven-3.9.11\bin\mvn.cmd') { return 'C:\tools\apache-maven-3.9.11\bin\mvn.cmd' }
    return 'mvn'
}

# ------------------------------------------------------------------------------------------- engines

<#
 One independent MCP server: its folder (with the aegis.yaml it serves), the gateway jar that serves it,
 its fixed loopback port, the sources its YAML declares, and the break-glass order the run signs.
 Sets this session's run directory to <server>\run.
#>
function Get-BgEngine([string]$Name) {
    $gw = Join-Path $script:Repo 'aegis-gateway\target\aegis-gateway-2.0.0-SNAPSHOT-all.jar'
    switch ($Name) {
        'flink' { $s = @{ Gateway = 'aegis-flink'; GatewayJar = $gw; Port = 18801; Env = @{}
                Tamper = 'http://127.0.0.1:18081'; Contain = 'flink=QUARANTINED'; Sources = @('flink') } }
        'iceberg' { $s = @{ Gateway = 'aegis-iceberg'; GatewayJar = $gw; Port = 18802
                Env = @{ ICEBERG_CATALOG_TOKEN = 'break-glass-example-catalog-credential' }
                Tamper = 'http://127.0.0.1:18181'; Contain = 'iceberg=REVOKED'; Sources = @('iceberg') } }
        'spark' { $s = @{ Gateway = 'aegis-spark'; GatewayJar = $gw; Port = 18803; Env = @{}
                Tamper = 'http://127.0.0.1:16066'; Contain = 'spark-master=REVOKED'; Sources = @('spark-history', 'spark-master') } }
        'kafka' { $s = @{ Gateway = 'aegis-kafka'; Port = 18804; Env = @{}
                GatewayJar = (Join-Path $script:Repo 'examples\aegis-gateway-kafka\target\aegis-gateway-kafka-2.0.0-SNAPSHOT-all.jar')
                Tamper = 'http://127.0.0.1:18083'; Contain = 'kafka-connect=REVOKED'; Sources = @('kafka-connect', 'schema-registry', 'kafka-admin') } }
        default { throw "unknown MCP server '$Name' (flink, iceberg, spark, kafka)" }
    }
    $s.Name = $Name
    $s.Dir = Join-Path $script:Here $Name
    $s.Config = Join-Path $s.Dir 'aegis.yaml'
    $s.Run = Join-Path $s.Dir 'run'
    if (-not (Test-Path $s.Config)) { throw "missing $($s.Config)" }
    $script:Run = $s.Run
    New-Item -ItemType Directory -Force -Path $s.Run | Out-Null
    return [pscustomobject]$s
}

<#
 Configurations derived from the server's own aegis.yaml, written to run\ (so './run/x' becomes './x'
 and every relative path still names the same file). The sources: section is never changed except by
 'tampered', so the signed source digests stay valid for every derived copy:
   review       - without serverCatalog: what a reviewer starts to read the definitions to sign
   break-glass  - with the commented breakGlass block enabled
   review-key   - break-glass, but naming the review key as the incident key (must be refused)
   tampered     - the signed file with one source pointed elsewhere (must be refused)
#>
function New-DerivedConfig($Engine, [string]$Kind) {
    $yaml = (Get-Content -Raw $Engine.Config).Replace('./run/', './')
    switch ($Kind) {
        'review' {
            $out = New-Object System.Text.StringBuilder
            $skip = $false
            foreach ($line in ($yaml -split "`r?`n")) {
                if ($line -match '^serverCatalog:') { $skip = $true; continue }
                if ($skip -and ($line -match '^\s' -or $line -eq '')) { continue }
                $skip = $false
                [void]$out.AppendLine($line)
            }
            $yaml = $out.ToString()
            if ($yaml -match '(?m)^serverCatalog:') { throw 'could not remove serverCatalog' }
        }
        'break-glass' { $yaml = Enable-BreakGlass $yaml }
        'review-key' { $yaml = (Enable-BreakGlass $yaml).Replace('    publicKeyFile: ./incident.pub', '    publicKeyFile: ./review.pub') }
        'tampered' {
            if (-not $yaml.Contains($Engine.Tamper)) { throw "aegis.yaml does not contain $($Engine.Tamper)" }
            $yaml = $yaml.Replace($Engine.Tamper, $Engine.Tamper.Replace('127.0.0.1', '127.0.0.2'))
        }
        default { throw "unknown derived configuration '$Kind'" }
    }
    $file = Join-Path $Engine.Run "aegis-$Kind.yaml"
    [System.IO.File]::WriteAllText($file, $yaml, (New-Object System.Text.UTF8Encoding($false)))
    return $file
}

function Enable-BreakGlass([string]$Yaml) {
    $on = $Yaml.Replace("  # breakGlass:", "  breakGlass:").Replace("  #   file: ./order.json", "    file: ./order.json").Replace("  #   publicKeyFile: ./incident.pub", "    publicKeyFile: ./incident.pub")
    if ($on -notmatch '(?m)^  breakGlass:\r?\n    file: \./order\.json\r?\n    publicKeyFile: \./incident\.pub') { throw 'the breakGlass block in aegis.yaml is not in the expected form' }
    return $on
}

function Assert-BgPrerequisites([string[]]$Engines) {
    $need = @((Join-Path $script:Repo 'aegis-gateway\target\aegis-gateway-2.0.0-SNAPSHOT-all.jar'))
    if ($Engines -contains 'kafka') {
        $need += (Join-Path $script:Repo 'examples\aegis-gateway-kafka\target\aegis-gateway-kafka-2.0.0-SNAPSHOT-all.jar')
        $need += (Join-Path $script:Repo 'examples\aegis-validation-kafka-server\target\server.classpath')
    }
    if ($Engines -contains 'iceberg') { $need += (Join-Path $script:Repo 'examples\aegis-validation-iceberg-server\target\server.classpath') }
    $missing = @($need | Where-Object { -not (Test-Path $_) })
    if ($missing.Count -gt 0) {
        throw ("missing build output (run from the repository root: mvn -B -Preal-engines -DskipTests install):`n  " + ($missing -join "`n  "))
    }
}

# Classpath for the Flink and Spark launchers: the real-engine validation module's test classpath, which
# pins Flink 1.20.1 and Spark 4.0.1 exactly as the two-door tests use them. Cached under <server>\run\_classpath.
function Get-EngineClasspath {
    $dir = New-Item -ItemType Directory -Force -Path (Join-Path $script:Run '_classpath')
    $file = Join-Path $dir.FullName 'validation.classpath'
    if (-not (Test-Path $file)) {
        $log = Join-Path $dir.FullName 'build-classpath.log'
        $code = Invoke-Native (Get-Mvn) @('-B', '-q', '-Preal-engines', '-f', (Join-Path $script:Repo 'pom.xml'), '-pl', 'examples/aegis-validation',
            'dependency:build-classpath', "-Dmdep.outputFile=$file", '-Dmdep.includeScope=test') $log
        if ($code -ne 0 -or -not (Test-Path $file)) { throw "could not resolve the Flink/Spark classpath; see $log" }
    }
    return (Get-Content $file -Raw).Trim()
}

# Writes a java @argfile (forward slashes, no quotes needed: these paths carry no spaces).
function New-JavaArgFile([string]$Path, [string]$Classpath, [string[]]$Extra) {
    $lines = @()
    if ($Extra) { $lines += $Extra }
    $lines += '-cp'
    $lines += ($Classpath -replace '\\', '/')
    [System.IO.File]::WriteAllLines($Path, [string[]]$lines, (New-Object System.Text.UTF8Encoding($false)))
    return $Path
}

function Compile-Launcher([string]$Source, [string]$Classpath, [string]$Out) {
    New-Item -ItemType Directory -Force -Path $Out | Out-Null
    $af = New-JavaArgFile (Join-Path $Out 'javac.args') $Classpath @('-d', ($Out -replace '\\', '/'), '-nowarn')
    $code = Invoke-Native (Get-Javac) @("@$af", $Source) (Join-Path $Out 'javac.log')
    if ($code -ne 0) { throw "javac failed for $Source; see $(Join-Path $Out 'javac.log')" }
}

# Starts a java process in the background with stdout/stderr in log files. $EnvVars are set only for it.
function Start-BgJava([string]$Name, [string[]]$Arguments, [string]$Logs, [hashtable]$EnvVars) {
    New-Item -ItemType Directory -Force -Path $Logs | Out-Null
    $saved = @{}
    if ($EnvVars) { foreach ($k in $EnvVars.Keys) { $saved[$k] = [Environment]::GetEnvironmentVariable($k); [Environment]::SetEnvironmentVariable($k, $EnvVars[$k]) } }
    try {
        $p = Start-Process -FilePath (Get-Java) -ArgumentList $Arguments -WorkingDirectory $Logs -PassThru -WindowStyle Hidden `
            -RedirectStandardOutput (Join-Path $Logs "$Name.out.log") -RedirectStandardError (Join-Path $Logs "$Name.err.log")
    } finally {
        foreach ($k in $saved.Keys) { [Environment]::SetEnvironmentVariable($k, $saved[$k]) }
    }
    $null = $p.Handle   # keeps ExitCode readable after the process ends (Windows PowerShell quirk)
    New-Item -ItemType Directory -Force -Path $script:Run | Out-Null
    Add-Content -Path (Join-Path $script:Run 'pids.txt') -Value "$($p.Id) $Name"
    return $p
}

function Stop-BgProcess($Process) {
    if ($null -ne $Process -and -not $Process.HasExited) {
        Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue
        $Process.WaitForExit(30000) | Out-Null
    }
}

function Wait-Until([scriptblock]$Condition, [int]$Seconds, [string]$What, $Process) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        if ($null -ne $Process -and $Process.HasExited) { throw "$What : the process exited with code $($Process.ExitCode)" }
        $ok = $false
        try { $ok = & $Condition } catch { $ok = $false }
        if ($ok) { return }
        Start-Sleep -Milliseconds 500
    }
    throw "timed out after $Seconds s waiting for $What"
}

function Test-PortFree([int]$Port) {
    $l = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, $Port)
    try { $l.Start(); return $true } catch { return $false } finally { try { $l.Stop() } catch { } }
}

# --------------------------------------------------------------------------------------------- HTTP

function Invoke-Http([string]$Method, [string]$Url, [hashtable]$Headers, [string]$Body) {
    $req = New-Object System.Net.Http.HttpRequestMessage((New-Object System.Net.Http.HttpMethod($Method)), $Url)
    if ($Headers) { foreach ($k in $Headers.Keys) { [void]$req.Headers.TryAddWithoutValidation($k, [string]$Headers[$k]) } }
    # PowerShell turns a $null [string] into '': only a non-empty body is sent (a GET must carry none).
    if (-not [string]::IsNullOrEmpty($Body)) { $req.Content = New-Object System.Net.Http.StringContent($Body, [System.Text.Encoding]::UTF8, 'application/json') }
    try {
        $rsp = $script:Http.SendAsync($req).GetAwaiter().GetResult()
        return [pscustomobject]@{ Status = [int]$rsp.StatusCode; Body = $rsp.Content.ReadAsStringAsync().GetAwaiter().GetResult() }
    } catch {
        return [pscustomobject]@{ Status = 0; Body = $_.Exception.Message }
    }
}

# One MCP request over Streamable HTTP with the 2026-07-28 headers. Returns status, body, the parsed
# JSON-RPC message, the Aegis decision (outcome, code) and the text the model would see.
function Invoke-Mcp([int]$Port, [string]$Token, [string]$Method, [string]$Tool, [hashtable]$Arguments) {
    $script:McpId++
    $meta = @{ 'io.modelcontextprotocol/protocolVersion' = '2026-07-28'; 'io.modelcontextprotocol/clientCapabilities' = @{} }
    $params = @{ '_meta' = $meta }
    $headers = @{ Authorization = "Bearer $Token"; Accept = 'application/json, text/event-stream'
        'MCP-Protocol-Version' = '2026-07-28'; 'Mcp-Method' = $Method }
    if ($Tool) {
        $params['name'] = $Tool
        if ($null -eq $Arguments) { $Arguments = @{} }
        $params['arguments'] = $Arguments
        $headers['Mcp-Name'] = $Tool
    }
    $body = (@{ jsonrpc = '2.0'; id = $script:McpId; method = $Method; params = $params } | ConvertTo-Json -Depth 20 -Compress)
    $r = Invoke-Http 'POST' "http://127.0.0.1:$Port/mcp" $headers $body
    $msg = $null; $decision = $null; $text = ''
    if ($r.Status -eq 200 -and $r.Body.StartsWith('{')) {
        $msg = $r.Body | ConvertFrom-Json
        if ($msg.PSObject.Properties['result'] -and $msg.result.PSObject.Properties['_meta']) {
            $m = $msg.result._meta
            if ($m.PSObject.Properties['io.github.vaquarkhan.aegis/decision']) { $decision = $m.'io.github.vaquarkhan.aegis/decision' }
        }
        if ($msg.PSObject.Properties['result'] -and $msg.result.PSObject.Properties['content']) {
            foreach ($c in @($msg.result.content)) { if ($c.PSObject.Properties['text']) { $text += [string]$c.text + "`n" } }
        }
        if ($msg.PSObject.Properties['result'] -and $msg.result.PSObject.Properties['structuredContent']) {
            $text += ($msg.result.structuredContent | ConvertTo-Json -Depth 20 -Compress)
        }
    }
    $outcome = $null; $code = $null
    if ($null -ne $decision) {
        if ($decision.PSObject.Properties['outcome']) { $outcome = [string]$decision.outcome }
        if ($decision.PSObject.Properties['code']) { $code = [string]$decision.code }
    }
    return [pscustomobject]@{ Status = $r.Status; Body = $r.Body; Message = $msg; Outcome = $outcome; Code = $code; Text = $text }
}

# --------------------------------------------------------------------------------------------- gateway

function New-Token { -join ((1..32) | ForEach-Object { '{0:x}' -f (Get-Random -Maximum 16) }) }

function Get-GatewayEnv($Engine, [hashtable]$Tokens) {
    $e = @{ AEGIS_SIGNING_KEY = 'break-glass-example-signing-key-0123456789abcdef'
        AEGIS_BG_READER = $Tokens.reader; AEGIS_BG_OPERATOR = $Tokens.operator
        AEGIS_BG_APPROVER = $Tokens.approver; AEGIS_BG_OUTSIDER = $Tokens.outsider }
    foreach ($k in $Engine.Env.Keys) { $e[$k] = $Engine.Env[$k] }
    return $e
}

# Starts the Aegis MCP server with a configuration and waits for /healthz. Returns the process.
function Start-AegisMcp($Engine, [string]$Config, [string]$Logs, [string]$Label, [hashtable]$EnvVars) {
    # A just-stopped instance can hold the port for a moment; anything else holding it is an error.
    Wait-Until { Test-PortFree $Engine.Port } 20 "port $($Engine.Port) to be free (stop the other MCP server first: Stop-McpServer.ps1)" $null
    $p = Start-BgJava "aegis-$Label" @('-jar', $Engine.GatewayJar, '--config', $Config) $Logs $EnvVars
    Wait-Until { (Invoke-Http 'GET' "http://127.0.0.1:$($Engine.Port)/healthz" @{} $null).Status -eq 200 } 90 "the $Label MCP server on port $($Engine.Port)" $p
    return $p
}

# Starts the Aegis MCP server with a configuration that must be refused. Returns the exit code and stderr.
function Start-AegisExpectRefusal($Engine, [string]$Config, [string]$Logs, [string]$Label, [hashtable]$EnvVars) {
    $p = Start-BgJava "aegis-$Label" @('-jar', $Engine.GatewayJar, '--config', $Config) $Logs $EnvVars
    if (-not $p.WaitForExit(90000)) {
        Stop-BgProcess $p
        return [pscustomobject]@{ Exited = $false; ExitCode = $null; Error = 'still running after 90 s' }
    }
    $p.WaitForExit()
    $err = Get-Content -Raw (Join-Path $Logs "aegis-$Label.err.log") -ErrorAction SilentlyContinue
    return [pscustomobject]@{ Exited = $true; ExitCode = $p.ExitCode; Error = [string]$err }
}

# Runs one gateway-jar command (keygen, server-catalog, break-glass, manifest-sign, verify-audit).
function Invoke-AegisCli($Engine, [string[]]$Arguments, [string]$Logs, [string]$Label) {
    New-Item -ItemType Directory -Force -Path $Logs | Out-Null
    $out = Join-Path $Logs "cli-$Label.log"
    $code = Invoke-Native (Get-Java) (@('-jar', $Engine.GatewayJar) + $Arguments) $out
    return [pscustomobject]@{ ExitCode = $code; Output = (Get-Content -Raw $out -ErrorAction SilentlyContinue) }
}

function Get-AegisCatalog($Engine, [string]$ApproverToken) {
    $r = Invoke-Http 'GET' "http://127.0.0.1:$($Engine.Port)/aegis/v1/catalog" @{ Authorization = "Bearer $ApproverToken" } $null
    if ($r.Status -ne 200) { throw "GET /aegis/v1/catalog answered $($r.Status): $($r.Body)" }
    return [pscustomobject]@{ Body = $r.Body; Tools = @(($r.Body | ConvertFrom-Json).tools) }
}

# ------------------------------------------------------------------------------------- real engines

function Assert-PortsFree([int[]]$Ports) {
    foreach ($port in $Ports) { if (-not (Test-PortFree $port)) { throw "port $port is in use; stop what holds it (Stop-McpServer.ps1 stops this example's processes)" } }
}

function Get-LauncherClasspath([string]$Source, [string]$Name, [string]$Classpath) {
    $out = Join-Path $script:Run "_launchers\$Name"
    if (-not (Test-Path (Join-Path $out "$Name.class"))) { Compile-Launcher $Source $Classpath $out }
    return "$Classpath;$out"
}

function Wait-ReadyLine([string]$Log, $Process, [int]$Seconds, [string]$What) {
    $line = $null
    Wait-Until { $script:ready = Select-String -Path $Log -Pattern '^READY ' -ErrorAction SilentlyContinue | Select-Object -First 1; $null -ne $script:ready } $Seconds $What $Process
    return [string]$script:ready.Line
}

<#
 Starts the real engine(s) one profile governs, on fixed loopback ports, and seeds the data the checks use.
 Returns the processes and the facts (job id, driver id, connector name) the checks need.
#>
function Start-BgEngine($Engine, [string]$Dir) {
    $work = New-Item -ItemType Directory -Force -Path (Join-Path $Dir ('engine-' + (Get-Date -Format 'yyyyMMdd-HHmmss')))
    $logs = Join-Path $Dir 'logs'
    $procs = New-Object System.Collections.ArrayList
    $facts = @{}
    try {
    switch ($Engine.Name) {
        'flink' {
            Assert-PortsFree @(18081)
            $cp = Get-LauncherClasspath (Join-Path $Engine.Dir 'engine\FlinkCluster.java') 'FlinkCluster' (Get-EngineClasspath)
            $af = New-JavaArgFile (Join-Path $work.FullName 'flink.args') $cp $script:Opens
            $p = Start-BgJava 'flink' @("@$af", 'FlinkCluster', '18081') $logs $null
            [void]$procs.Add($p)
            $ready = Wait-ReadyLine (Join-Path $logs 'flink.out.log') $p 240 'the Flink cluster'
            $facts.jobId = ([regex]::Match($ready, 'jobId=([0-9a-f]+)')).Groups[1].Value
        }
        'spark' {
            Assert-PortsFree @(14040, 16066, 17077, 17078)
            $cp = Get-LauncherClasspath (Join-Path $Engine.Dir 'engine\SparkServices.java') 'SparkServices' (Get-EngineClasspath)
            $af = New-JavaArgFile (Join-Path $work.FullName 'spark.args') $cp ($script:Opens + @('-Xmx1G'))
            $p = Start-BgJava 'spark' @("@$af", 'SparkServices', '14040', '16066', '17077') $logs $null
            [void]$procs.Add($p)
            Wait-ReadyLine (Join-Path $logs 'spark.out.log') $p 240 'the Spark Master and application' | Out-Null
            # Setup outside Aegis, as spark-submit --deploy-mode cluster would do: the driver waits for a worker.
            $body = @{ action = 'CreateSubmissionRequest'; clientSparkVersion = '4.0.1'; appResource = 'file:/opt/jobs/aegis-example-job.jar'
                mainClass = 'org.example.NightlyAggregation'; appArgs = @(); environmentVariables = @{}
                sparkProperties = @{ 'spark.app.name' = 'nightly-aggregation'; 'spark.master' = 'spark://127.0.0.1:17077'
                    'spark.submit.deployMode' = 'cluster'; 'spark.driver.supervise' = 'false' } } | ConvertTo-Json -Depth 5 -Compress
            $r = Invoke-Http 'POST' 'http://127.0.0.1:16066/v1/submissions/create' @{} $body
            $facts.driver = [string](($r.Body | ConvertFrom-Json).submissionId)
            if (-not $facts.driver) { throw "the Spark Master refused the submission: $($r.Body)" }
        }
        'iceberg' {
            Assert-PortsFree @(18181)
            $cp = (Get-Content -Raw (Join-Path $script:Repo 'examples\aegis-validation-iceberg-server\target\server.classpath')).Trim()
            $af = New-JavaArgFile (Join-Path $work.FullName 'iceberg.args') $cp @('-Xmx1G')
            $wh = New-Item -ItemType Directory -Force -Path (Join-Path $work.FullName 'warehouse')
            $envv = @{ CATALOG_REST_PORT = '18181'; CATALOG_WAREHOUSE = ($wh.FullName -replace '\\', '/')
                CATALOG_IO__IMPL = 'org.apache.iceberg.inmemory.InMemoryFileIO'; CATALOG_CATALOG__IMPL = 'org.apache.iceberg.jdbc.JdbcCatalog'
                CATALOG_URI = 'jdbc:sqlite:file:' + ((Join-Path $work.FullName 'catalog.db') -replace '\\', '/')
                CATALOG_JDBC_USER = 'aegis'; CATALOG_JDBC_PASSWORD = 'aegis' }
            $p = Start-BgJava 'iceberg' @("@$af", 'org.apache.iceberg.rest.RESTCatalogServer') $logs $envv
            [void]$procs.Add($p)
            Wait-Until { (Invoke-Http 'GET' 'http://127.0.0.1:18181/v1/config' @{} $null).Status -eq 200 } 180 'the Iceberg REST catalog' $p
            $schema = '{"type":"struct","schema-id":0,"fields":[{"id":1,"name":"id","required":true,"type":"long"}]}'
            foreach ($ns in 'sales', 'finance') { Invoke-Http 'POST' 'http://127.0.0.1:18181/v1/namespaces' @{} "{`"namespace`":[`"$ns`"],`"properties`":{}}" | Out-Null }
            foreach ($t in @(@('sales', 'orders'), @('finance', 'ledger'))) {
                $r = Invoke-Http 'POST' "http://127.0.0.1:18181/v1/namespaces/$($t[0])/tables" @{} "{`"name`":`"$($t[1])`",`"schema`":$schema}"
                if ($r.Status -ne 200) { throw "could not create $($t[0]).$($t[1]): HTTP $($r.Status) $($r.Body)" }
            }
            $facts.tables = 'sales.orders,finance.ledger'
        }
        'kafka' {
            Assert-PortsFree @(19092, 19093, 18083)
            $cp = (Get-Content -Raw (Join-Path $script:Repo 'examples\aegis-validation-kafka-server\target\server.classpath')).Trim()
            $af = New-JavaArgFile (Join-Path $work.FullName 'kafka.args') $cp $null
            $w = $work.FullName -replace '\\', '/'
            $server = Join-Path $work.FullName 'server.properties'
            [System.IO.File]::WriteAllLines($server, [string[]]@('process.roles=broker,controller', 'node.id=1',
                'controller.quorum.voters=1@127.0.0.1:19093', 'listeners=PLAINTEXT://127.0.0.1:19092,CONTROLLER://127.0.0.1:19093',
                'advertised.listeners=PLAINTEXT://127.0.0.1:19092', 'controller.listener.names=CONTROLLER',
                'listener.security.protocol.map=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT', 'inter.broker.listener.name=PLAINTEXT',
                "log.dirs=$w/kraft-logs", 'num.partitions=1', 'offsets.topic.replication.factor=1',
                'transaction.state.log.replication.factor=1', 'transaction.state.log.min.isr=1', 'group.initial.rebalance.delay.ms=0'))
            # kafka-storage.sh format, then kafka-server-start.sh: the two steps a KRaft node needs.
            $fmt = Start-BgJava 'kafka-storage-format' @("@$af", 'kafka.tools.StorageTool', 'format', '-t', ([Convert]::ToBase64String([guid]::NewGuid().ToByteArray()).TrimEnd('=').Replace('+', '-').Replace('/', '_')), '-c', $server) $logs $null
            if (-not $fmt.WaitForExit(120000) -or $fmt.ExitCode -ne 0) { throw "kafka-storage format failed; see $logs" }
            $p = Start-BgJava 'kafka-broker' @("@$af", 'kafka.Kafka', $server) $logs $null
            [void]$procs.Add($p)
            $seedCp = Get-LauncherClasspath (Join-Path $Engine.Dir 'engine\KafkaSeed.java') 'KafkaSeed' $cp
            $saf = New-JavaArgFile (Join-Path $work.FullName 'seed.args') $seedCp $null
            $seed = Start-BgJava 'kafka-seed' @("@$saf", 'KafkaSeed', '127.0.0.1:19092') $logs $null
            if (-not $seed.WaitForExit(180000) -or $seed.ExitCode -ne 0) { throw "seeding the orders topic failed; see $logs" }
            $worker = Join-Path $work.FullName 'connect-standalone.properties'
            [System.IO.File]::WriteAllLines($worker, [string[]]@('bootstrap.servers=127.0.0.1:19092',
                'key.converter=org.apache.kafka.connect.storage.StringConverter', 'value.converter=org.apache.kafka.connect.storage.StringConverter',
                "offset.storage.file.filename=$w/connect.offsets", 'listeners=http://127.0.0.1:18083'))
            $c = Start-BgJava 'kafka-connect' @("@$af", 'org.apache.kafka.connect.cli.ConnectStandalone', $worker) $logs $null
            [void]$procs.Add($c)
            Wait-Until { (Invoke-Http 'GET' 'http://127.0.0.1:18083/connectors' @{} $null).Status -eq 200 } 240 'the Kafka Connect worker' $c
            $source = Join-Path $work.FullName 'orders.txt'
            Set-Content -Path $source -Value "order-1`norder-2" -Encoding ascii
            $facts.connector = 'orders-file-source'
            $body = @{ name = $facts.connector; config = @{ 'connector.class' = 'org.apache.kafka.connect.file.FileStreamSourceConnector'
                'tasks.max' = '1'; file = ($source -replace '\\', '/'); topic = 'orders' } } | ConvertTo-Json -Depth 5 -Compress
            $r = Invoke-Http 'POST' 'http://127.0.0.1:18083/connectors' @{} $body
            if ($r.Status -ne 201) { throw "could not create the connector: HTTP $($r.Status) $($r.Body)" }
        }
    }
    } catch {
        # An engine that did not come up completely is stopped, never left running in the background.
        Stop-BgEngine ([pscustomobject]@{ Processes = $procs })
        throw
    }
    return [pscustomobject]@{ Processes = $procs; Facts = $facts }
}

function Stop-BgEngine($Started) {
    if ($null -eq $Started) { return }
    $list = @($Started.Processes)
    [array]::Reverse($list)
    foreach ($p in $list) { Stop-BgProcess $p }
}

# Direct reads of real engine state, outside Aegis, to show what a governed call did or did not change.
function Get-SparkDriverState([string]$Driver) {
    $r = Invoke-Http 'GET' "http://127.0.0.1:16066/v1/submissions/status/$Driver" @{} $null
    return [string](($r.Body | ConvertFrom-Json).driverState)
}

function Get-KafkaConnectors {
    $r = Invoke-Http 'GET' 'http://127.0.0.1:18083/connectors' @{} $null
    return @($r.Body | ConvertFrom-Json)
}

function Set-Text([string]$Path, [string]$Text) {
    [System.IO.File]::WriteAllText($Path, $Text, (New-Object System.Text.UTF8Encoding($false)))
}

# The reads each server's checks make, with the text the real engine must return.
function Get-BgReads($Engine, $Facts) {
    switch ($Engine.Name) {
        'flink' { return , @(@{ tool = 'flink_list_jobs'; args = @{}; expect = $Facts.jobId; source = 'flink' }) }
        'iceberg' { return , @(@{ tool = 'iceberg_list_tables'; args = @{ namespace = 'sales' }; expect = 'orders'; source = 'iceberg' }) }
        'spark' {
            return , @(@{ tool = 'spark_list_applications'; args = @{ status = 'running' }; expect = 'aegis-spark-example'; source = 'spark-history' },
                @{ tool = 'spark_submission_status'; args = @{ submissionId = $Facts.driver }; expect = 'SUBMITTED'; source = 'spark-master' })
        }
        'kafka' {
            return , @(@{ tool = 'kafka_list_topics'; args = @{}; expect = 'orders'; source = 'kafka-admin' },
                @{ tool = 'connect_list_connectors'; args = @{}; expect = $Facts.connector; source = 'kafka-connect' })
        }
    }
}

<#
 The whole life of one MCP server, against its real engine: review, sign, serve its own aegis.yaml,
 tamper, break-glass, audit. Writes run\RESULT.md and returns the number of failed checks.
#>
function Invoke-BgEndToEnd([string]$Name) {
    $e = Get-BgEngine $Name
    Assert-BgPrerequisites @($Name)
    $run = $e.Run
    # A fresh run: earlier results go; caches and engine data an old process may still hold stay.
    Get-ChildItem $run -Exclude 'engine-*', '_classpath', '_launchers' | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
    $keys = New-Item -ItemType Directory -Force -Path (Join-Path $run 'keys')
    $logs = Join-Path $run 'logs'
    $checks = New-Object System.Collections.ArrayList
    $check = {
        param([string]$Step, [bool]$Ok, [string]$Detail)
        [void]$checks.Add([pscustomobject]@{ Step = $Step; Ok = $Ok; Detail = $Detail })
        $mark = 'FAIL'; if ($Ok) { $mark = 'PASS' }
        Write-Host ("[{0}] {1,-8} {2} - {3}" -f $mark, $Name, $Step, $Detail)
    }
    $tokens = @{ reader = New-Token; operator = New-Token; approver = New-Token; outsider = New-Token }
    Set-Text (Join-Path $keys.FullName 'tokens.json') ($tokens | ConvertTo-Json)
    $envv = Get-GatewayEnv $e $tokens
    $started = $null; $gw = $null
    Write-Host "`n=== $Name MCP server ($($e.Config)) ==="
    try {
        $started = Start-BgEngine $e $run
        # Every engine process is still running and the launcher reported the facts the checks need
        # (job id, driver id, connector, seeded table), so later reads hit a live engine.
        $procs = @($started.Processes | Where-Object { $null -ne $_ })
        $alive = @($procs | Where-Object { -not $_.HasExited }).Count
        $facts = @($started.Facts.GetEnumerator())
        $factsOk = $facts.Count -gt 0 -and @($facts | Where-Object { [string]::IsNullOrWhiteSpace([string]$_.Value) }).Count -eq 0
        & $check 'engine started' ($procs.Count -gt 0 -and $alive -eq $procs.Count -and $factsOk) ("$alive of $($procs.Count) processes running; " + (($facts | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ' '))
        $reads = Get-BgReads $e $started.Facts
        $first = $reads[0]

        # 1. Review: aegis.yaml without its serverCatalog section; nothing is trusted yet.
        $gw = Start-AegisMcp $e (New-DerivedConfig $e 'review') $logs 'review' $envv
        $cat = Get-AegisCatalog $e $tokens.approver
        Set-Text (Join-Path $run 'catalog.json') $cat.Body
        $q = @($cat.Tools | Where-Object { $_.status -eq 'QUARANTINED' }).Count
        & $check 'review: nothing trusted before signing' ($cat.Tools.Count -gt 0 -and $q -eq $cat.Tools.Count) "$q of $($cat.Tools.Count) tools QUARANTINED"
        $r = Invoke-Mcp $e.Port $tokens.reader 'tools/call' $first.tool $first.args
        # A governed denial, not a transport failure: HTTP 200 with an Aegis decision naming why.
        & $check 'review: an unreviewed tool cannot be called' ($r.Status -eq 200 -and $r.Outcome -and $r.Outcome -ne 'ALLOWED' -and @('CATALOG_INTEGRITY', 'NOT_EXPOSED') -contains $r.Code) "$($first.tool) -> HTTP $($r.Status) $($r.Outcome) $($r.Code)"
        Stop-BgProcess $gw; $gw = $null

        # 2. Sign offline with the gateway jar, from the checked-in aegis.yaml and the reviewed reply.
        $c1 = Invoke-AegisCli $e @('--manifest-keygen', (Join-Path $keys.FullName 'review.key'), (Join-Path $run 'review.pub')) $logs 'keygen-review'
        $c2 = Invoke-AegisCli $e @('--server-catalog-create', $e.Config, (Join-Path $run 'catalog.json'), (Join-Path $run 'servers.json')) $logs 'server-catalog-create'
        $c3 = Invoke-AegisCli $e @('--manifest-sign', (Join-Path $run 'servers.json'), (Join-Path $keys.FullName 'review.key')) $logs 'server-catalog-sign'
        $c4 = Invoke-AegisCli $e @('--server-catalog-verify', (Join-Path $run 'servers.json'), (Join-Path $run 'review.pub'), $e.Gateway) $logs 'server-catalog-verify'
        $n = $e.Sources.Count
        & $check 'sign: keygen, create, sign, verify' ($c1.ExitCode -eq 0 -and $c2.ExitCode -eq 0 -and $c3.ExitCode -eq 0 -and $c4.Output -match "OK servers=$n active=$n") (([string]$c4.Output).Trim())

        # 3. Serve the checked-in aegis.yaml itself: exactly the reviewed tools are active and govern real calls.
        $gw = Start-AegisMcp $e $e.Config $logs 'signed' $envv
        $cat2 = Get-AegisCatalog $e $tokens.approver
        $active = @($cat2.Tools | Where-Object { $_.status -eq 'ACTIVE' })
        $same = (($cat.Tools | ForEach-Object { $_.name } | Sort-Object) -join ',') -eq (($active | ForEach-Object { $_.name } | Sort-Object) -join ',')
        & $check 'aegis.yaml: every reviewed tool ACTIVE, nothing else' ($same -and $active.Count -eq $cat2.Tools.Count) "$($active.Count) ACTIVE"
        $list = Invoke-Mcp $e.Port $tokens.reader 'tools/list' $null $null
        $listed = 0
        if ($null -ne $list.Message -and $list.Message.PSObject.Properties['result']) { $listed = @($list.Message.result.tools).Count }
        $activeNames = @($active | ForEach-Object { $_.name })
        $listedNames = @()
        if ($listed -gt 0) { $listedNames = @($list.Message.result.tools | ForEach-Object { $_.name }) }
        $unknown = @($listedNames | Where-Object { $activeNames -notcontains $_ }).Count
        & $check 'aegis.yaml: tools/list for the reader' ($list.Status -eq 200 -and $listed -gt 0 -and $unknown -eq 0) "$listed tools listed, $unknown not in the signed catalog"
        foreach ($read in $reads) {
            $r = Invoke-Mcp $e.Port $tokens.reader 'tools/call' $read.tool $read.args
            & $check "aegis.yaml: real read $($read.tool)" ($r.Outcome -eq 'ALLOWED' -and $r.Text.Contains([string]$read.expect)) "outcome=$($r.Outcome) expected '$($read.expect)'"
        }
        $o = Invoke-Mcp $e.Port $tokens.outsider 'tools/call' $first.tool $first.args
        # The outsider is authenticated but in no allowed group: Aegis must deny with a governance code.
        & $check 'aegis.yaml: outsider refused' ($o.Status -eq 200 -and $o.Outcome -and $o.Outcome -ne 'ALLOWED' -and @('POLICY_DENIED', 'NOT_EXPOSED', 'SCOPE_DENIED', 'READONLY_CALLER') -contains $o.Code) "HTTP $($o.Status) $($o.Outcome) code=$($o.Code)"
        $digest = ($active | Where-Object { $_.name -eq $first.tool } | Select-Object -First 1).digest
        $acc = Invoke-Http 'POST' "http://127.0.0.1:$($e.Port)/aegis/v1/catalog/$($first.tool)/accept" @{ Authorization = "Bearer $($tokens.approver)"; 'Aegis-Definition-Digest' = $digest } ''
        & $check 'aegis.yaml: no run-time acceptance can re-trust a definition' ($acc.Status -eq 409) "POST accept -> HTTP $($acc.Status)"
        Stop-BgProcess $gw; $gw = $null

        # 4. Tamper: one source pointed elsewhere no longer matches its signed digest.
        $t = Start-AegisExpectRefusal $e (New-DerivedConfig $e 'tampered') $logs 'tampered' $envv
        & $check 'tamper: edited source refused at startup' ($t.Exited -and $t.ExitCode -ne 0 -and $t.Error -match 'differs from the configuration reviewed') "exit=$($t.ExitCode)"

        # 5. Break-glass: a separate incident key signs a contain-only order valid for 60 minutes.
        $b1 = Invoke-AegisCli $e @('--manifest-keygen', (Join-Path $keys.FullName 'incident.key'), (Join-Path $run 'incident.pub')) $logs 'keygen-incident'
        $b2 = Invoke-AegisCli $e @('--break-glass-create', (Join-Path $run 'order.json'), $e.Gateway, "INC-$Name", '60', $e.Contain) $logs 'break-glass-create'
        $b3 = Invoke-AegisCli $e @('--manifest-sign', (Join-Path $run 'order.json'), (Join-Path $keys.FullName 'incident.key')) $logs 'break-glass-sign'
        $b4 = Invoke-AegisCli $e @('--break-glass-verify', (Join-Path $run 'order.json'), (Join-Path $run 'incident.pub'), $e.Gateway) $logs 'break-glass-verify'
        & $check 'break-glass: order created, signed, verified' ($b1.ExitCode -eq 0 -and $b2.ExitCode -eq 0 -and $b3.ExitCode -eq 0 -and $b4.Output -match "OK incident=INC-$Name") (([string]$b4.Output).Trim())
        $wk = Start-AegisExpectRefusal $e (New-DerivedConfig $e 'review-key') $logs 'break-glass-review-key' $envv
        & $check 'break-glass: the review key cannot be the incident key' ($wk.Exited -and $wk.ExitCode -ne 0 -and $wk.Error -match 'must be a different key') "exit=$($wk.ExitCode)"
        # An order actually signed with the review key does not verify against the incident key.
        $rk1 = Invoke-AegisCli $e @('--break-glass-create', (Join-Path $run 'order-review-key.json'), $e.Gateway, "INC-$Name-RK", '60', $e.Contain) $logs 'break-glass-create-review-key'
        $rk2 = Invoke-AegisCli $e @('--manifest-sign', (Join-Path $run 'order-review-key.json'), (Join-Path $keys.FullName 'review.key')) $logs 'break-glass-sign-review-key'
        $rk3 = Invoke-AegisCli $e @('--break-glass-verify', (Join-Path $run 'order-review-key.json'), (Join-Path $run 'incident.pub'), $e.Gateway) $logs 'break-glass-verify-review-key'
        & $check 'break-glass: an order signed with the review key is refused' ($rk1.ExitCode -eq 0 -and $rk2.ExitCode -eq 0 -and $rk3.ExitCode -ne 0 -and [string]$rk3.Output -match 'BROKEN') ("exit=$($rk3.ExitCode) " + ([string]$rk3.Output).Trim())
        $bgConfig = New-DerivedConfig $e 'break-glass'
        $contained = $e.Contain.Split('=')[0]
        if ($e.Sources.Count -eq 1) {
            $b = Start-AegisExpectRefusal $e $bgConfig $logs 'break-glass' $envv
            & $check "break-glass: $($e.Contain) leaves nothing to start, so start-up is refused" ($b.Exited -and $b.ExitCode -ne 0 -and $b.Error -match 'no source that the signed server catalog lets start') "exit=$($b.ExitCode)"
        } else {
            $gw = Start-AegisMcp $e $bgConfig $logs 'break-glass' $envv
            $cat3 = Get-AegisCatalog $e $tokens.approver
            $fromContained = @($cat3.Tools | Where-Object { $_.source -eq $contained }).Count
            & $check "break-glass: no $contained tool listed" ($fromContained -eq 0 -and $cat3.Tools.Count -gt 0) "$($cat3.Tools.Count) tools remain, $fromContained from $contained"
            foreach ($read in $reads) {
                if ($read.source -eq $contained) {
                    $r = Invoke-Mcp $e.Port $tokens.operator 'tools/call' $read.tool $read.args
                    & $check "break-glass: $($read.tool) is NOT_EXPOSED" ($r.Status -eq 200 -and $r.Outcome -and $r.Outcome -ne 'ALLOWED' -and $r.Code -eq 'NOT_EXPOSED') "code=$($r.Code)"
                } else {
                    $r = Invoke-Mcp $e.Port $tokens.reader 'tools/call' $read.tool $read.args
                    & $check "break-glass: $($read.tool) still governs real reads" ($r.Outcome -eq 'ALLOWED' -and $r.Text.Contains([string]$read.expect)) "outcome=$($r.Outcome)"
                }
            }
            if ($Name -eq 'spark') {
                $state = Get-SparkDriverState $started.Facts.driver
                & $check 'break-glass: the waiting driver is untouched' ($state -eq 'SUBMITTED') "driverState=$state"
            }
            if ($Name -eq 'kafka') {
                $conns = Get-KafkaConnectors
                & $check 'break-glass: the real connector is untouched' ($conns -contains $started.Facts.connector) "connectors=$($conns -join ',')"
            }
            Stop-BgProcess $gw; $gw = $null
        }

        # 6. The audit journal every instance appended to is intact.
        $va = Invoke-AegisCli $e @('--verify-audit', (Join-Path $run "audit\$($e.Gateway).jsonl")) $logs 'verify-audit'
        & $check 'audit: hash chain verifies' ($va.ExitCode -eq 0 -and $va.Output -match 'OK records=') (([string]$va.Output).Trim())
    } catch {
        & $check 'unexpected error' $false ($_.Exception.Message)
    } finally {
        Stop-BgProcess $gw
        Stop-BgEngine $started
    }

    $failed = @($checks | Where-Object { -not $_.Ok }).Count
    $md = "# $Name MCP server: end-to-end result`n`nRun $(Get-Date -Format s): $($checks.Count - $failed) of $($checks.Count) checks passed. The server is [``../aegis.yaml``](../aegis.yaml); everything else here was generated by the run. Private keys and tokens are in ``keys\``: never copy them to a gateway host.`n`n| Result | Step | Detail |`n| --- | --- | --- |`n"
    foreach ($c in $checks) { $res = 'FAIL'; if ($c.Ok) { $res = 'PASS' }; $md += "| $res | $($c.Step) | $($c.Detail -replace '\|', '/') |`n" }
    Set-Text (Join-Path $run 'RESULT.md') $md
    Write-Host "$Name : $($checks.Count - $failed) of $($checks.Count) checks passed ($(Join-Path $run 'RESULT.md'))"
    return $failed
}

<#
 Starts the real engine and this server's MCP server and leaves both running. 'signed' serves the
 checked-in aegis.yaml as is; 'break-glass' serves it with the breakGlass block enabled.
#>
function Start-BgMcpServer([string]$Name, [string]$Mode) {
    $e = Get-BgEngine $Name
    Assert-BgPrerequisites @($Name)
    $tokensFile = Join-Path $e.Run 'keys\tokens.json'
    if (-not (Test-Path (Join-Path $e.Run 'servers.json')) -or -not (Test-Path $tokensFile)) {
        throw "this server has no signed catalog yet: run .\Run-EndToEnd.ps1 in $($e.Dir) first"
    }
    $config = $e.Config
    if ($Mode -eq 'break-glass') {
        if (-not (Test-Path (Join-Path $e.Run 'order.json'))) { throw 'no break-glass order yet: run .\Run-EndToEnd.ps1 first' }
        $config = New-DerivedConfig $e 'break-glass'
    }
    $t = Get-Content -Raw $tokensFile | ConvertFrom-Json
    $tokens = @{ reader = $t.reader; operator = $t.operator; approver = $t.approver; outsider = $t.outsider }
    $started = Start-BgEngine $e $e.Run
    try {
        $gw = Start-AegisMcp $e $config (Join-Path $e.Run 'logs') "live-$Mode" (Get-GatewayEnv $e $tokens)
    } catch {
        Stop-BgEngine $started
        throw
    }
    $cat = Get-AegisCatalog $e $tokens.approver
    Write-Host ""
    Write-Host "$Name MCP server ($Mode) is running, serving $config"
    Write-Host "  MCP endpoint : http://127.0.0.1:$($e.Port)/mcp   (Streamable HTTP, bearer token)"
    Write-Host "  Admin API    : http://127.0.0.1:$($e.Port)/aegis/v1/catalog   (approver token)"
    Write-Host "  Engine facts : $(($started.Facts.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ' ')"
    Write-Host "  Active tools : $(@($cat.Tools | Where-Object { $_.status -eq 'ACTIVE' }).Count) ($((@($cat.Tools | ForEach-Object { $_.name }) | Sort-Object) -join ', '))"
    Write-Host "  Tokens       : $tokensFile  (reader, operator, approver, outsider)"
    Write-Host ""
    Write-Host "MCP client entry (Kiro: .kiro\settings\mcp.json, under mcpServers); put the reader token from the tokens file in it:"
    Write-Host ("  `"aegis-$Name`": {`"url`": `"http://127.0.0.1:$($e.Port)/mcp`", `"headers`": {`"Authorization`": `"Bearer <reader token>`"}}")
    Write-Host ""
    Write-Host "Stop it with: .\Stop-McpServer.ps1"
}

# Stops the java processes this server's scripts started (recorded in run\pids.txt), and nothing else.
function Stop-BgMcpServer([string]$Name) {
    $e = Get-BgEngine $Name
    $pids = Join-Path $e.Run 'pids.txt'
    if (-not (Test-Path $pids)) { Write-Host "$Name : nothing running"; return }
    foreach ($line in Get-Content $pids) {
        $parts = $line.Split(' ', 2)
        $p = Get-CimInstance Win32_Process -Filter "ProcessId=$([int]$parts[0])" -ErrorAction SilentlyContinue
        # A recorded id is stopped only while it is still a java process launched from this repository.
        if ($null -ne $p -and $p.Name -eq 'java.exe' -and [string]$p.CommandLine -like "*$($script:Repo)*") {
            Stop-Process -Id $p.ProcessId -Force
            Write-Host "$Name : stopped $($parts[1]) (pid $($p.ProcessId))"
        }
    }
    Remove-Item $pids
}

Export-ModuleMember -Function *
