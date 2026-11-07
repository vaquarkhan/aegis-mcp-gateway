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
# Creates the tutorial's tiny dataset through Spark Connect, outside Aegis (SeedLake.java).
# Needs: mvn -B -Preal-engines -DskipTests -pl :aegis-spark-connect install  (writes plugin.classpath)
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = (Resolve-Path (Join-Path $here '..\..\..')).Path
$cpFile = Join-Path $repo 'examples\aegis-spark-connect\target\plugin.classpath'
if (-not (Test-Path $cpFile)) {
    Write-Error "Missing $cpFile. Run: mvn -B -Preal-engines -DskipTests -pl :aegis-spark-connect install"
}
$cp = (Get-Content -Raw $cpFile).Trim()
# The Spark Connect client uses Apache Arrow, which needs these opens on Java 17+.
$ErrorActionPreference = 'Continue'
& java --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED `
    --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED `
    -cp $cp (Join-Path $here 'SeedLake.java') 'sc://127.0.0.1:15002' 2>&1 | Where-Object { "$_" -notmatch 'WARN|SLF4J' }
exit $LASTEXITCODE
