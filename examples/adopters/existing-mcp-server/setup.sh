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
# Installs the two downstream MCP servers this example fronts, at pinned versions, outside the repository,
# and prints the environment variables the test reads.
#   ./setup.sh /opt/aegis-engines
set -euo pipefail
dest="${1:?usage: setup.sh <destination>}"
mkdir -p "$dest"
python3 -m venv "$dest/py-mcp"
"$dest/py-mcp/bin/python" -m pip install --disable-pip-version-check 'mcp==2.2.0'
mkdir -p "$dest/mcp-filesystem"
(cd "$dest/mcp-filesystem" && npm install --no-fund --no-audit --save-exact '@modelcontextprotocol/server-filesystem@2026.8.31')
echo "Set:"
echo "  export AEGIS_MCP_PYTHON=$dest/py-mcp/bin/python"
echo "  export AEGIS_NODE=$(command -v node)"
echo "  export AEGIS_MCP_FILESYSTEM_JS=$dest/mcp-filesystem/node_modules/@modelcontextprotocol/server-filesystem/dist/index.js"
