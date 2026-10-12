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
# Tutorials: what this folder is for

If you are new to Aegis, start here. The HTML walkthrough that ties IDE setup to your development cycle is
[`docs/guide/hello-world.html`](../../docs/guide/hello-world.html).

## Plain-language picture

An **IDE agent** (Cursor, VS Code Copilot, Kiro, and similar) can call tools on a remote server.
Without a gateway, that agent can talk straight to Kafka, Spark, Airflow, and so on with whatever
power the backend credential allows.

**Aegis** sits in the middle. The agent talks only to Aegis. Aegis decides whether each tool call is
allowed (read vs write, who you are, approval, dry-run, egress, audit), then calls the real engine
only when the call is allowed.

Each subdirectory under `adopters/tutorials/<project>/` holds **ready-made IDE connection files** for
one verified project (for example `kafka`, `airflow`, `spark-iceberg`). The step-by-step story for
that project lives in [`docs/tutorials/<project>.md`](../../docs/tutorials/) (or
[`docs/TUTORIAL-SPARK-ICEBERG.md`](../../docs/TUTORIAL-SPARK-ICEBERG.md) for the Spark + Iceberg walk).

## What the `ide/` folder means

Inside every project you will see:

```text
adopters/tutorials/<project>/ide/
  README.md                 ← this project's short "how to plug the IDE in"
  vscode/mcp.json           ← VS Code / Copilot: own-host Aegis (HTTP + bearer token)
  vscode/mcp.knox.json      ← VS Code / Copilot: Aegis behind Apache Knox (HTTP Basic)
  kiro/mcp.json             ← Kiro: own host enabled, Knox entry present but disabled
  cursor/mcp.json           ← Cursor: own-host Aegis
```

Those JSON files are **not** the Aegis product itself. They only tell your IDE:

1. **Where** to send MCP traffic (usually `http://127.0.0.1:8080/mcp`, or a Knox HTTPS URL).
2. **How** to authenticate (a bearer token for own host, or Knox demo Basic auth).

Until Aegis is running on that URL with a matching configuration, the IDE will show an empty or
failing MCP server. Start the gateway first (see the matching tutorial), then copy the JSON into
your editor's MCP settings path.

## Own host vs Knox (two doors, same governance)

| Flavor | What you run | What the IDE file uses | When to use it |
| --- | --- | --- | --- |
| **Own host** | `java -jar …/aegis-gateway-…-all.jar --config …` | Bearer token in `Authorization` | Local learning and most tutorials |
| **Knox** | Stock Apache Knox 3.0 with the Aegis extension | HTTP Basic against Knox's demo LDAP | You already operate Knox as the HTTP perimeter |

Both doors feed the **same** Aegis governance engine. Knox authenticates and admits the route; Aegis
still decides the MCP action.

## How a new user gets from zero to a first prompt

1. Build Aegis: `mvn -B -Preal-engines -DskipTests install` from the repository root.
2. Open the tutorial for your engine in [`docs/TUTORIALS.md`](../../docs/TUTORIALS.md).
3. Start the real engine and Aegis as that tutorial describes, and leave the gateway listening.
   `examples/adopters/run-example.ps1 <project>` is a test: it stops what it started. Adding `-Keep`
   (bash: `--keep`) also overwrites the retained evidence files. It is not the way to start an IDE session.
4. Open `adopters/tutorials/<project>/ide/README.md` and copy the Cursor or Kiro file into the project
   the editor has open (`.cursor/mcp.json` or `.kiro/settings/mcp.json`).
5. Replace `REPLACE_WITH_YOUR_TOKEN` with the same value as `AEGIS_VALIDATION_TOKEN_READER`.
6. Reload MCP. You are ready when the gateway is still listening on `http://127.0.0.1:8080/mcp` and the
   editor lists `aegis-<project>` with its tools.
7. In the agent chat, name that server and ask a prompt from the tutorial. The agent calls Aegis.

## What you should **not** do

- Do not paste production secrets into these JSON files or commit real tokens.
- Do not point the IDE at the backend engine URL and skip Aegis; that bypasses governance.
- Do not treat a contract-only adapter (Atlas, Cassandra, Impala, Paimon) as having a
  tutorial; those profiles have no kept real run and no `ide/` folder here.

## Index of generated tutorials

The Markdown walkthroughs are generated from kept two-door runs by
`tools/build-project-tutorials.py`. See [`docs/tutorials/README.md`](../../docs/tutorials/README.md).
Spark with Iceberg is hand-authored under [`spark-iceberg/`](spark-iceberg/).
