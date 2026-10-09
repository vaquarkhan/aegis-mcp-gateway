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
# IDE setup for Spark and Iceberg

## What this folder is (new-user version)

Your editor's agent can call tools. These files tell it to call **Aegis**, which then talks to Spark
Connect and the Iceberg REST catalog only after governance allows the call.

Full walkthrough: [`docs/TUTORIAL-SPARK-ICEBERG.md`](../../../../docs/TUTORIAL-SPARK-ICEBERG.md).
Folder overview: [`adopters/tutorials/README.md`](../../README.md).

## Which file to copy

| Editor | Copy this file to | Door |
| --- | --- | --- |
| VS Code (Copilot agent) | `.vscode/mcp.json` | own host |
| VS Code (Knox) | `.vscode/mcp.json` (from `mcp.knox.json`) | Knox |
| Kiro | `.kiro/settings/mcp.json` | own host (+ optional Knox entry) |
| Cursor | `.cursor/mcp.json` | own host |
| Claude Desktop (stdio) | see the tutorial's stdio section | local process |

Start the tutorial scripts first so Aegis is listening, then copy the JSON and supply the token the
tutorial prints. Replace placeholders; do not commit real secrets.
