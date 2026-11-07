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

# Tutorial: Apache Storm with Aegis, from your IDE, with and without Knox

Built by `tools/build-project-tutorials.py` using retained evidence from the real run of `StormFrontDoorsTest` on 2026-10-05 (2 tests, 0 failures, 0 skipped: the own-host door and the Knox 3.0.0 door). The retained files in [`examples/adopters/storm/`](https://github.com/vaquarkhan/aegis-mcp-gateway/tree/{{ site.github.source.branch }}/examples/adopters/storm) provide the scenario's tools, people, outcomes, test count, and date. The generator synthesizes the shared build, runner, endpoint, IDE, audit, and cleanup commands; choose ports, paths, and keys for your environment.

## Contents

1. What Aegis governs here
2. Prerequisites and build
3. Run the whole verified scenario
4. The engine
5. Flavor A: without Knox
6. Flavor B: with Knox
7. Connect your IDE
8. Prompts to try
9. What the run showed
10. Audit and clean up

## 1. What Aegis governs here

The gateway exposes these tools (from [`aegis/own-host.yaml`](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/{{ site.github.source.branch }}/examples/adopters/storm/aegis/own-host.yaml)). Aegis decides each call before the backend sees it. `READ` runs for callers allowed by policy; `DESTRUCTIVE` needs a write group, an independent per-call approval, and a fresh dry-run receipt bound to the same arguments.

| Tool | Class | Source | What it does |
| --- | --- | --- | --- |
| `storm_cluster_summary` | `READ` | `storm` | Nimbus and supervisor summary. |
| `storm_get_topology` | `READ` | `storm` | Spouts, bolts and statistics of one topology. |
| `storm_kill_topology` | `DESTRUCTIVE` | `storm` | Kill one topology with no wait period. |
| `storm_list_topologies` | `READ` | `storm` | List topologies. |

| Access setting | Groups |
| --- | --- |
| `access.writeGroups` | `storm-operators` |
| `access.destructiveGroups` | (none) |
| `access.approverGroups` | `storm-approvers` |

| Person in the run | Role |
| --- | --- |
| `guest` | reader |
| `admin` | outsider |
| `sam` | operator |
| `tom` | approver |

## 2. Prerequisites and build

- JDK 17 or newer, Maven 3.9 or newer.
- For Flavor B, a stock Apache Knox 3.0.0 from `examples/adopters/knox/install-knox.ps1` (or `.sh`), which checks the release's SHA-512.
- Docker with a running daemon and enough resources for the retained project image or images.

```bash
# from the repository root
mvn -B -Preal-engines -DskipTests install
```

## 3. Run the whole verified scenario

One command starts the real engine, runs every step through both doors, and checks the engine's own state after each governed write. This is the test that produced the kept results.

```powershell
$env:AEGIS_KNOX_HOME = 'C:\knox\knox-3.0.0'
examples/adopters/run-example.ps1 storm
```

This scenario has no `run-example.sh` case. Use the supported PowerShell runner above.
Without `AEGIS_KNOX_HOME` the Knox door is reported as skipped, never as passed.

The runner is a test: it stops the engine and both gateways when it finishes, so nothing is left listening for an IDE. To use an IDE, start the engine and a gateway yourself with sections 4 to 7. Maintainers add `-Keep` (bash: `--keep`) to copy a passing run's results over the retained evidence in `examples/adopters/storm/results/`; those are tracked files, so review the change with `git diff` before committing it.

## 4. The engine

In the commands below `$REPO` is the repository root, as in the retained HOW-TO-RUN.md. Set it first. From the repository root, PowerShell: `$REPO = (Get-Location).Path`. Bash: `export REPO="$(pwd)"`.

The test started these processes, in this order, the way the engine's own launcher scripts start them:

Process 1:

```text
docker \
  run \
  -d \
  --name \
  aegis-val-storm-zk \
  --network \
  aegis-val-storm-net \
  --network-alias \
  zookeeper \
  zookeeper:3.9.3
```

Process 2:

```text
docker \
  run \
  -d \
  --name \
  aegis-val-storm-nimbus \
  --network \
  aegis-val-storm-net \
  --network-alias \
  nimbus \
  storm:2.6.2 \
  storm \
  nimbus \
  -c \
  storm.zookeeper.servers=["zookeeper"]
```

Process 3:

```text
docker \
  run \
  -d \
  --name \
  aegis-val-storm-supervisor \
  --network \
  aegis-val-storm-net \
  storm:2.6.2 \
  storm \
  supervisor \
  -c \
  storm.zookeeper.servers=["zookeeper"] \
  -c \
  nimbus.seeds=["nimbus"]
```

Process 4:

```text
docker \
  run \
  -d \
  --name \
  aegis-val-storm-ui \
  --network \
  aegis-val-storm-net \
  -p \
  127.0.0.1:65047:8080 \
  storm:2.6.2 \
  storm \
  ui \
  -c \
  storm.zookeeper.servers=["zookeeper"] \
  -c \
  nimbus.seeds=["nimbus"]
```


The data the scenario works on (topics, tables, queues, jobs) is created by the test's `@BeforeAll` method against the engine's own API, outside Aegis; see the test source.

## 5. Flavor A: without Knox

The file: [`aegis/own-host.yaml`](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/{{ site.github.source.branch }}/examples/adopters/storm/aegis/own-host.yaml), exactly what this door loaded: the shipped adopter profile with the engine address and egress network substituted, plus a `host:` section (loopback listener on a free port, one static bearer token per person, read from the environment).

Environment the gateway reads:

- `AEGIS_SIGNING_KEY`: approval signing key, at least 32 bytes
- `AEGIS_VALIDATION_TOKEN_APPROVER`: bearer token for that persona, at least 16 characters
- `AEGIS_VALIDATION_TOKEN_OPERATOR`: bearer token for that persona, at least 16 characters
- `AEGIS_VALIDATION_TOKEN_OUTSIDER`: bearer token for that persona, at least 16 characters
- `AEGIS_VALIDATION_TOKEN_READER`: bearer token for that persona, at least 16 characters

Start it from the repository root after `mvn -B -Preal-engines -DskipTests install`:

```bash
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar \
  --config examples/adopters/storm/aegis/own-host.yaml
```

The gateway logs `Aegis gateway listening on http://127.0.0.1:<port>/mcp`; `port: 0` in the file means any free port. The admin API is `http://127.0.0.1:<port>/aegis/v1/`. The engine address in the file is the port the test picked for its engine; change it to yours.

A request, the same as the test's first one (`storm_cluster_summary` as `guest`):

```bash
AEGIS_PORT=8080
curl -s http://127.0.0.1:${AEGIS_PORT}/mcp \
  -H "Authorization: Bearer $AEGIS_VALIDATION_TOKEN_READER" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -H 'MCP-Protocol-Version: 2026-07-28' -H 'Mcp-Method: tools/call' -H 'Mcp-Name: storm_cluster_summary' \
  -d '{"id":1,"method":"tools/call","params":{"name":"storm_cluster_summary","arguments":{},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}},"jsonrpc":"2.0"}'
```

Approvals use the admin API on the same door: `POST <admin>/approvals/<id>/approve` as an approver, then `GET <admin>/approvals/<id>` as the requester returns the single-use token. A call carries Aegis controls in `params._meta`: `io.github.vaquarkhan.aegis/dryRun`, `.../approvalToken` and `.../dryRunReceipt`.

For an IDE you want a fixed port: in your copy of `own-host.yaml` set `host.listen.port: 8080`, and point the engine address at your engine. Nothing else changes.

## 6. Flavor B: with Knox

The manual commands in this section are POSIX shell commands. On Windows, use the section 3 runner (`examples/adopters/run-example.ps1 storm`), which does not leave a gateway listening.
The files: [`aegis/knox-aegis.yaml`](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/{{ site.github.source.branch }}/examples/adopters/storm/aegis/knox-aegis.yaml), the same adopter profile with no `host:` section (Knox is the host), and [`aegis/knox-topology.xml`](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/{{ site.github.source.branch }}/examples/adopters/storm/aegis/knox-topology.xml), the Knox topology: ShiroProvider against Knox's demo LDAP, Default identity assertion with `group.principal.mapping` (person to adopter groups), `AclsAuthz` on the route, and the `AEGIS_MCP` service.

Steps, in the order the test performed them on a stock Knox 3.0.0 (`$AEGIS_KNOX_HOME`, from `examples/adopters/knox/install-knox.ps1` or `.sh`):

```bash
cd $AEGIS_KNOX_HOME
# 1. the Aegis extension
cp $REPO/aegis-knox3-extension/target/aegis-knox3-extension-2.0.0-SNAPSHOT-knox-bundle.jar ext/
# 2. a master secret and a TLS certificate for localhost
java -jar bin/knoxcli.jar create-master
java -jar bin/knoxcli.jar create-cert --hostname localhost
# 3. conf/gateway-site.xml: set gateway.port (the test used a free port) and gateway.host 127.0.0.1
# 4. the Aegis configuration and the topology
mkdir -p conf/aegis && cp $REPO/examples/adopters/storm/aegis/knox-aegis.yaml conf/aegis/storm.yaml
cp $REPO/examples/adopters/storm/aegis/knox-topology.xml conf/topologies/aegis-storm.xml   # then replace ${KNOX_HOME} in it
# 5. Knox's demo LDAP (port 33389) and the gateway
java -jar bin/ldap.jar conf &
ldap_pid=$!
cleanup_ldap() {
  kill "$ldap_pid" 2>/dev/null || true
  wait "$ldap_pid" 2>/dev/null || true
}
# The shell stays in the foreground for Knox; when Knox exits, stop only this LDAP process.
trap cleanup_ldap EXIT
java -jar bin/gateway.jar
```

Environment for the gateway process (the Aegis configuration reads it):

- `AEGIS_SIGNING_KEY`: approval signing key, at least 32 bytes

The route: `https://localhost:<gateway.port>/gateway/aegis-storm/aegis-mcp/v1/mcp`; the admin API is `.../aegis-mcp/v1/admin/`. Knox demo LDAP users are in `conf/users.ldif`; supply that installation's demo credentials through your terminal or IDE, never in a committed configuration.

A request, the same as the test's first one (`storm_cluster_summary` as `guest`):

```bash
KNOX_PORT=8443
java -jar "$AEGIS_KNOX_HOME/bin/knoxcli.jar" export-cert --type PEM
# export-cert writes the PEM into the Knox installation, not the current directory.
KNOX_CA_CERT="$AEGIS_KNOX_HOME/data/security/keystores/gateway-client-trust.pem"
read -r -p 'Knox demo user: ' KNOX_USER
# curl prompts for the password because the user value contains no colon.
curl -sS --cacert "$KNOX_CA_CERT" https://localhost:${KNOX_PORT}/gateway/aegis-storm/aegis-mcp/v1/mcp \
  --user "$KNOX_USER" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -H 'MCP-Protocol-Version: 2026-07-28' -H 'Mcp-Method: tools/call' -H 'Mcp-Name: storm_cluster_summary' \
  -d '{"id":1,"method":"tools/call","params":{"name":"storm_cluster_summary","arguments":{},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}},"jsonrpc":"2.0"}'
```

Approvals use the admin API on the same door: `POST <admin>/approvals/<id>/approve` as an approver, then `GET <admin>/approvals/<id>` as the requester returns the single-use token. A call carries Aegis controls in `params._meta`: `io.github.vaquarkhan.aegis/dryRun`, `.../approvalToken` and `.../dryRunReceipt`.

The curl example exports Knox's certificate and supplies it with `--cacert`; do not replace this with `-k`. Verify the exported certificate through a trusted channel before using it. Curl prompts for the Knox password because `--user` contains only the user name, keeping the password out of shell history and process arguments.

For an IDE, set `gateway.port` to 8443 in `conf/gateway-site.xml`.

## 7. Connect your IDE

Ready-made files, in each IDE's documented format, are in [`adopters/tutorials/storm/ide/`](https://github.com/vaquarkhan/aegis-mcp-gateway/tree/{{ site.github.source.branch }}/adopters/tutorials/storm/ide):

| IDE | File | Door |
| --- | --- | --- |
| VS Code | `ide/vscode/mcp.json` (copy to `.vscode/mcp.json`) | own host, bearer token prompted |
| VS Code | `ide/vscode/mcp.knox.json` | Knox, HTTP Basic prompted |
| Kiro | `ide/kiro/mcp.json` (copy to `.kiro/settings/mcp.json`) | own host enabled, Knox disabled |
| Cursor | `ide/cursor/mcp.json` (copy to `.cursor/mcp.json`) | own host |

Own host: `http://127.0.0.1:8080/mcp`. Knox: `https://localhost:8443/gateway/aegis-storm/aegis-mcp/v1/mcp`; trust Knox's certificate first (`java -jar bin/knoxcli.jar export-cert --type PEM`). The token you give the IDE decides who you are to Aegis: use the token of the person whose permissions you want the assistant to have.

Copy into the project the editor has open. Cursor: `adopters/tutorials/storm/ide/cursor/mcp.json` to `.cursor/mcp.json`. Kiro: `adopters/tutorials/storm/ide/kiro/mcp.json` to `.kiro/settings/mcp.json`. Leave the `aegis-storm-knox` entry disabled unless you started the Knox door. Replace `REPLACE_WITH_YOUR_TOKEN` with the same value you exported as `AEGIS_VALIDATION_TOKEN_READER` (the person `guest`). Section 3 stops the gateway; the process from section 5 has to still be running.

Before enabling the Kiro Knox entry, replace `REPLACE_WITH_BASE64_OF_KNOX_DEMO_CREDENTIALS` with the Base64 encoding of the UTF-8 bytes of the exact `username:password` value from the local Knox demo LDAP. The JSON already contains the `Basic ` scheme, so paste only the encoded value. Do not commit the result.

```powershell
$basic = [Convert]::ToBase64String(
    [Text.Encoding]::UTF8.GetBytes("$KNOX_USER`:$KNOX_PASSWORD")
)
```

```bash
basic="$(printf '%s:%s' "$KNOX_USER" "$KNOX_PASSWORD" | base64 | tr -d '\r\n')"
```

Reload MCP. You are ready to instruct the agent when the gateway log still says `Aegis gateway listening on http://127.0.0.1:8080/mcp`, and Cursor or Kiro lists `aegis-storm` with its tools. Then use a prompt from section 8 and name `aegis-storm`. The agent calls Aegis. It does not call the engine URL.

