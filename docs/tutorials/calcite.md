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

# Tutorial: Apache Calcite (Avatica) with Aegis, from your IDE, with and without Knox

Built by `tools/build-project-tutorials.py` from the kept real run of `CalciteFrontDoorsTest` on 2026-10-04 (2 tests, 0 failures, 0 skipped: the own-host door and the Knox 3.0.0 door). Every command, tool, person and result below is taken from that run's files in [`examples/adopters/calcite/`](../../examples/adopters/calcite/). Ports, paths and keys are the ones you choose.

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

The gateway exposes these tools (from [`aegis/own-host.yaml`](../../examples/adopters/calcite/aegis/own-host.yaml)). Aegis decides each call before the backend sees it: `READ` runs for callers a policy rule allows; `MUTATE` and `DESTRUCTIVE` need a write group and an independent per-call approval, and `DESTRUCTIVE` also a fresh dry-run receipt bound to the same arguments.

| Tool | Class | Source | What it does |
| --- | --- | --- | --- |
| `jdbc_list_tables` | `READ` | `calcite` | Description comes from the `jdbc` source plugin; the kept run does not record it. |
| `jdbc_run_query` | `READ` | `calcite` | Description comes from the `jdbc` source plugin; the kept run does not record it. |

| Access setting | Groups |
| --- | --- |
| `access.writeGroups` | `calcite-operators` |
| `access.destructiveGroups` | (none) |
| `access.approverGroups` | `calcite-approvers` |

| Person in the run | Role |
| --- | --- |
| `guest` | reader |
| `admin` | outsider |
| `sam` | operator |

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
examples/adopters/run-example.ps1 calcite -Keep
```

```bash
export AEGIS_KNOX_HOME=/opt/knox/knox-3.0.0
examples/adopters/run-example.sh calcite --keep
```

Without `AEGIS_KNOX_HOME` the Knox door is reported as skipped, never as passed.

## 4. The engine

In the commands below `$REPO` is the repository root, as in the kept HOW-TO-RUN.md.

The test started one engine process, the way the engine's own launcher script starts it:

Process 1:

```text
java \
  -cp \
  <the jars listed in examples/aegis-validation-calcite-server/target/server.classpath> \
  org.apache.calcite.avatica.standalone.StandaloneServer \
  -u \
  jdbc:calcite:model=examples/aegis-validation/target/calcite-server/model.json \
  -p \
  49258 \
  -s \
  JSON
```

`examples/aegis-validation-calcite-server/target/server.classpath` is written by the `-Preal-engines` build of that module: the engine's jars, at the version its `pom.xml` names, as Maven resolved them. With a binary distribution, use its own `lib/` instead.


The data the scenario works on (topics, tables, queues, jobs) is created by the test's `@BeforeAll` method against the engine's own API, outside Aegis; see the test source.

## 5. Flavor A: without Knox

The file: [`aegis/own-host.yaml`](../../examples/adopters/calcite/aegis/own-host.yaml), exactly what this door loaded: the shipped adopter profile with the engine address and egress network substituted, plus a `host:` section (loopback listener on a free port, one static bearer token per person, read from the environment).

Environment the gateway reads:

- `AEGIS_SIGNING_KEY`: approval signing key, at least 32 bytes
- `AEGIS_VALIDATION_TOKEN_APPROVER`: bearer token for that persona, at least 16 characters
- `AEGIS_VALIDATION_TOKEN_OPERATOR`: bearer token for that persona, at least 16 characters
- `AEGIS_VALIDATION_TOKEN_OUTSIDER`: bearer token for that persona, at least 16 characters
- `AEGIS_VALIDATION_TOKEN_READER`: bearer token for that persona, at least 16 characters
- `CALCITE_PASSWORD`: credential the adopter YAML reads
- `CALCITE_USER`: value `aegis`

Start it from the repository root after `mvn -B -Preal-engines -DskipTests install`:

```bash
# this profile uses a source plugin: put it and its jars on the classpath (same jars as Knox ext/, below)
java -cp "aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar:<plugin jars>" io.github.vaquarkhan.aegis.gateway.Main \
  --config examples/adopters/calcite/aegis/own-host.yaml
```

Plugin jars this run used: listed in the kept [HOW-TO-RUN.md](../../examples/adopters/calcite/HOW-TO-RUN.md), section 2.

The gateway logs `Aegis gateway listening on http://127.0.0.1:<port>/mcp`; `port: 0` in the file means any free port. The admin API is `http://127.0.0.1:<port>/aegis/v1/`. The engine address in the file is the port the test picked for its engine; change it to yours.

A request, the same as the test's first one (`jdbc_list_tables` as `guest`):

```bash
curl -s http://127.0.0.1:<port>/mcp \
  -H "Authorization: Bearer $AEGIS_VALIDATION_TOKEN_READER" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -H 'MCP-Protocol-Version: 2026-07-28' -H 'Mcp-Method: tools/call' -H 'Mcp-Name: jdbc_list_tables' \
  -d '{"id":1,"jsonrpc":"2.0","params":{"name":"jdbc_list_tables","arguments":{"schema":"SALES"},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}},"method":"tools/call"}'
```

