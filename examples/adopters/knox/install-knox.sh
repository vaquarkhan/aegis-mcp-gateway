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
# Downloads the official Apache Knox 3.0.0 binary distribution, checks its published SHA-512, and
# unpacks it. Point AEGIS_KNOX_HOME at the printed directory to run the Knox front door.
#   ./install-knox.sh /opt/knox
set -euo pipefail
dest="${1:?usage: install-knox.sh <destination>}"
mkdir -p "$dest"
cd "$dest"
curl -fsSL --retry 3 -o knox-3.0.0.zip https://dlcdn.apache.org/knox/3.0.0/knox-3.0.0.zip \
  || curl -fsSL --retry 3 -o knox-3.0.0.zip https://archive.apache.org/dist/knox/3.0.0/knox-3.0.0.zip
curl -fsSL -o knox-3.0.0.zip.sha512 https://downloads.apache.org/knox/3.0.0/knox-3.0.0.zip.sha512 \
  || curl -fsSL -o knox-3.0.0.zip.sha512 https://archive.apache.org/dist/knox/3.0.0/knox-3.0.0.zip.sha512
expected="$(cut -d' ' -f1 knox-3.0.0.zip.sha512 | tr 'A-F' 'a-f')"
actual="$(sha512sum knox-3.0.0.zip | cut -d' ' -f1)"
[ "$expected" = "$actual" ] || { echo "SHA-512 mismatch" >&2; exit 1; }
unzip -q -o knox-3.0.0.zip
echo "SHA-512 verified. Set: export AEGIS_KNOX_HOME=$dest/knox-3.0.0"
