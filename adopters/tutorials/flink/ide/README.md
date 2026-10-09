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
# IDE setup for the Apache Flink tutorial

## What this folder is (new-user version)

Your coding editor's **agent** can call remote tools over MCP. These JSON files tell the editor to call
**Aegis**, not the Apache Flink engine directly. Aegis checks who you are and whether the action is allowed,
then talks to the engine only when the call is allowed.

Think of it as a receptionist: the agent asks Aegis; Aegis decides; the backend never sees a skipped
governance check.

You still need:

1. The real Apache Flink stack running (see the tutorial).
2. Aegis running with that tutorial's configuration on port `8080` (own host) or Knox on
   `8443` (Knox door).
3. A bearer token (own host) or Knox demo credentials (Knox door) from the tutorial.

Full walkthrough: [`docs/tutorials/flink.md`](../../../../docs/tutorials/flink.md). Folder overview:
[`adopters/tutorials/README.md`](../../README.md).

## Which file to copy

| Editor | Copy this file to | Door |
| --- | --- | --- |
| VS Code (Copilot agent) | `.vscode/mcp.json` | own host → `http://127.0.0.1:8080/mcp` |
| VS Code (Knox) | `.vscode/mcp.json` (from `mcp.knox.json`) | Knox → `https://localhost:8443/gateway/aegis-flink/aegis-mcp/v1/mcp` |
| Kiro | `.kiro/settings/mcp.json` | own host enabled; Knox entry present but disabled |
| Cursor | `.cursor/mcp.json` | own host → `http://127.0.0.1:8080/mcp` |

Server name in the files: `aegis-flink` (Knox variant: `aegis-flink-knox`).

## First-time steps

1. Finish the "Run the whole verified scenario" (or Flavor A / Flavor B) section of the tutorial so
   Aegis is listening.
2. Copy the matching JSON above into your project or user MCP settings path.
3. Replace `REPLACE_WITH_YOUR_TOKEN` (Cursor / Kiro) with a token from
   `examples/adopters/flink/aegis/own-host.yaml`, or answer VS Code's password prompt.
4. Reload MCP / the window, then ask the agent a **read** prompt from the tutorial (for example list
   or status). A write or delete should require approval when the profile marks the tool `MUTATE` or
   `DESTRUCTIVE`.

## What the fields mean

| Field | Meaning |
| --- | --- |
| `url` | MCP endpoint. Must be Aegis (or Knox → Aegis), never the raw engine admin URL. |
| `Authorization: Bearer …` | Own-host identity. The token maps to groups in Aegis config. |
| `Authorization: Basic …` | Knox demo identity. Knox authenticates; Aegis still governs the MCP body. |
| `disabled` (Kiro) | `true` means that server entry is ignored until you enable it. |

## Safety

- Do not commit real tokens or Knox passwords.
- Do not set the IDE to auto-approve destructive tools while learning.
- If the IDE shows no tools, the usual causes are: Aegis not running, wrong port, wrong token, or a
  token whose groups see an empty catalog.
