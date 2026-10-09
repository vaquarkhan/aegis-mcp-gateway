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
# Starts a single-node Apache Flink session cluster on Windows, REST API on 127.0.0.1:18081.
#
# The Flink 1.20 binary distribution ships no .bat or .cmd scripts at all (bin/ is shell only), so
# this script starts the two JVMs directly. Two things the shell scripts would have done for us have
# to be done here by hand:
#   1. taskmanager.resource-id is set explicitly. Left to Flink it defaults to "<host>:<port>-<hash>",
#      and the TaskManager then tries to create a working directory with a colon in its name, which
#      Windows refuses ("Could not create the working directory ... tm_localhost:64616-442b34").
#   2. The whole TaskManager memory spec is passed on the command line. bin/taskmanager.sh derives it
#      with a Java helper before launching; without it the TaskManager exits with
#      "The required configuration option taskmanager.cpu.cores is not set".
#
#   $env:FLINK_HOME = 'C:\path\to\flink-1.20.1'
#   .\start-flink.ps1
# Stop it again with .\stop-flink.ps1
$ErrorActionPreference = 'Stop'
if (-not $env:FLINK_HOME) { Write-Error 'Set $env:FLINK_HOME to an Apache Flink 1.20+ binary distribution (see the tutorial, step 4.2)' }
$dist = (Resolve-Path $env:FLINK_HOME).Path
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$work = Join-Path $here 'work'
foreach ($d in 'conf', 'log', 'aegis-savepoints', 'checkpoints', 'jars', 'tmp', 'audit') {
    New-Item -ItemType Directory -Force -Path (Join-Path $work $d) | Out-Null
}
$u = $work.Replace('\', '/')
$conf = Join-Path $work 'conf\config.yaml'
if (-not (Test-Path $conf)) {
    @"
jobmanager:
  bind-host: localhost
  rpc:
    address: localhost
    port: 6123
  memory:
    process:
      size: 1600m
  execution:
    failover-strategy: region
taskmanager:
  bind-host: localhost
  host: localhost
  numberOfTaskSlots: 4
parallelism:
  default: 1
rest:
  address: localhost
  bind-address: localhost
  port: 18081
execution:
  checkpointing:
    interval: 10s
state:
  backend:
    type: hashmap
  checkpoints:
    dir: file:///$u/checkpoints
  savepoints:
    dir: file:///$u/aegis-savepoints
web:
  upload:
    dir: $u/jars
io:
  tmp:
    dirs: $u/tmp
"@ | Set-Content -Encoding utf8 $conf
    Write-Host "Wrote $conf (REST on 127.0.0.1:18081, 4 slots, checkpoints every 10s)"
}

# Java 17 needs these; in the distribution they live in conf/config.yaml under env.java.opts.all and
# are applied by the shell scripts, which we are not using.
$open = @(
    '--add-exports=java.base/sun.net.util=ALL-UNNAMED',
    '--add-exports=java.rmi/sun.rmi.registry=ALL-UNNAMED',
    '--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED',
    '--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED',
    '--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED',
    '--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED',
    '--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED',
    '--add-exports=java.security.jgss/sun.security.krb5=ALL-UNNAMED',
    '--add-opens=java.base/java.lang=ALL-UNNAMED',
    '--add-opens=java.base/java.net=ALL-UNNAMED',
    '--add-opens=java.base/java.io=ALL-UNNAMED',
    '--add-opens=java.base/java.nio=ALL-UNNAMED',
    '--add-opens=java.base/sun.nio.ch=ALL-UNNAMED',
    '--add-opens=java.base/java.lang.reflect=ALL-UNNAMED',
    '--add-opens=java.base/java.text=ALL-UNNAMED',
    '--add-opens=java.base/java.time=ALL-UNNAMED',
    '--add-opens=java.base/java.util=ALL-UNNAMED',
    '--add-opens=java.base/java.util.concurrent=ALL-UNNAMED',
    '--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED',
    '--add-opens=java.base/java.util.concurrent.locks=ALL-UNNAMED')
$cp = Join-Path $dist 'lib\*'
$log4j = 'file:' + (Join-Path $dist 'conf\log4j.properties')

$jm = $open + @("-Xmx1024m", "-Dlog.file=$work\log\jobmanager.log", "-Dlog4j.configurationFile=$log4j",
    '-cp', $cp, 'org.apache.flink.runtime.entrypoint.StandaloneSessionClusterEntrypoint',
    '--configDir', "$work\conf")
Start-Process -FilePath 'java' -ArgumentList $jm -WindowStyle Minimized
Write-Host 'JobManager starting ...'

# A memory spec that adds up: framework heap 128 + task heap 384 + framework off-heap 128 + network 64
# + managed 128 = 832m of Flink memory, plus 256m metaspace and 192m overhead.
$tm = $open + @('-Xms512m', '-Xmx512m', '-XX:MaxDirectMemorySize=192m', '-XX:MaxMetaspaceSize=256m',
    "-Dlog.file=$work\log\taskmanager.log", "-Dlog4j.configurationFile=$log4j",
    '-cp', $cp, 'org.apache.flink.runtime.taskexecutor.TaskManagerRunner',
    '--configDir', "$work\conf",
    '-D', 'taskmanager.resource-id=tm-1',
    '-D', 'taskmanager.cpu.cores=4.0',
    '-D', 'taskmanager.memory.framework.heap.size=128m',
    '-D', 'taskmanager.memory.task.heap.size=384m',
    '-D', 'taskmanager.memory.framework.off-heap.size=128m',
    '-D', 'taskmanager.memory.task.off-heap.size=0m',
    '-D', 'taskmanager.memory.network.min=64m',
    '-D', 'taskmanager.memory.network.max=64m',
    '-D', 'taskmanager.memory.managed.size=128m',
    '-D', 'taskmanager.memory.jvm-metaspace.size=256m',
    '-D', 'taskmanager.memory.jvm-overhead.min=192m',
    '-D', 'taskmanager.memory.jvm-overhead.max=192m')
Start-Sleep -Seconds 12
Start-Process -FilePath 'java' -ArgumentList $tm -WindowStyle Minimized
Write-Host 'TaskManager starting ...'

Write-Host 'Waiting for one TaskManager to register with the JobManager ...'
for ($i = 0; $i -lt 90; $i++) {
    Start-Sleep -Seconds 2
    try {
        $o = Invoke-RestMethod -Uri http://127.0.0.1:18081/overview -TimeoutSec 5
        if ($o.taskmanagers -ge 1) {
            Write-Host ("Flink {0} is up: {1} TaskManager, {2} slots." -f $o.'flink-version', $o.taskmanagers, $o.'slots-total')
            exit 0
        }
    } catch { }
}
Write-Error "The cluster did not come up; look in $work\log"