Approvals use the admin API on the same door: `POST <admin>/approvals/<id>/approve` as an approver, then `GET <admin>/approvals/<id>` as the requester returns the single-use token. A call carries Aegis controls in `params._meta`: `io.github.vaquarkhan.aegis/dryRun`, `.../approvalToken` and `.../dryRunReceipt`.

For an IDE you want a fixed port: in your copy of `own-host.yaml` set `host.listen.port: 8080`, and point the engine address at your engine. Nothing else changes.

## 6. Flavor B: with Knox

The files: [`aegis/knox-aegis.yaml`](../../examples/adopters/calcite/aegis/knox-aegis.yaml), the same adopter profile with no `host:` section (Knox is the host), and [`aegis/knox-topology.xml`](../../examples/adopters/calcite/aegis/knox-topology.xml), the Knox topology: ShiroProvider against Knox's demo LDAP, Default identity assertion with `group.principal.mapping` (person to adopter groups), `AclsAuthz` on the route, and the `AEGIS_MCP` service.

Steps, in the order the test performed them on a stock Knox 3.0.0 (`$AEGIS_KNOX_HOME`, from `examples/adopters/knox/install-knox.ps1` or `.sh`):

```bash
cd $AEGIS_KNOX_HOME
# 1. the Aegis extension
cp $REPO/aegis-knox3-extension/target/aegis-knox3-extension-2.0.0-SNAPSHOT-knox-bundle.jar ext/
# 1b. the source plugin: copy each jar marked ADD in results/knox-ext-plugins.log to ext/aegis-plugin-<jar>
# 2. a master secret and a TLS certificate for localhost
java -jar bin/knoxcli.jar create-master --master <a master secret of your choice>
java -jar bin/knoxcli.jar create-cert --hostname localhost
# 3. conf/gateway-site.xml: set gateway.port (the test used a free port) and gateway.host 127.0.0.1
# 4. the Aegis configuration and the topology
mkdir -p conf/aegis && cp $REPO/examples/adopters/calcite/aegis/knox-aegis.yaml conf/aegis/calcite.yaml
cp $REPO/examples/adopters/calcite/aegis/knox-topology.xml conf/topologies/aegis-calcite.xml   # then replace ${KNOX_HOME} in it
# 5. Knox's demo LDAP (port 33389) and the gateway
java -jar bin/ldap.jar conf &
java -jar bin/gateway.jar
```

Environment for the gateway process (the Aegis configuration reads it):

- `AEGIS_SIGNING_KEY`: approval signing key, at least 32 bytes
- `CALCITE_PASSWORD`: credential the adopter YAML reads
- `CALCITE_USER`: value `aegis`

The route: `https://localhost:<gateway.port>/gateway/aegis-calcite/aegis-mcp/v1/mcp`; the admin API is `.../aegis-mcp/v1/admin/`. Knox demo LDAP users are in `conf/users.ldif`; supply that installation's demo credentials through your terminal or IDE, never in a committed configuration.

A request, the same as the test's first one (`jdbc_list_tables` as `guest`):

```bash
# -k because the certificate is the self-signed one from step 2; or export it with knoxcli export-cert
curl -sk https://localhost:<gateway.port>/gateway/aegis-calcite/aegis-mcp/v1/mcp \
  -u <knox-demo-user>:<knox-demo-password> \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -H 'MCP-Protocol-Version: 2026-07-28' -H 'Mcp-Method: tools/call' -H 'Mcp-Name: jdbc_list_tables' \
  -d '{"id":1,"jsonrpc":"2.0","params":{"name":"jdbc_list_tables","arguments":{"schema":"SALES"},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}},"method":"tools/call"}'
```

Approvals use the admin API on the same door: `POST <admin>/approvals/<id>/approve` as an approver, then `GET <admin>/approvals/<id>` as the requester returns the single-use token. A call carries Aegis controls in `params._meta`: `io.github.vaquarkhan.aegis/dryRun`, `.../approvalToken` and `.../dryRunReceipt`.

For an IDE, set `gateway.port` to 8443 in `conf/gateway-site.xml`.

## 7. Connect your IDE

Ready-made files, in each IDE's documented format, are in [`adopters/tutorials/calcite/ide/`](../../adopters/tutorials/calcite/ide/):

| IDE | File | Door |
| --- | --- | --- |
| VS Code | `ide/vscode/mcp.json` (copy to `.vscode/mcp.json`) | own host, bearer token prompted |
| VS Code | `ide/vscode/mcp.knox.json` | Knox, HTTP Basic prompted |
| Kiro | `ide/kiro/mcp.json` (copy to `.kiro/settings/mcp.json`) | own host enabled, Knox disabled |
| Cursor | `ide/cursor/mcp.json` (copy to `.cursor/mcp.json`) | own host |

Own host: `http://127.0.0.1:8080/mcp`. Knox: `https://localhost:8443/gateway/aegis-calcite/aegis-mcp/v1/mcp`; trust Knox's certificate first (`java -jar bin/knoxcli.jar export-cert --type PEM`). The token you give the IDE decides who you are to Aegis: use the token of the person whose permissions you want the assistant to have.

