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
# Installs Apache Burr 0.42.0 with its tracking server in a virtual environment outside the repository.
# setuptools is pinned because the 0.42.0 /api/v0/version route imports pkg_resources.
#   ./setup.sh /opt/aegis-engines
set -euo pipefail
dest="${1:?usage: setup.sh <destination>}"
mkdir -p "$dest"
python3 -m venv "$dest/py-burr"
"$dest/py-burr/bin/python" -m pip install --disable-pip-version-check 'burr[tracking]==0.42.0' 'setuptools==80.9.0'
echo "Set: export AEGIS_BURR_PYTHON=$dest/py-burr/bin/python"
