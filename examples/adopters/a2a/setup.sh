#!/usr/bin/env sh
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
# Installs the official A2A Python SDK 1.2.1 with its HTTP server extras in a virtual environment
# outside the repository, and prints the AEGIS_A2A_PYTHON variable the example test reads.
# Usage: ./setup.sh /opt/aegis-engines
set -eu
dest="${1:?usage: setup.sh <destination>}"
mkdir -p "$dest"
python3 -m venv "$dest/py-a2a"
"$dest/py-a2a/bin/python" -m pip install --disable-pip-version-check 'a2a-sdk[http-server]==1.2.1' 'uvicorn==0.54.0'
echo "export AEGIS_A2A_PYTHON=$dest/py-a2a/bin/python"
