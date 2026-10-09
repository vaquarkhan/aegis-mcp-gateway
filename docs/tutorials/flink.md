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

# Tutorial: Apache Flink with Aegis, from your IDE, with and without Knox

Built by `tools/build-project-tutorials.py` using retained evidence from the real run of `FlinkFrontDoorsTest` on 2026-10-04 (2 tests, 0 failures, 0 skipped: the own-host door and the Knox 3.0.0 door). The retained files in [`examples/adopters/flink/`](../../examples/adopters/flink/) provide the scenario's tools, people, outcomes, test count, and date. The generator synthesizes the shared build, runner, endpoint, IDE, audit, and cleanup commands; choose ports, paths, and keys for your environment.

For background on the product's own security model and a longer walkthrough, read [the deep-dive tutorial](../TUTORIAL-FLINK.md).

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

The gateway exposes these tools (from [`aegis/own-host.yaml`](../../examples/adopters/flink/aegis/own-host.yaml)). Aegis decides each call before the backend sees it. `READ` runs for callers allowed by policy; `MUTATE` needs a write group and an independent per-call approval; `DESTRUCTIVE` needs a write group, an independent per-call approval, and a fresh dry-run receipt bound to the same arguments.

| Tool | Class | Source | What it does |
| --- | --- | --- | --- |
| `flink_cancel_job` | `DESTRUCTIVE` | `flink` | Cancel a running job. Irreversible for the running instance. |
| `flink_get_cluster_info` | `READ` | `flink` | Cluster overview: Flink version, slots total and available, running jobs. |
| `flink_get_flink_config` | `READ` | `flink` | Effective JobManager configuration. |
| `flink_get_job` | `READ` | `flink` | Details of one Flink job. |
| `flink_get_job_config` | `READ` | `flink` | Configuration one job was submitted with. |
| `flink_get_job_exceptions` | `READ` | `flink` | Exception history of one job, most recent first. |
| `flink_get_job_metrics` | `READ` | `flink` | Metrics of one job. |
| `flink_get_job_status` | `READ` | `flink` | State of one job only, without the vertex detail. |
| `flink_get_savepoint_status` | `READ` | `flink` | Status and final location of a savepoint that flink_trigger_savepoint started. |
| `flink_list_checkpoints` | `READ` | `flink` | Checkpoint history and counts for one job. |
| `flink_list_jars` | `READ` | `flink` | List jars uploaded to this JobManager. |
| `flink_list_jobs` | `READ` | `flink` | List Flink jobs with their state. |
| `flink_list_taskmanagers` | `READ` | `flink` | List TaskManagers with their slot and resource counts. |
| `flink_rescale_job` | `MUTATE` | `flink` | Change the parallelism of a running job. |
| `flink_run_jar` | `DESTRUCTIVE` | `flink` | Run an already uploaded jar as a new job. This starts code on the cluster. |
| `flink_stop_job` | `DESTRUCTIVE` | `flink` | Stop a job with a final savepoint. The job does not resume by itself. |
| `flink_trigger_savepoint` | `MUTATE` | `flink` | Trigger a savepoint for a running job. |

| Access setting | Groups |
| --- | --- |
| `access.writeGroups` | `flink-operators` |
| `access.destructiveGroups` | (none) |
| `access.approverGroups` | `flink-approvers` |

| Person in the run | Role |
| --- | --- |
| `guest` | reader |
| `admin` | outsider |
| `sam` | operator |
| `tom` | approver |

## 2. Prerequisites and build

- JDK 17 or newer, Maven 3.9 or newer.
- For Flavor B, a stock Apache Knox 3.0.0 from `examples/adopters/knox/install-knox.ps1` (or `.sh`), which checks the release's SHA-512.

```bash
# from the repository root
mvn -B -Preal-engines -DskipTests install
```

## 3. Run the whole verified scenario

One command starts the real engine, runs every step through both doors, and checks the engine's own state after each governed write. This is how the kept results were produced.

```powershell
$env:AEGIS_KNOX_HOME = 'C:\knox\knox-3.0.0'
examples/adopters/run-example.ps1 flink -Keep
```

```bash
export AEGIS_KNOX_HOME=/opt/knox/knox-3.0.0
examples/adopters/run-example.sh flink --keep
```

Without `AEGIS_KNOX_HOME` the Knox door is reported as skipped, never as passed.

## 4. The engine

In the commands below `$REPO` is the repository root, as in the retained HOW-TO-RUN.md.