These files were checked by `DocumentationAssetsTest` (they parse and name these endpoints). The MCP transport they use was exercised with the official MCP Python SDK in the [Spark and Iceberg tutorial](../TUTORIAL-SPARK-ICEBERG.md); the IDEs themselves were not driven by a test.

## 8. Prompts to try

Ask the assistant in plain language; it picks the tool. What Aegis answered for the same call in the kept own-host run is in the last column.

| Prompt | Tool | Class | What Aegis answered, by person |
| --- | --- | --- | --- |
| Use aegis-storm to show cluster summary | `storm_cluster_summary` | `READ` | `guest`, read by READER: `ALLOWED`; `admin`, same read by an outsider: `DENIED:POLICY_DENIED:step3` |
| Use aegis-storm to list topologies | `storm_list_topologies` | `READ` | `guest`, read by READER: `ALLOWED`; `admin`, same read by an outsider: `DENIED:POLICY_DENIED:step3` |
| Use aegis-storm to get topology with id `aegis_ownhost-1-1791217089` | `storm_get_topology` | `READ` | `guest`, read by READER: `ALLOWED`; `admin`, same read by an outsider: `DENIED:POLICY_DENIED:step3` |
| Use aegis-storm to kill topology with id `aegis_ownhost-1-1791217089` | `storm_kill_topology` | `DESTRUCTIVE` | `guest`, destructive by READER: `DENIED:READONLY_CALLER:step1`; `sam`, destructive without approval: `DENIED:APPROVAL_REQUIRED:step4`; `sam`, dry run: `ALLOWED`; `sam`, execute with approval and receipt: `ALLOWED`; `sam`, replay of the same approval: `DENIED:APPROVAL_REQUIRED:step4` |