These files were checked by `DocumentationAssetsTest` (they parse and name these endpoints). The MCP transport they use was exercised with the official MCP Python SDK in the [Spark and Iceberg tutorial](../TUTORIAL-SPARK-ICEBERG.md); the IDEs themselves were not driven by a test.

## 8. Prompts to try

Ask the assistant in plain language; it picks the tool. What Aegis answered for the same call in the kept own-host run is in the last column.

| Prompt | Tool | Class | What Aegis answered, by person |
| --- | --- | --- | --- |
| Use aegis-calcite to list tables with schema `SALES` | `jdbc_list_tables` | `READ` | `guest`, read by READER: `ALLOWED`; `admin`, same read by an outsider: `DENIED:POLICY_DENIED:step3` |
| Use aegis-calcite to run query with sql `SELECT ITEM, PRICE FROM SALES.ORDERS WHERE PRICE > 5 ORDER BY ID` | `jdbc_run_query` | `READ` | `guest`, read by READER: `ALLOWED`; `admin`, same read by an outsider: `DENIED:POLICY_DENIED:step3`; `sam`, write smuggled into the read-only SQL tool: `DENIED:INVALID_INPUT:step8`; `sam`, write hidden after a comment: `DENIED:INVALID_INPUT:step8` |

A `DESTRUCTIVE` call takes four turns, all visible in section 9: the call is denied with an approval request id; a dry run returns a receipt; a different person approves in the admin API; the call with the token and receipt runs once, and a replay is refused.

## 9. What the run showed

### Without Knox

| # | Step | Caller | Request | Result |
| --- | --- | --- | --- | --- |
| 1 | read by READER | `guest` | `tools/call jdbc_list_tables {"schema":"SALES"}` | HTTP 200, `ALLOWED` |
| 2 | same read by an outsider | `admin` | `tools/call jdbc_list_tables {"schema":"SALES"}` | HTTP 200, `DENIED:POLICY_DENIED:step3` |
| 3 | read by READER | `guest` | `tools/call jdbc_run_query {"sql":"SELECT ITEM, PRICE FROM SALES.ORDERS WHERE PRICE > 5 ORDER BY ID"}` | HTTP 200, `ALLOWED` |
| 4 | same read by an outsider | `admin` | `tools/call jdbc_run_query {"sql":"SELECT ITEM, PRICE FROM SALES.ORDERS WHERE PRICE > 5 ORDER BY ID"}` | HTTP 200, `DENIED:POLICY_DENIED:step3` |
| 5 | write smuggled into the read-only SQL tool | `sam` | `tools/call jdbc_run_query {"sql":"DELETE FROM SALES.ORDERS"}` | HTTP 200, `DENIED:INVALID_INPUT:step8` |
| 6 | write hidden after a comment | `sam` | `tools/call jdbc_run_query {"sql":"/* report */ DROP TABLE SALES.ORDERS"}` | HTTP 200, `DENIED:INVALID_INPUT:step8` |

### With Knox

| # | Step | Caller | Request | Result |
| --- | --- | --- | --- | --- |
| 1 | read by READER | `guest` | `tools/call jdbc_list_tables {"schema":"SALES"}` | HTTP 200, `ALLOWED` |
| 2 | same read by an outsider | `admin` | `tools/call jdbc_list_tables {"schema":"SALES"}` | HTTP 403 |
| 3 | read by READER | `guest` | `tools/call jdbc_run_query {"sql":"SELECT ITEM, PRICE FROM SALES.ORDERS WHERE PRICE > 5 ORDER BY ID"}` | HTTP 200, `ALLOWED` |
| 4 | same read by an outsider | `admin` | `tools/call jdbc_run_query {"sql":"SELECT ITEM, PRICE FROM SALES.ORDERS WHERE PRICE > 5 ORDER BY ID"}` | HTTP 403 |
| 5 | write smuggled into the read-only SQL tool | `sam` | `tools/call jdbc_run_query {"sql":"DELETE FROM SALES.ORDERS"}` | HTTP 200, `DENIED:INVALID_INPUT:step8` |
| 6 | write hidden after a comment | `sam` | `tools/call jdbc_run_query {"sql":"/* report */ DROP TABLE SALES.ORDERS"}` | HTTP 200, `DENIED:INVALID_INPUT:step8` |

Full requests and responses: [own host](../../examples/adopters/calcite/results/own-host-transcript.log), [Knox](../../examples/adopters/calcite/results/knox-transcript.log).

## 10. Audit and clean up

Every decision, approval and result is in the hash-chained journal. Check a kept one:

```bash
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar \
  --verify-audit examples/adopters/calcite/results/own-host-audit.jsonl
```

In your own deployment, add `audit.checkpoint` so the head is exported to storage the journal writer cannot rewrite, and check with `--verify-audit <journal> --checkpoints <file>`.

Limits of this run are listed in [the example README](../../examples/adopters/calcite/README.md).
