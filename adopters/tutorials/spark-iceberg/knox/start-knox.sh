#!/usr/bin/env bash
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
# Knox flavor: starts Knox's demo LDAP in the background and the gateway in the foreground on
# https://localhost:8443, with Spark Connect's Java 17 options. Needs AEGIS_SIGNING_KEY.
#   ./start-knox.sh /opt/knox/knox-3.0.0
set -euo pipefail
knox="${1:-${AEGIS_KNOX_HOME:-}}"
[ -f "$knox/bin/gateway.jar" ] || { echo "usage: $0 <Knox 3.0.0 directory>" >&2; exit 2; }
: "${AEGIS_SIGNING_KEY:?export AEGIS_SIGNING_KEY first (tutorial step 3)}"
cd "$knox"
if ! (exec 3<>/dev/tcp/127.0.0.1/33389) 2>/dev/null; then
  nohup java -jar bin/ldap.jar conf > logs/aegis-tutorial-ldap.out 2>&1 &
  echo 'Started Knox demo LDAP on ldap://localhost:33389'
fi
opens=""
for p in java.lang java.lang.invoke java.lang.reflect java.io java.net java.nio java.util java.util.concurrent \
         java.util.concurrent.atomic sun.nio.ch sun.nio.cs sun.security.action sun.util.calendar; do
  opens="$opens --add-opens=java.base/$p=ALL-UNNAMED"
done
echo 'Starting Knox on https://localhost:8443 (Ctrl+C to stop)'
# shellcheck disable=SC2086
exec java $opens -Djdk.reflect.useDirectMethodHandle=false -Dio.netty.tryReflectionSetAccessible=true -jar bin/gateway.jar