A `DESTRUCTIVE` call takes four turns, all visible in section 9: the call is denied with an approval request id; a dry run returns a receipt; a different person approves in the admin API; the call with the token and receipt runs once, and a replay is refused.

## 9. What the run showed

### Without Knox

| # | Step | Caller | Request | Result |
| --- | --- | --- | --- | --- |
| 1 | read by READER | `guest` | `tools/call storm_cluster_summary {}` | HTTP 200, `ALLOWED` |
| 2 | same read by an outsider | `admin` | `tools/call storm_cluster_summary {}` | HTTP 200, `DENIED:POLICY_DENIED:step3` |
| 3 | read by READER | `guest` | `tools/call storm_list_topologies {}` | HTTP 200, `ALLOWED` |
| 4 | same read by an outsider | `admin` | `tools/call storm_list_topologies {}` | HTTP 200, `DENIED:POLICY_DENIED:step3` |
| 5 | read by READER | `guest` | `tools/call storm_get_topology {"id":"aegis_ownhost-1-1791217089"}` | HTTP 200, `ALLOWED` |
| 6 | same read by an outsider | `admin` | `tools/call storm_get_topology {"id":"aegis_ownhost-1-1791217089"}` | HTTP 200, `DENIED:POLICY_DENIED:step3` |
| 7 | destructive by READER | `guest` | `tools/call storm_kill_topology {"id":"aegis_ownhost-1-1791217089"}` | HTTP 200, `DENIED:READONLY_CALLER:step1` |
| 8 | destructive without approval | `sam` | `tools/call storm_kill_topology {"id":"aegis_ownhost-1-1791217089"}` | HTTP 200, `DENIED:APPROVAL_REQUIRED:step4` |
| 9 | dry run | `sam` | `tools/call storm_kill_topology {"id":"aegis_ownhost-1-1791217089"}` | HTTP 200, `ALLOWED` |
| 10 | requester approves own request | `sam` | `POST approvals/apr_e0d47226f47733eb1edada9d8a242519/approve` | HTTP 403 |
| 11 | independent approval | `tom` | `POST approvals/apr_e0d47226f47733eb1edada9d8a242519/approve` | HTTP 200 |
| 12 | requester collects the token | `sam` | `GET approvals/apr_e0d47226f47733eb1edada9d8a242519` | HTTP 200 |
| 13 | execute with approval and receipt | `sam` | `tools/call storm_kill_topology {"id":"aegis_ownhost-1-1791217089"}` | HTTP 200, `ALLOWED` |
| 14 | replay of the same approval | `sam` | `tools/call storm_kill_topology {"id":"aegis_ownhost-1-1791217089"}` | HTTP 200, `DENIED:APPROVAL_REQUIRED:step4` |

