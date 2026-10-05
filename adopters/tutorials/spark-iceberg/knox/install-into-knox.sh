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
# Knox flavor: installs Aegis into a stock Apache Knox 3.0.0 (see install-into-knox.ps1 for the steps).
#   ./install-into-knox.sh /opt/knox/knox-3.0.0
set -euo pipefail
knox="${1:-${AEGIS_KNOX_HOME:-}}"
[ -f "$knox/bin/gateway.jar" ] || { echo "usage: $0 <Knox 3.0.0 directory>" >&2; exit 2; }
knox="$(cd "$knox" && pwd)"
here="$(cd "$(dirname "$0")" && pwd)"
tutorial="$(dirname "$here")"
repo="$(cd "$tutorial/../../.." && pwd)"
bundle="$repo/aegis-knox3-extension/target/aegis-knox3-extension-2.0.0-SNAPSHOT-knox-bundle.jar"
plugin="$repo/examples/aegis-spark-connect/target/aegis-spark-connect-2.0.0-SNAPSHOT.jar"
plugin_cp="$repo/examples/aegis-spark-connect/target/plugin.classpath"
for f in "$bundle" "$plugin" "$plugin_cp"; do
  [ -f "$f" ] || { echo "Missing $f. Run: mvn -B -DskipTests install; mvn -B -Preal-engines -DskipTests -pl :aegis-spark-connect install" >&2; exit 1; }
done
rm -f "$knox"/ext/aegis-*
cp "$bundle" "$knox/ext/"
artifact() { basename "$1" .jar | sed -E 's/-[0-9][A-Za-z0-9.\-]*$//'; }
shipped="$(for j in "$knox"/lib/*.jar "$knox"/dep/*.jar; do [ -f "$j" ] && artifact "$j"; done | sort -u)"
for j in "$plugin" $(tr ':' ' ' < "$plugin_cp"); do
  name="$(basename "$j")"
  case "$name" in log4j*|slf4j*|logback*|*-to-slf4j*|*-over-slf4j*) echo "SKIP $name (logging)"; continue ;; esac
  if grep -qx "$(artifact "$j")" <<< "$shipped"; then echo "KEEP $name"; continue; fi
  cp "$j" "$knox/ext/aegis-plugin-$name"; echo "ADD  $name"
done
mkdir -p "$knox/conf/aegis"
cp "$tutorial/aegis-spark-iceberg.yaml" "$knox/conf/aegis/aegis-spark-iceberg.yaml"
sed "s#@KNOX_HOME@#$knox#g" "$here/aegis-spark-iceberg.xml" > "$knox/conf/topologies/aegis-spark-iceberg.xml"
[ -f "$knox/data/security/master" ] || java -jar "$knox/bin/knoxcli.jar" create-master --master tutorial-master-secret-change-me
java -jar "$knox/bin/knoxcli.jar" create-cert --hostname localhost
echo "Installed. Start Knox with ./start-knox.sh $knox"
