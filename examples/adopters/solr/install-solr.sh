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
# Downloads the official Apache Solr 9.10.1 slim binary distribution, checks its published SHA-512, and
# unpacks it. Point AEGIS_SOLR_HOME at the printed directory to run the example.
#   ./install-solr.sh /opt/solr
set -euo pipefail
dest="${1:?usage: install-solr.sh <destination>}"
version=9.10.1
name="solr-$version-slim.tgz"
mkdir -p "$dest"
cd "$dest"
curl -fsSL --retry 3 -o "$name" "https://dlcdn.apache.org/solr/solr/$version/$name" \
  || curl -fsSL --retry 3 -o "$name" "https://archive.apache.org/dist/solr/solr/$version/$name"
curl -fsSL -o "$name.sha512" "https://downloads.apache.org/solr/solr/$version/$name.sha512" \
  || curl -fsSL -o "$name.sha512" "https://archive.apache.org/dist/solr/solr/$version/$name.sha512"
expected="$(cut -d' ' -f1 "$name.sha512" | tr 'A-F' 'a-f')"
actual="$(sha512sum "$name" | cut -d' ' -f1)"
[ "$expected" = "$actual" ] || { echo "SHA-512 mismatch" >&2; exit 1; }
tar -xzf "$name"
echo "SHA-512 verified. Set: export AEGIS_SOLR_HOME=$dest/solr-$version-slim"
