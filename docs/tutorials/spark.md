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

# Tutorial: Apache Spark with Aegis, from your IDE, with and without Knox

Built by `tools/build-project-tutorials.py` using retained evidence from the real run of `SparkFrontDoorsTest` on 2026-10-04 (2 tests, 0 failures, 0 skipped: the own-host door and the Knox 3.0.0 door). The retained files in [`examples/adopters/spark/`](https://github.com/vaquarkhan/aegis-mcp-gateway/tree/{{ site.github.source.branch }}/examples/adopters/spark) provide the scenario's tools, people, outcomes, test count, and date. The generator synthesizes the shared build, runner, endpoint, IDE, audit, and cleanup commands; choose ports, paths, and keys for your environment.

For background on the product's own security model and a longer walkthrough, read [the deep-dive tutorial](../TUTORIAL-SPARK-AND-SPARK-CONNECT.md).

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

The gateway exposes these tools (from [`aegis/own-host.yaml`](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/{{ site.github.source.branch }}/examples/adopters/spark/aegis/own-host.yaml)). Aegis decides each call before the backend sees it. `READ` runs for callers allowed by policy; `DESTRUCTIVE` needs a write group, an independent per-call approval, and a fresh dry-run receipt bound to the same arguments.

| Tool | Class | Source | What it does |
| --- | --- | --- | --- |
| `spark_get_application` | `READ` | `spark-history` | Attempts, duration and completion state of one Spark application. |
| `spark_get_environment` | `READ` | `spark-history` | Spark properties and classpath of one application, useful for drift questions. |
| `spark_kill_submission` | `DESTRUCTIVE` | `spark-master` | Kill a driver submitted in standalone cluster mode. |
| `spark_list_applications` | `READ` | `spark-history` | List Spark applications known to the History Server. |
| `spark_list_executors` | `READ` | `spark-history` | Executor list with task, memory and shuffle counters for one application. |
| `spark_list_jobs` | `READ` | `spark-history` | List jobs of one Spark application. |
| `spark_list_stages` | `READ` | `spark-history` | Stage summaries of one Spark application, the same data the Stages tab shows. |
| `spark_submission_status` | `READ` | `spark-master` | Status of one driver submitted in standalone cluster mode. |
| `spark_submit_batch` | `DESTRUCTIVE` | `spark-master` | Submit a jar as a new driver in standalone cluster mode. This starts code on the cluster. |

| Access setting | Groups |
| --- | --- |
| `access.writeGroups` | `spark-operators` |
| `access.destructiveGroups` | (none) |
| `access.approverGroups` | `spark-approvers` |

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

One command starts the real engine, runs every step through both doors, and checks the engine's own state after each governed write. This is the test that produced the kept results.

```powershell
$env:AEGIS_KNOX_HOME = 'C:\knox\knox-3.0.0'
examples/adopters/run-example.ps1 spark
```

```bash
export AEGIS_KNOX_HOME=/opt/knox/knox-3.0.0
examples/adopters/run-example.sh spark
```

Without `AEGIS_KNOX_HOME` the Knox door is reported as skipped, never as passed.

The runner is a test: it stops the engine and both gateways when it finishes, so nothing is left listening for an IDE. To use an IDE, start the engine and a gateway yourself with sections 4 to 7. Maintainers add `-Keep` (bash: `--keep`) to copy a passing run's results over the retained evidence in `examples/adopters/spark/results/`; those are tracked files, so review the change with `git diff` before committing it.

## 4. The engine

In the commands below `$REPO` is the repository root, as in the retained HOW-TO-RUN.md. Set it first. From the repository root, PowerShell: `$REPO = (Get-Location).Path`. Bash: `export REPO="$(pwd)"`.

Apache Spark 4.0.1 runs inside the test JVM as two real services: a standalone `Master` (`Master.startRpcEnvAndEndpoint`, what `sbin/start-master.sh` runs) with its REST submission server on `127.0.0.1:56868` (`spark.master.rest.enabled=true`), and a `local[1]` application whose web UI REST API is on `127.0.0.1:56881`. To use your own, start `sbin/start-master.sh` with `spark.master.rest.enabled=true` and point the YAML at the Master's REST port and a History Server or driver UI.


The data the scenario works on (topics, tables, queues, jobs) is created by the test's `@BeforeAll` method against the engine's own API, outside Aegis; see the test source.

## 5. Flavor A: without Knox

The file: [`aegis/own-host.yaml`](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/{{ site.github.source.branch }}/examples/adopters/spark/aegis/own-host.yaml), exactly what this door loaded: the shipped adopter profile with the engine address and egress network substituted, plus a `host:` section (loopback listener on a free port, one static bearer token per person, read from the environment).

Environment the gateway reads:

- `AEGIS_SIGNING_KEY`: approval signing key, at least 32 bytes
- `AEGIS_VALIDATION_TOKEN_APPROVER`: bearer token for that persona, at least 16 characters
- `AEGIS_VALIDATION_TOKEN_OPERATOR`: bearer token for that persona, at least 16 characters
- `AEGIS_VALIDATION_TOKEN_OUTSIDER`: bearer token for that persona, at least 16 characters
- `AEGIS_VALIDATION_TOKEN_READER`: bearer token for that persona, at least 16 characters

Start it from the repository root after `mvn -B -Preal-engines -DskipTests install`:

```bash
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar \
  --config examples/adopters/spark/aegis/own-host.yaml
```

The gateway logs `Aegis gateway listening on http://127.0.0.1:<port>/mcp`; `port: 0` in the file means any free port. The admin API is `http://127.0.0.1:<port>/aegis/v1/`. The engine address in the file is the port the test picked for its engine; change it to yours.

A request, the same as the test's first one (`spark_list_applications` as `guest`):

```bash
AEGIS_PORT=8080
curl -s http://127.0.0.1:${AEGIS_PORT}/mcp \
  -H "Authorization: Bearer $AEGIS_VALIDATION_TOKEN_READER" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -H 'MCP-Protocol-Version: 2026-07-28' -H 'Mcp-Method: tools/call' -H 'Mcp-Name: spark_list_applications' \
  -d '{"method":"tools/call","id":1,"jsonrpc":"2.0","params":{"name":"spark_list_applications","arguments":{"status":"running"},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}'
```

Approvals use the admin API on the same door: `POST <admin>/approvals/<id>/approve` as an approver, then `GET <admin>/approvals/<id>` as the requester returns the single-use token. A call carries Aegis controls in `params._meta`: `io.github.vaquarkhan.aegis/dryRun`, `.../approvalToken` and `.../dryRunReceipt`.

For an IDE you want a fixed port: in your copy of `own-host.yaml` set `host.listen.port: 8080`, and point the engine address at your engine. Nothing else changes.

## 6. Flavor B: with Knox

The manual commands in this section are POSIX shell commands. On Windows, use the section 3 runner (`examples/adopters/run-example.ps1 spark`), which does not leave a gateway listening.
The files: [`aegis/knox-aegis.yaml`](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/{{ site.github.source.branch }}/examples/adopters/spark/aegis/knox-aegis.yaml), the same adopter profile with no `host:` section (Knox is the host), and [`aegis/knox-topology.xml`](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/{{ site.github.source.branch }}/examples/adopters/spark/aegis/knox-topology.xml), the Knox topology: ShiroProvider against Knox's demo LDAP, Default identity assertion with `group.principal.mapping` (person to adopter groups), `AclsAuthz` on the route, and the `AEGIS_MCP` service.

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
mkdir -p conf/aegis && cp $REPO/examples/adopters/spark/aegis/knox-aegis.yaml conf/aegis/spark.yaml
cp $REPO/examples/adopters/spark/aegis/knox-topology.xml conf/topologies/aegis-spark.xml   # then replace ${KNOX_HOME} in it
# 5. Knox's demo LDAP (port 33389) and the gateway
java -jar bin/ldap.jar conf &
java -jar bin/gateway.jar
```

Environment for the gateway process (the Aegis configuration reads it):

- `AEGIS_SIGNING_KEY`: approval signing key, at least 32 bytes

The route: `https://localhost:<gateway.port>/gateway/aegis-spark/aegis-mcp/v1/mcp`; the admin API is `.../aegis-mcp/v1/admin/`. Knox demo LDAP users are in `conf/users.ldif`; supply that installation's demo credentials through your terminal or IDE, never in a committed configuration.

A request, the same as the test's first one (`spark_list_applications` as `guest`):

```bash
KNOX_PORT=8443
java -jar "$AEGIS_KNOX_HOME/bin/knoxcli.jar" export-cert --type PEM
# export-cert writes the PEM into the Knox installation, not the current directory.
KNOX_CA_CERT="$AEGIS_KNOX_HOME/data/security/keystores/gateway-client-trust.pem"
read -r -p 'Knox demo user: ' KNOX_USER
# curl prompts for the password because the user value contains no colon.
curl -sS --cacert "$KNOX_CA_CERT" https://localhost:${KNOX_PORT}/gateway/aegis-spark/aegis-mcp/v1/mcp \
  --user "$KNOX_USER" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -H 'MCP-Protocol-Version: 2026-07-28' -H 'Mcp-Method: tools/call' -H 'Mcp-Name: spark_list_applications' \
  -d '{"method":"tools/call","id":1,"jsonrpc":"2.0","params":{"name":"spark_list_applications","arguments":{"status":"running"},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}'
```

Approvals use the admin API on the same door: `POST <admin>/approvals/<id>/approve` as an approver, then `GET <admin>/approvals/<id>` as the requester returns the single-use token. A call carries Aegis controls in `params._meta`: `io.github.vaquarkhan.aegis/dryRun`, `.../approvalToken` and `.../dryRunReceipt`.

The curl example exports Knox's certificate and supplies it with `--cacert`; do not replace this with `-k`. Verify the exported certificate through a trusted channel before using it. Curl prompts for the Knox password because `--user` contains only the user name, keeping the password out of shell history and process arguments.

For an IDE, set `gateway.port` to 8443 in `conf/gateway-site.xml`.

## 7. Connect your IDE

Ready-made files, in each IDE's documented format, are in [`adopters/tutorials/spark/ide/`](https://github.com/vaquarkhan/aegis-mcp-gateway/tree/{{ site.github.source.branch }}/adopters/tutorials/spark/ide):

| IDE | File | Door |
| --- | --- | --- |
| VS Code | `ide/vscode/mcp.json` (copy to `.vscode/mcp.json`) | own host, bearer token prompted |
| VS Code | `ide/vscode/mcp.knox.json` | Knox, HTTP Basic prompted |
| Kiro | `ide/kiro/mcp.json` (copy to `.kiro/settings/mcp.json`) | own host enabled, Knox disabled |
| Cursor | `ide/cursor/mcp.json` (copy to `.cursor/mcp.json`) | own host |

Own host: `http://127.0.0.1:8080/mcp`. Knox: `https://localhost:8443/gateway/aegis-spark/aegis-mcp/v1/mcp`; trust Knox's certificate first (`java -jar bin/knoxcli.jar export-cert --type PEM`). The token you give the IDE decides who you are to Aegis: use the token of the person whose permissions you want the assistant to have.

Copy into the project the editor has open. Cursor: `adopters/tutorials/spark/ide/cursor/mcp.json` to `.cursor/mcp.json`. Kiro: `adopters/tutorials/spark/ide/kiro/mcp.json` to `.kiro/settings/mcp.json`. Leave the `aegis-spark-knox` entry disabled unless you started the Knox door. Replace `REPLACE_WITH_YOUR_TOKEN` with the same value you exported as `AEGIS_VALIDATION_TOKEN_READER` (the person `guest`). Section 3 stops the gateway; the process from section 5 has to still be running.

Reload MCP. You are ready to instruct the agent when the gateway log still says `Aegis gateway listening on http://127.0.0.1:8080/mcp`, and Cursor or Kiro lists `aegis-spark` with its tools. Then use a prompt from section 8 and name `aegis-spark`. The agent calls Aegis. It does not call the engine URL.

These files were checked by `DocumentationAssetsTest` (they parse and name these endpoints). The MCP transport they use was exercised with the official MCP Python SDK in the [Spark and Iceberg tutorial](../TUTORIAL-SPARK-ICEBERG.md); the IDEs themselves were not driven by a test.

## 8. Prompts to try

Ask the assistant in plain language; it picks the tool. What Aegis answered for the same call in the kept own-host run is in the last column.

| Prompt | Tool | Class | What Aegis answered, by person |
| --- | --- | --- | --- |
| Use aegis-spark to list applications with status `running` | `spark_list_applications` | `READ` | `guest`, read by READER: `ALLOWED`; `admin`, same read by an outsider: `DENIED:POLICY_DENIED:step3` |
| Use aegis-spark to show submission status with submissionId `driver-20261004200610-0000` | `spark_submission_status` | `READ` | `guest`, read by READER: `ALLOWED`; `admin`, same read by an outsider: `DENIED:POLICY_DENIED:step3` |
| Use aegis-spark to kill submission with submissionId `driver-20261004200610-0000` | `spark_kill_submission` | `DESTRUCTIVE` | `guest`, destructive by READER: `DENIED:READONLY_CALLER:step1`; `sam`, destructive without approval: `DENIED:APPROVAL_REQUIRED:step4`; `sam`, dry run: `ALLOWED`; `sam`, execute with approval and receipt: `ALLOWED`; `sam`, replay of the same approval: `DENIED:APPROVAL_REQUIRED:step4` |

A `DESTRUCTIVE` call takes four turns, all visible in section 9: the call is denied with an approval request id; a dry run returns a receipt; a different person approves in the admin API; the call with the token and receipt runs once, and a replay is refused.

## 9. What the run showed

### Without Knox

| # | Step | Caller | Request | Result |
| --- | --- | --- | --- | --- |
| 1 | read by READER | `guest` | `tools/call spark_list_applications {"status":"running"}` | HTTP 200, `ALLOWED` |
| 2 | same read by an outsider | `admin` | `tools/call spark_list_applications {"status":"running"}` | HTTP 200, `DENIED:POLICY_DENIED:step3` |
| 3 | read by READER | `guest` | `tools/call spark_submission_status {"submissionId":"driver-20261004200610-0000"}` | HTTP 200, `ALLOWED` |
| 4 | same read by an outsider | `admin` | `tools/call spark_submission_status {"submissionId":"driver-20261004200610-0000"}` | HTTP 200, `DENIED:POLICY_DENIED:step3` |
| 5 | destructive by READER | `guest` | `tools/call spark_kill_submission {"submissionId":"driver-20261004200610-0000"}` | HTTP 200, `DENIED:READONLY_CALLER:step1` |
| 6 | destructive without approval | `sam` | `tools/call spark_kill_submission {"submissionId":"driver-20261004200610-0000"}` | HTTP 200, `DENIED:APPROVAL_REQUIRED:step4` |
| 7 | dry run | `sam` | `tools/call spark_kill_submission {"submissionId":"driver-20261004200610-0000"}` | HTTP 200, `ALLOWED` |
| 8 | requester approves own request | `sam` | `POST approvals/apr_0c94c6612b8cb41a124f0784d99efccf/approve` | HTTP 403 |
| 9 | independent approval | `tom` | `POST approvals/apr_0c94c6612b8cb41a124f0784d99efccf/approve` | HTTP 200 |
| 10 | requester collects the token | `sam` | `GET approvals/apr_0c94c6612b8cb41a124f0784d99efccf` | HTTP 200 |
| 11 | execute with approval and receipt | `sam` | `tools/call spark_kill_submission {"submissionId":"driver-20261004200610-0000"}` | HTTP 200, `ALLOWED` |
| 12 | replay of the same approval | `sam` | `tools/call spark_kill_submission {"submissionId":"driver-20261004200610-0000"}` | HTTP 200, `DENIED:APPROVAL_REQUIRED:step4` |

### With Knox

| # | Step | Caller | Request | Result |
| --- | --- | --- | --- | --- |
| 1 | read by READER | `guest` | `tools/call spark_list_applications {"status":"running"}` | HTTP 200, `ALLOWED` |
| 2 | same read by an outsider | `admin` | `tools/call spark_list_applications {"status":"running"}` | HTTP 403 |
| 3 | read by READER | `guest` | `tools/call spark_submission_status {"submissionId":"driver-20261004200739-0001"}` | HTTP 200, `ALLOWED` |
| 4 | same read by an outsider | `admin` | `tools/call spark_submission_status {"submissionId":"driver-20261004200739-0001"}` | HTTP 403 |
| 5 | destructive by READER | `guest` | `tools/call spark_kill_submission {"submissionId":"driver-20261004200739-0001"}` | HTTP 200, `DENIED:READONLY_CALLER:step1` |
| 6 | destructive without approval | `sam` | `tools/call spark_kill_submission {"submissionId":"driver-20261004200739-0001"}` | HTTP 200, `DENIED:APPROVAL_REQUIRED:step4` |
| 7 | dry run | `sam` | `tools/call spark_kill_submission {"submissionId":"driver-20261004200739-0001"}` | HTTP 200, `ALLOWED` |
| 8 | requester approves own request | `sam` | `POST approvals/apr_0647e283c72b00f04d0cffa415f7f8c1/approve` | HTTP 403 |
| 9 | independent approval | `tom` | `POST approvals/apr_0647e283c72b00f04d0cffa415f7f8c1/approve` | HTTP 200 |
| 10 | requester collects the token | `sam` | `GET approvals/apr_0647e283c72b00f04d0cffa415f7f8c1` | HTTP 200 |
| 11 | execute with approval and receipt | `sam` | `tools/call spark_kill_submission {"submissionId":"driver-20261004200739-0001"}` | HTTP 200, `ALLOWED` |
| 12 | replay of the same approval | `sam` | `tools/call spark_kill_submission {"submissionId":"driver-20261004200739-0001"}` | HTTP 200, `DENIED:APPROVAL_REQUIRED:step4` |

Full requests and responses: [own host](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/{{ site.github.source.branch }}/examples/adopters/spark/results/own-host-transcript.log), [Knox](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/{{ site.github.source.branch }}/examples/adopters/spark/results/knox-transcript.log).

## 10. Audit and clean up

Every decision, approval and result is in the hash-chained journal. Check a kept one:

```bash
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar \
  --verify-audit examples/adopters/spark/results/own-host-audit.jsonl
```

In your own deployment, add `audit.checkpoint` so the head is exported to storage the journal writer cannot rewrite. Verify it with `--verify-audit` plus your journal and checkpoint file paths.

When you ran the full scenario, the test teardown stopped the processes and containers it started. For a manual run, stop the foreground Aegis, Knox, and engine processes with Ctrl+C in their terminals, then stop any background process you started from section 4.
Remove temporary configuration copies, exported certificates, logs, and audit journals only after retaining any evidence your review or incident process requires.

Limits of this run are listed in [the example README](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/{{ site.github.source.branch }}/examples/adopters/spark/README.md).