Apache Flink 1.20.1 runs inside the test JVM as a `MiniCluster` (1 TaskManager, 2 slots) with its JobManager REST endpoint on `127.0.0.1:61660`: the same REST API a standalone cluster serves. To use your own cluster instead, start it with `bin/start-cluster.sh` and put its REST address in the YAML. Per front door, the test submits an endless streaming job (source to discarding sink) with the Flink client, which `flink_cancel_job` then cancels.


The data the scenario works on (topics, tables, queues, jobs) is created by the test's `@BeforeAll` method against the engine's own API, outside Aegis; see the test source.

## 5. Flavor A: without Knox

The file: [`aegis/own-host.yaml`](../../examples/adopters/flink/aegis/own-host.yaml), exactly what this door loaded: the shipped adopter profile with the engine address and egress network substituted, plus a `host:` section (loopback listener on a free port, one static bearer token per person, read from the environment).

Environment the gateway reads:

- `AEGIS_SIGNING_KEY`: approval signing key, at least 32 bytes
- `AEGIS_VALIDATION_TOKEN_APPROVER`: bearer token for that persona, at least 16 characters
- `AEGIS_VALIDATION_TOKEN_OPERATOR`: bearer token for that persona, at least 16 characters
- `AEGIS_VALIDATION_TOKEN_OUTSIDER`: bearer token for that persona, at least 16 characters
- `AEGIS_VALIDATION_TOKEN_READER`: bearer token for that persona, at least 16 characters

Start it from the repository root after `mvn -B -Preal-engines -DskipTests install`:

```bash
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar \
  --config examples/adopters/flink/aegis/own-host.yaml
```

The gateway logs `Aegis gateway listening on http://127.0.0.1:<port>/mcp`; `port: 0` in the file means any free port. The admin API is `http://127.0.0.1:<port>/aegis/v1/`. The engine address in the file is the port the test picked for its engine; change it to yours.

A request, the same as the test's first one (`flink_list_jobs` as `guest`):

```bash
AEGIS_PORT=8080
curl -s http://127.0.0.1:${AEGIS_PORT}/mcp \
  -H "Authorization: Bearer $AEGIS_VALIDATION_TOKEN_READER" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -H 'MCP-Protocol-Version: 2026-07-28' -H 'Mcp-Method: tools/call' -H 'Mcp-Name: flink_list_jobs' \
  -d '{"id":1,"jsonrpc":"2.0","params":{"name":"flink_list_jobs","arguments":{},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}},"method":"tools/call"}'
```

Approvals use the admin API on the same door: `POST <admin>/approvals/<id>/approve` as an approver, then `GET <admin>/approvals/<id>` as the requester returns the single-use token. A call carries Aegis controls in `params._meta`: `io.github.vaquarkhan.aegis/dryRun`, `.../approvalToken` and `.../dryRunReceipt`.

For an IDE you want a fixed port: in your copy of `own-host.yaml` set `host.listen.port: 8080`, and point the engine address at your engine. Nothing else changes.

## 6. Flavor B: with Knox

The manual commands in this section are POSIX shell commands. On Windows, use the supported `examples/adopters/run-example.ps1 flink -Keep` runner from section 3.
The files: [`aegis/knox-aegis.yaml`](../../examples/adopters/flink/aegis/knox-aegis.yaml), the same adopter profile with no `host:` section (Knox is the host), and [`aegis/knox-topology.xml`](../../examples/adopters/flink/aegis/knox-topology.xml), the Knox topology: ShiroProvider against Knox's demo LDAP, Default identity assertion with `group.principal.mapping` (person to adopter groups), `AclsAuthz` on the route, and the `AEGIS_MCP` service.

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
mkdir -p conf/aegis && cp $REPO/examples/adopters/flink/aegis/knox-aegis.yaml conf/aegis/flink.yaml
cp $REPO/examples/adopters/flink/aegis/knox-topology.xml conf/topologies/aegis-flink.xml   # then replace ${KNOX_HOME} in it
# 5. Knox's demo LDAP (port 33389) and the gateway
java -jar bin/ldap.jar conf &
java -jar bin/gateway.jar
```

Environment for the gateway process (the Aegis configuration reads it):

- `AEGIS_SIGNING_KEY`: approval signing key, at least 32 bytes

The route: `https://localhost:<gateway.port>/gateway/aegis-flink/aegis-mcp/v1/mcp`; the admin API is `.../aegis-mcp/v1/admin/`. Knox demo LDAP users are in `conf/users.ldif`; supply that installation's demo credentials through your terminal or IDE, never in a committed configuration.

