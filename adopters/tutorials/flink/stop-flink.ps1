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
# Stops the two Flink JVMs started by start-flink.ps1. It matches on the Flink entrypoint class
# names, so it does not touch the gateway JVM or any other Java process.
$ErrorActionPreference = 'Stop'
$classes = 'StandaloneSessionClusterEntrypoint', 'TaskManagerRunner'
$stopped = 0
foreach ($p in Get-CimInstance Win32_Process -Filter "Name = 'java.exe'") {
    foreach ($c in $classes) {
        if ($p.CommandLine -and $p.CommandLine.Contains($c)) {
            Write-Host ("Stopping pid {0} ({1})" -f $p.ProcessId, $c)
            Stop-Process -Id $p.ProcessId -Force
            $stopped++
            break
        }
    }
}
if ($stopped -eq 0) { Write-Host 'No Flink JVM was running.' }
