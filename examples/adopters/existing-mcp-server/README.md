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
# Existing MCP server example: with Knox and without Knox

This example runs the shipped profile
[`adopters/configurations/existing-mcp-server/existing-mcp-server.yaml`](../../../adopters/configurations/existing-mcp-server/existing-mcp-server.yaml)
in front of two MCP servers Aegis shares no code with, through Aegis's own server and inside a stock
Apache Knox 3.0.0 gateway. Only the server URL, the stdio command and the egress network are substituted.

Status: **retained historical run**: both front doors with the profile's `era: AUTO`, plus a third run with
`era: LEGACY` for the 2025 handshake. The kept logs are in [results/](results/).

## Files in this folder

| File | What it is |
| --- | --- |
| [`HOW-TO-RUN.md`](HOW-TO-RUN.md) | written by the test harness during the kept run: the exact command line each engine was started with, how each front door was started with the files below, a sample `curl` request, and every request the test sent with its result. Start here to repeat the run by hand |
| [`aegis/canonical.yaml`](aegis/canonical.yaml) | portable exact reference copy of the shipped profile; edit its HTTP URL and stdio command for your deployment |
| [`aegis/own-host.yaml`](aegis/own-host.yaml) | exact captured evidence configuration for the own-host run; its stdio command deliberately contains the Windows paths used by that run, not portable defaults |
| [`aegis/knox-aegis.yaml`](aegis/knox-aegis.yaml) | exact captured evidence configuration loaded inside Knox; it has the same run-specific Windows stdio paths |
| [`aegis/knox-topology.xml`](aegis/knox-topology.xml) | the Knox topology: demo LDAP authentication, group mapping, `AclsAuthz`, and the `AEGIS_MCP` service |
| [`setup.ps1`](setup.ps1) | installs the pinned Python or Node servers this example needs, outside the repository |
| [`setup.sh`](setup.sh) | the same, for Linux and macOS |
| [`engine_mcp_server.py`](engine_mcp_server.py) | the downstream MCP server (official MCP Python SDK) the test starts |
| [`results/`](results/) | the kept transcripts, Aegis audit journals and Surefire report of that run |
| [`ExistingMcpServerFrontDoorsTest.java`](../../aegis-validation/src/test/java/io/github/vaquarkhan/aegis/validation/ExistingMcpServerFrontDoorsTest.java) | the test itself: how the engine is started and seeded, and every assertion |

## How the run works

1. `mvn -B -Preal-engines -DskipTests install` resolves the engine (the `examples/aegis-validation-*-server` module for this adopter, or a distribution named in this README) and builds the Aegis jars, including the Knox bundle.
2. `ExistingMcpServerFrontDoorsTest` starts the real engine in its own process, as section 1 of `HOW-TO-RUN.md` shows, and creates the data the scenario works on through the engine's own API, outside Aegis.
3. Without Knox: the test builds Aegis from `aegis/own-host.yaml`, starts Aegis's own server on loopback, and sends MCP `tools/call` requests over HTTP as each person, with that person's bearer token.
4. With Knox: the test copies the Aegis bundle into a stock Knox 3.0.0 `ext/`, writes `aegis/knox-topology.xml` to `conf/topologies/`, starts Knox's demo LDAP and the gateway, and sends the same requests with HTTP Basic as the same people. Knox authenticates and checks the route ACL; Aegis governs the MCP call.
5. After every governed write, the test reads the engine directly to check what really changed. Each request and response goes to the transcript, and the Aegis audit journal is copied next to it.

## The two downstream servers

| Source | Transport | Server | What it stands for |
| --- | --- | --- | --- |
| `engine-mcp` | Streamable HTTP | [`engine_mcp_server.py`](engine_mcp_server.py), written only with the official MCP Python SDK 2.2.0 | "the MCP server your project already has". It requires a bearer credential and records the protocol headers of every request it gets. Its job table is its own in-memory state |
| `local-files` | stdio | the official `@modelcontextprotocol/server-filesystem` 2026.8.31, launched by Aegis | a third-party server run as a subprocess |

The point is the MCP protocol path and Aegis's governance of it. `engine_mcp_server.py` is a small server,
not a job engine; the filesystem server is used exactly as published.