A request, the same as the test's first one (`flink_list_jobs` as `guest`):

```bash
KNOX_PORT=8443
java -jar "$AEGIS_KNOX_HOME/bin/knoxcli.jar" export-cert --type PEM
KNOX_CA_CERT="$PWD/gateway-client-trust.pem"
read -r -p 'Knox demo user: ' KNOX_USER
# curl prompts for the password because the user value contains no colon.
curl -sS --cacert "$KNOX_CA_CERT" https://localhost:${KNOX_PORT}/gateway/aegis-flink/aegis-mcp/v1/mcp \
  --user "$KNOX_USER" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -H 'MCP-Protocol-Version: 2026-07-28' -H 'Mcp-Method: tools/call' -H 'Mcp-Name: flink_list_jobs' \
  -d '{"id":1,"jsonrpc":"2.0","params":{"name":"flink_list_jobs","arguments":{},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}},"method":"tools/call"}'
```

Approvals use the admin API on the same door: `POST <admin>/approvals/<id>/approve` as an approver, then `GET <admin>/approvals/<id>` as the requester returns the single-use token. A call carries Aegis controls in `params._meta`: `io.github.vaquarkhan.aegis/dryRun`, `.../approvalToken` and `.../dryRunReceipt`.

The curl example exports Knox's certificate and supplies it with `--cacert`; do not replace this with `-k`. Verify the exported certificate through a trusted channel before using it. Curl prompts for the Knox password because `--user` contains only the user name, keeping the password out of shell history and process arguments.

For an IDE, set `gateway.port` to 8443 in `conf/gateway-site.xml`.

## 7. Connect your IDE

Ready-made files, in each IDE's documented format, are in [`adopters/tutorials/flink/ide/`](../../adopters/tutorials/flink/ide/):

| IDE | File | Door |
| --- | --- | --- |
| VS Code | `ide/vscode/mcp.json` (copy to `.vscode/mcp.json`) | own host, bearer token prompted |
| VS Code | `ide/vscode/mcp.knox.json` | Knox, HTTP Basic prompted |
| Kiro | `ide/kiro/mcp.json` (copy to `.kiro/settings/mcp.json`) | own host enabled, Knox disabled |
| Cursor | `ide/cursor/mcp.json` (copy to `.cursor/mcp.json`) | own host |

Own host: `http://127.0.0.1:8080/mcp`. Knox: `https://localhost:8443/gateway/aegis-flink/aegis-mcp/v1/mcp`; trust Knox's certificate first (`java -jar bin/knoxcli.jar export-cert --type PEM`). The token you give the IDE decides who you are to Aegis: use the token of the person whose permissions you want the assistant to have.

These files were checked by `DocumentationAssetsTest` (they parse and name these endpoints). The MCP transport they use was exercised with the official MCP Python SDK in the [Spark and Iceberg tutorial](../TUTORIAL-SPARK-ICEBERG.md); the IDEs themselves were not driven by a test.

## 8. Prompts to try

Ask the assistant in plain language; it picks the tool. What Aegis answered for the same call in the kept own-host run is in the last column.

| Prompt | Tool | Class | What Aegis answered, by person |
| --- | --- | --- | --- |
| Use aegis-flink to list jobs | `flink_list_jobs` | `READ` | `guest`, read by READER: `ALLOWED`; `admin`, same read by an outsider: `DENIED:POLICY_DENIED:step3` |
| Use aegis-flink to cancel job with jobId `72eea7e355dbd827a5540977b18a6fef` | `flink_cancel_job` | `DESTRUCTIVE` | `guest`, destructive by READER: `DENIED:READONLY_CALLER:step1`; `sam`, destructive without approval: `DENIED:APPROVAL_REQUIRED:step4`; `sam`, dry run: `ALLOWED`; `sam`, execute with approval and receipt: `ALLOWED`; `sam`, replay of the same approval: `DENIED:APPROVAL_REQUIRED:step4` |

A `DESTRUCTIVE` call takes four turns, all visible in section 9: the call is denied with an approval request id; a dry run returns a receipt; a different person approves in the admin API; the call with the token and receipt runs once, and a replay is refused.

## 9. What the run showed

### Without Knox

