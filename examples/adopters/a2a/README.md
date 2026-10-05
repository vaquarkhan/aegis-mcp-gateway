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
# A2A agent example: with Knox and without Knox

This example runs the shipped profile
[`adopters/configurations/a2a/a2a.yaml`](../../../adopters/configurations/a2a/a2a.yaml) against an A2A 1.0
agent served by the official Python `a2a-sdk` 1.2.1, through Aegis's own server and inside a stock
Apache Knox 3.0.0 gateway. Only the card URL and the egress host are substituted.

## Files in this folder

| File | What it is |
| --- | --- |
| [`inventory_agent.py`](inventory_agent.py) | the agent: an in-memory stock table, `stock SKU-n` and `reserve N SKU-n` messages, a bearer check on its JSON-RPC route, and a journal of every message it receives |
| [`setup.ps1`](setup.ps1) | installs `a2a-sdk[http-server]==1.2.1` and `uvicorn` in a virtual environment outside the repository |
| [`setup.sh`](setup.sh) | the same, for Linux and macOS |
| [`A2aAgentFrontDoorsTest.java`](../../aegis-validation/src/test/java/io/github/vaquarkhan/aegis/validation/A2aAgentFrontDoorsTest.java) | the test: how the agent is started, and every assertion |

## How to run

```powershell
./setup.ps1 -Destination C:\aegis-engines
$env:AEGIS_A2A_PYTHON = 'C:\aegis-engines\py-a2a\Scripts\python.exe'
$env:AEGIS_KNOX_HOME = 'C:\aegis-engines\knox-3.0.0'
mvn -B -Preal-engines install -DskipTests
mvn -B -Preal-engines -pl examples/aegis-validation verify -Dtest=A2aAgentFrontDoorsTest
```

Without `AEGIS_A2A_PYTHON` the test is skipped, and a skipped test is not a pass.

## What the run shows, for each front door

- A message carrying an injection phrase is refused with `PROMPT_INJECTION`, and the agent journal
  shows it never arrived.
- The agent is classified `MUTATE` (it can reserve stock). A reader gets `READONLY_CALLER`; an operator
  without an approval gets `APPROVAL_REQUIRED`; the dry run opens the approval request and sends
  nothing; the operator cannot approve their own request; an approver can.
- The one approved call reaches the agent, which accepts the bearer token from the source's
  `credentials` section: the journal shows exactly one reservation. Replaying the approval is refused.