## Stateless and stateful MCP

| Run | Profile setting | What the engine server saw |
| --- | --- | --- |
| own host, Knox | `era: AUTO` (as shipped) | Aegis probed 2026-07-28 and the server answered it: all 8 MCP POSTs of the two runs carried `MCP-Protocol-Version: 2026-07-28`, none a session |
| own host, `results/legacy-2025/` | `era: LEGACY` | one `initialize` (no version header, as the 2025 spec has it), then 4 POSTs with `2025-11-25` and the server-assigned `Mcp-Session-Id` |

The other 10 lines in the log are the test's own `GET /state` reads.

[`results/engine-observed.jsonl`](results/engine-observed.jsonl) is the engine server's own record, one
line per HTTP request (`authorized`, `protocolVersion`, `session`) and per tool call.

## What the test proves

| Step | Caller | Call | Own host | Knox |
| --- | --- | --- | --- | --- |
| 1 | `guest` (analyst) | `engine_list_jobs` (HTTP), `read_text_file` (stdio) | allowed; the real job list and file text | allowed |
| 2 | `admin` (outsider) | the same reads | Aegis denies: `POLICY_DENIED` | Knox `AclsAuthz`: HTTP 403 |
| 3 | `sam` (operator) | `write_file`, which the filesystem server has but the profile does not list | `NOT_EXPOSED` (`unlistedTools: HIDE`); no file written | same |
| 4 | `sam` | `engine_restart_all_jobs`, which the profile does not classify | `APPROVAL_REQUIRED`: unclassified means `DESTRUCTIVE`; the server never sees the call | same |
| 5 | `guest` | `engine_delete_job` | `READONLY_CALLER` | `READONLY_CALLER` |
| 6 | `sam` | the same call | `APPROVAL_REQUIRED`; the job still exists | same |
| 7 | `sam` | dry run (`GATEWAY` mode: nothing sent to the server) | receipt and approval request; the job still exists | same |
| 8 | `sam` | approves own request | HTTP 403 | same |
| 9 | `tom` (approver) | approves | HTTP 200 | same |
| 10 | `sam` | delete with token and receipt | allowed; the server no longer has the job, `delete_job` reached it exactly once | same |
| 11 | `sam` | same token again | denied: single use | same |
| 12 | (engine server) | every request it received | carried the gateway's own credential (`ENGINE_MCP_TOKEN`), never a caller's token | same |

Job state is read from the engine server's plain `GET /state` route, outside MCP and outside Aegis.

| Person | Groups in this profile |
| --- | --- |
| `guest` | `analysts` |
| `sam` | `operators` |
| `tom` | `approvers` |
| `admin` | none, and outside the Knox ACL |

## Run it

```powershell
./setup.ps1 -Destination C:\aegis-engines        # pinned mcp==2.2.0 and server-filesystem 2026.8.31
# set the three variables setup.ps1 prints, then:
$env:AEGIS_KNOX_HOME = 'C:\knox\knox-3.0.0'
../run-example.ps1 existing-mcp-server -Keep
```

```bash
./setup.sh /opt/aegis-engines
export AEGIS_KNOX_HOME=/opt/knox/knox-3.0.0
../run-example.sh existing-mcp-server --keep
```

Without `AEGIS_MCP_PYTHON`, `AEGIS_NODE` and `AEGIS_MCP_FILESYSTEM_JS` the test is skipped, never passed.
`run-example -Keep` copies the two-door results; the `legacy-2025/` and `engine-observed.jsonl` files in
`results/` were copied by hand from the same run (`target/adopter-results/existing-mcp-server-legacy-2025/`
and `target/existing-mcp-server/engine-observed.jsonl`).

## Profile change found by this run

The profile listed `read_file`. The filesystem server registers that as deprecated in favor of
`read_text_file`, so the profile now lists `read_text_file`.

## Limits

- `catalog.mode: TOFU` pins tool definitions on first sight; the rug-pull quarantine (a definition that
  changes after pinning) is covered by the engine's own tests, not exercised here.
- The legacy run is own host only; the Knox path is the same client code in the same engine.