### With Knox

| # | Step | Caller | Request | Result |
| --- | --- | --- | --- | --- |
| 1 | read by READER | `guest` | `tools/call storm_cluster_summary {}` | HTTP 200, `ALLOWED` |
| 2 | same read by an outsider | `admin` | `tools/call storm_cluster_summary {}` | HTTP 403 |
| 3 | read by READER | `guest` | `tools/call storm_list_topologies {}` | HTTP 200, `ALLOWED` |
| 4 | same read by an outsider | `admin` | `tools/call storm_list_topologies {}` | HTTP 403 |
| 5 | read by READER | `guest` | `tools/call storm_get_topology {"id":"aegis_knox-2-1791217189"}` | HTTP 200, `ALLOWED` |
| 6 | same read by an outsider | `admin` | `tools/call storm_get_topology {"id":"aegis_knox-2-1791217189"}` | HTTP 403 |
| 7 | destructive by READER | `guest` | `tools/call storm_kill_topology {"id":"aegis_knox-2-1791217189"}` | HTTP 200, `DENIED:READONLY_CALLER:step1` |
| 8 | destructive without approval | `sam` | `tools/call storm_kill_topology {"id":"aegis_knox-2-1791217189"}` | HTTP 200, `DENIED:APPROVAL_REQUIRED:step4` |
| 9 | dry run | `sam` | `tools/call storm_kill_topology {"id":"aegis_knox-2-1791217189"}` | HTTP 200, `ALLOWED` |
| 10 | requester approves own request | `sam` | `POST approvals/apr_98efd638967049eca967964715a3d795/approve` | HTTP 403 |
| 11 | independent approval | `tom` | `POST approvals/apr_98efd638967049eca967964715a3d795/approve` | HTTP 200 |
| 12 | requester collects the token | `sam` | `GET approvals/apr_98efd638967049eca967964715a3d795` | HTTP 200 |
| 13 | execute with approval and receipt | `sam` | `tools/call storm_kill_topology {"id":"aegis_knox-2-1791217189"}` | HTTP 200, `ALLOWED` |
| 14 | replay of the same approval | `sam` | `tools/call storm_kill_topology {"id":"aegis_knox-2-1791217189"}` | HTTP 200, `DENIED:APPROVAL_REQUIRED:step4` |

