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
# Stops the two processes start-hello.ps1 started (the gateway and the example inventory service).
# It uses the process ids recorded in work\pids.txt, so it never touches any other Java or Python process.
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$pids = Join-Path $here 'work\pids.txt'
if (-not (Test-Path $pids)) { Write-Host 'Nothing to stop (no work\pids.txt).'; return }
foreach ($id in Get-Content $pids) {
    $p = Get-Process -Id ([int]$id) -ErrorAction SilentlyContinue
    # Guard against a recycled pid: only stop the java or python process this folder started.
    if ($p -and $p.ProcessName -match '^(java|python|py)') { Stop-Process -Id $p.Id -Force; Write-Host "Stopped pid $id ($($p.ProcessName))" }
}
Remove-Item $pids -Force
