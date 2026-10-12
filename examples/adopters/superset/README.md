<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->
# Apache Superset: both front doors passed

[`aegis/canonical.yaml`](aegis/canonical.yaml) is the shipped profile. [`HOW-TO-RUN.md`](HOW-TO-RUN.md)
and the files beside it are what `SupersetFrontDoorsTest` used. The official Superset 4.0.2 image
answered the profile's chart API through the own host and through Knox. Retained results are in
[`results/`](results/). The IDE tutorial is [docs/tutorials/superset.md](../../../docs/tutorials/superset.md).

## Run it yourself

The files in this folder are the kept run. To leave a gateway listening, copy them; do not start the IDE against the engine, and do not use `-Keep` as the start command. `-Keep` re-runs the test, stops the processes, and overwrites these files.

1. From the repository root: `mvn -B -Preal-engines -DskipTests install`.
2. Start the engine with section 1 of [`HOW-TO-RUN.md`](HOW-TO-RUN.md). Use a host port you choose. The port written in that file is the free port from the kept run.
3. Copy [`aegis/own-host.yaml`](aegis/own-host.yaml) to a file you do not commit. Set `host.listen.port` to `8080` and set the engine address to your port.
4. Set `AEGIS_SIGNING_KEY` (at least 32 bytes) and `AEGIS_VALIDATION_TOKEN_READER`, `AEGIS_VALIDATION_TOKEN_OPERATOR`, `AEGIS_VALIDATION_TOKEN_APPROVER`, and `AEGIS_VALIDATION_TOKEN_OUTSIDER` (each at least 16 characters). Also set `SUPERSET_ACCESS_TOKEN`. The YAML names the variables; it does not contain the secrets.
5. Start Aegis so it stays up. The full command, including the plugin classpath when this profile needs one, is in [`adopters/configurations/adapters/superset/README.md`](../../../adopters/configurations/adapters/superset/README.md).

| Copy | To |
| --- | --- |
| [`aegis/knox-aegis.yaml`](aegis/knox-aegis.yaml) | `$AEGIS_KNOX_HOME/conf/aegis/superset.yaml` |
| [`aegis/knox-topology.xml`](aegis/knox-topology.xml) | `$AEGIS_KNOX_HOME/conf/topologies/aegis-superset.xml` |
| `aegis-knox3-extension/target/aegis-knox3-extension-2.0.0-SNAPSHOT-knox-bundle.jar` | `$AEGIS_KNOX_HOME/ext/` |
| [`../../../adopters/tutorials/superset/ide/cursor/mcp.json`](../../../adopters/tutorials/superset/ide/cursor/mcp.json) | `.cursor/mcp.json` |
| [`../../../adopters/tutorials/superset/ide/vscode/mcp.json`](../../../adopters/tutorials/superset/ide/vscode/mcp.json) | `.vscode/mcp.json` |
| [`../../../adopters/tutorials/superset/ide/vscode/mcp.knox.json`](../../../adopters/tutorials/superset/ide/vscode/mcp.knox.json) | `.vscode/mcp.json` when you use Knox |
| [`../../../adopters/tutorials/superset/ide/kiro/mcp.json`](../../../adopters/tutorials/superset/ide/kiro/mcp.json) | `.kiro/settings/mcp.json` |

Replace `${KNOX_HOME}` in the topology. Replace `REPLACE_WITH_YOUR_TOKEN` with the reader token. Reload MCP after the gateway is listening on `http://127.0.0.1:8080/mcp`. You are ready when the editor lists the server from the JSON you copied and shows its tools. Then name that server in the agent chat. The agent calls Aegis, not the engine URL. Install Knox with [`../knox/install-knox.ps1`](../knox/install-knox.ps1) or [`../knox/install-knox.sh`](../knox/install-knox.sh) before the Knox copies.
