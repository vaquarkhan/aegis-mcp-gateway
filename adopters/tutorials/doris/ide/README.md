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
# IDE setup for the Apache Doris tutorial

## What this folder is (new-user version)

Your coding editor's **agent** can call remote tools over MCP. These JSON files tell the editor to call
**Aegis**, not the Apache Doris engine directly. Aegis checks who you are and whether the action is allowed,
then talks to the engine only when the call is allowed.

Think of it as a receptionist: the agent asks Aegis; Aegis decides; the backend never sees a skipped
governance check.

You still need:

1. The real Apache Doris stack running (see the tutorial).
2. Aegis running with that tutorial's configuration on port `8080` (own host) or Knox on
   `8443` (Knox door).
3. A bearer token (own host) or Knox demo credentials (Knox door) from the tutorial.

Full walkthrough: [`docs/tutorials/doris.md`](../../../../docs/tutorials/doris.md). Folder overview:
[`adopters/tutorials/README.md`](../../README.md).

## Which file to copy

| Editor | Copy this file to | Door |
| --- | --- | --- |
| VS Code (Copilot agent) | `.vscode/mcp.json` | own host → `http://127.0.0.1:8080/mcp` |
| VS Code (Knox) | `.vscode/mcp.json` (from `mcp.knox.json`) | Knox → `https://localhost:8443/gateway/aegis-doris/aegis-mcp/v1/mcp` |
| Kiro | `.kiro/settings/mcp.json` | own host enabled; Knox entry present but disabled |
| Cursor | `.cursor/mcp.json` | own host → `http://127.0.0.1:8080/mcp` |

Server name in the files: `aegis-doris` (Knox variant: `aegis-doris-knox`).

## First-time steps

Do these in order. The agent is not using Aegis until the last step is true.

1. Start the real engine, then start the own-host gateway and leave it listening on `http://127.0.0.1:8080/mcp`.
   The section 3 runner is a test: it stops what it started, and `-Keep` overwrites the retained evidence.
2. Copy the file into the project the editor has open:
   Cursor → `.cursor/mcp.json` from `cursor/mcp.json` in this folder.
   Kiro → `.kiro/settings/mcp.json` from `kiro/mcp.json` in this folder.
   Leave `aegis-doris-knox` disabled unless Knox is the door you started.
3. Replace `REPLACE_WITH_YOUR_TOKEN` with the same value you exported as `AEGIS_VALIDATION_TOKEN_READER`
   in the terminal that started Aegis. That token is the person `guest`. The name of the variable is in
   `examples/adopters/doris/aegis/own-host.yaml`; the file does not contain the secret.
   VS Code asks for the token instead of storing it in the JSON.
4. Reload MCP in Cursor or Kiro.

You are ready to give the agent an instruction when both of these are true:

- the gateway log still says `Aegis gateway listening on http://127.0.0.1:8080/mcp`
- the editor lists `aegis-doris` and shows its tools

Then ask a read prompt from the tutorial and name `aegis-doris`. The agent calls Aegis. It does not call the
engine URL. A write or delete still needs approval when the profile marks the tool `MUTATE` or
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