| # | Step | Caller | Request | Result |
| --- | --- | --- | --- | --- |
| 1 | read by READER | `guest` | `tools/call flink_list_jobs {}` | HTTP 200, `ALLOWED` |
| 2 | same read by an outsider | `admin` | `tools/call flink_list_jobs {}` | HTTP 200, `DENIED:POLICY_DENIED:step3` |
| 3 | destructive by READER | `guest` | `tools/call flink_cancel_job {"jobId":"72eea7e355dbd827a5540977b18a6fef"}` | HTTP 200, `DENIED:READONLY_CALLER:step1` |
| 4 | destructive without approval | `sam` | `tools/call flink_cancel_job {"jobId":"72eea7e355dbd827a5540977b18a6fef"}` | HTTP 200, `DENIED:APPROVAL_REQUIRED:step4` |
| 5 | dry run | `sam` | `tools/call flink_cancel_job {"jobId":"72eea7e355dbd827a5540977b18a6fef"}` | HTTP 200, `ALLOWED` |
| 6 | requester approves own request | `sam` | `POST approvals/apr_60db41a95e2682b02e1a2001921e50c1/approve` | HTTP 403 |
| 7 | independent approval | `tom` | `POST approvals/apr_60db41a95e2682b02e1a2001921e50c1/approve` | HTTP 200 |
| 8 | requester collects the token | `sam` | `GET approvals/apr_60db41a95e2682b02e1a2001921e50c1` | HTTP 200 |
| 9 | execute with approval and receipt | `sam` | `tools/call flink_cancel_job {"jobId":"72eea7e355dbd827a5540977b18a6fef"}` | HTTP 200, `ALLOWED` |
| 10 | replay of the same approval | `sam` | `tools/call flink_cancel_job {"jobId":"72eea7e355dbd827a5540977b18a6fef"}` | HTTP 200, `DENIED:APPROVAL_REQUIRED:step4` |

### With Knox

| # | Step | Caller | Request | Result |
| --- | --- | --- | --- | --- |
| 1 | read by READER | `guest` | `tools/call flink_list_jobs {}` | HTTP 200, `ALLOWED` |
| 2 | same read by an outsider | `admin` | `tools/call flink_list_jobs {}` | HTTP 403 |
| 3 | destructive by READER | `guest` | `tools/call flink_cancel_job {"jobId":"d40e564b1dadfc2e3009e6119dd9f613"}` | HTTP 200, `DENIED:READONLY_CALLER:step1` |
| 4 | destructive without approval | `sam` | `tools/call flink_cancel_job {"jobId":"d40e564b1dadfc2e3009e6119dd9f613"}` | HTTP 200, `DENIED:APPROVAL_REQUIRED:step4` |
| 5 | dry run | `sam` | `tools/call flink_cancel_job {"jobId":"d40e564b1dadfc2e3009e6119dd9f613"}` | HTTP 200, `ALLOWED` |
| 6 | requester approves own request | `sam` | `POST approvals/apr_19f4e325e18a2d0ed709c6a73effb354/approve` | HTTP 403 |
| 7 | independent approval | `tom` | `POST approvals/apr_19f4e325e18a2d0ed709c6a73effb354/approve` | HTTP 200 |
| 8 | requester collects the token | `sam` | `GET approvals/apr_19f4e325e18a2d0ed709c6a73effb354` | HTTP 200 |
| 9 | execute with approval and receipt | `sam` | `tools/call flink_cancel_job {"jobId":"d40e564b1dadfc2e3009e6119dd9f613"}` | HTTP 200, `ALLOWED` |
| 10 | replay of the same approval | `sam` | `tools/call flink_cancel_job {"jobId":"d40e564b1dadfc2e3009e6119dd9f613"}` | HTTP 200, `DENIED:APPROVAL_REQUIRED:step4` |

Full requests and responses: [own host](../../examples/adopters/flink/results/own-host-transcript.log), [Knox](../../examples/adopters/flink/results/knox-transcript.log).

## 10. Audit and clean up

Every decision, approval and result is in the hash-chained journal. Check a kept one:

```bash
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar \
  --verify-audit examples/adopters/flink/results/own-host-audit.jsonl
```

In your own deployment, add `audit.checkpoint` so the head is exported to storage the journal writer cannot rewrite. Verify it with `--verify-audit` plus your journal and checkpoint file paths.

When you ran the full scenario, the test teardown stopped the processes and containers it started. For a manual run, stop the foreground Aegis, Knox, and engine processes with Ctrl+C in their terminals, then stop any background process you started from section 4.
Remove temporary configuration copies, exported certificates, logs, and audit journals only after retaining any evidence your review or incident process requires.

Limits of this run are listed in [the example README](../../examples/adopters/flink/README.md).