Full requests and responses: [own host](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/{{ site.github.source.branch }}/examples/adopters/storm/results/own-host-transcript.log), [Knox](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/{{ site.github.source.branch }}/examples/adopters/storm/results/knox-transcript.log).

## 10. Audit and clean up

Every decision, approval and result is in the hash-chained journal. Check a kept one:

```bash
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar \
  --verify-audit examples/adopters/storm/results/own-host-audit.jsonl
```

In your own deployment, add `audit.checkpoint` so the head is exported to storage the journal writer cannot rewrite. Verify it with `--verify-audit` plus your journal and checkpoint file paths.

When you ran the full scenario, the test teardown stopped the processes and containers it started. For a manual run, stop the foreground Aegis or Knox process with Ctrl+C in its terminal. The section 6 EXIT trap stops the demo LDAP process that shell started. Stop any background engine process you started in section 4 yourself.
Remove only this tutorial's retained Docker containers after you no longer need their data:

```bash
docker rm -f aegis-val-storm-zk aegis-val-storm-nimbus aegis-val-storm-supervisor aegis-val-storm-ui
```
Remove temporary configuration copies, exported certificates, logs, and audit journals only after retaining any evidence your review or incident process requires.

Limits of this run are listed in [the example README](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/{{ site.github.source.branch }}/examples/adopters/storm/README.md).
