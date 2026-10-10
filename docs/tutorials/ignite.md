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

# Tutorial: Apache Ignite with Aegis, from your IDE, with and without Knox

Built by `tools/build-project-tutorials.py` using retained evidence from the real run of `IgniteFrontDoorsTest` on 2026-10-04 (2 tests, 0 failures, 0 skipped: the own-host door and the Knox 3.0.0 door). The retained files in [`examples/adopters/ignite/`](https://github.com/vaquarkhan/aegis-mcp-gateway/tree/aegis-2.0-review-20261009/examples/adopters/ignite) provide the scenario's tools, people, outcomes, test count, and date. The generator synthesizes the shared build, runner, endpoint, IDE, audit, and cleanup commands; choose ports, paths, and keys for your environment.

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

The gateway exposes these tools (from [`aegis/own-host.yaml`](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/aegis-2.0-review-20261009/examples/adopters/ignite/aegis/own-host.yaml)). Aegis decides each call before the backend sees it. `READ` runs for callers allowed by policy; `DESTRUCTIVE` needs a write group, an independent per-call approval, and a fresh dry-run receipt bound to the same arguments.

| Tool | Class | Source | What it does |
| --- | --- | --- | --- |
| `ignite_cache_size` | `READ` | `ignite` | Number of entries in one cache. |
| `ignite_destroy_cache` | `DESTRUCTIVE` | `ignite` | Destroy one cache and all entries in it. |
| `ignite_get_version` | `READ` | `ignite` | Ignite version. |
| `ignite_list_caches` | `READ` | `ignite` | Cluster topology and its caches. |

| Access setting | Groups |
| --- | --- |
| `access.writeGroups` | `ignite-operators` |
| `access.destructiveGroups` | (none) |
| `access.approverGroups` | `ignite-approvers` |

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
examples/adopters/run-example.ps1 ignite -Keep
```

```bash
export AEGIS_KNOX_HOME=/opt/knox/knox-3.0.0
examples/adopters/run-example.sh ignite --keep
```

Without `AEGIS_KNOX_HOME` the Knox door is reported as skipped, never as passed.

## 4. The engine

In the commands below `$REPO` is the repository root, as in the retained HOW-TO-RUN.md.

The test started one engine process, the way the engine's own launcher script starts it:

Process 1:

```text
java \
  --add-opens=java.base/jdk.internal.access=ALL-UNNAMED \
  --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
  --add-opens=java.base/sun.util.calendar=ALL-UNNAMED \
  --add-opens=java.management/com.sun.jmx.mbeanserver=ALL-UNNAMED \
  --add-opens=jdk.internal.jvmstat/sun.jvmstat.monitor=ALL-UNNAMED \
  --add-opens=java.base/sun.reflect.generics.reflectiveObjects=ALL-UNNAMED \
  --add-opens=jdk.management/com.sun.management.internal=ALL-UNNAMED \
  --add-opens=java.base/java.io=ALL-UNNAMED \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/java.net=ALL-UNNAMED \
  --add-opens=java.base/java.util=ALL-UNNAMED \
  --add-opens=java.base/java.util.concurrent=ALL-UNNAMED \
  --add-opens=java.base/java.util.concurrent.locks=ALL-UNNAMED \
  --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --add-opens=java.base/java.lang.invoke=ALL-UNNAMED \
  --add-opens=java.base/java.math=ALL-UNNAMED \
  --add-opens=java.sql/java.sql=ALL-UNNAMED \
  --add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
  --add-opens=java.base/java.time=ALL-UNNAMED \
  --add-opens=java.base/java.text=ALL-UNNAMED \
  --add-opens=java.management/sun.management=ALL-UNNAMED \
  --add-opens=java.desktop/java.awt.font=ALL-UNNAMED \
  -Xms256m \
  -Xmx1g \
  -DIGNITE_HOME=$REPO/examples/aegis-validation/target/ignite-server \
  -DIGNITE_JETTY_HOST=127.0.0.1 \
  -DIGNITE_JETTY_PORT=61822 \
  -DIGNITE_UPDATE_NOTIFIER=false \
  -DIGNITE_NO_ASCII=true \
  -cp \
  "$(cat "$REPO/examples/aegis-validation-ignite-server/target/server.classpath")" \
  org.apache.ignite.startup.cmdline.CommandLineStartup \
  engine/aegis-ignite.xml
```

`$REPO/examples/aegis-validation-ignite-server/target/server.classpath` is written by the `-Preal-engines` build of that module: the engine's jars, at the version its `pom.xml` names, as Maven resolved them. With a binary distribution, use its own `lib/` instead.

The engine's own configuration file, as the test wrote it: [`engine/aegis-ignite.xml`](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/aegis-2.0-review-20261009/examples/adopters/ignite/engine/aegis-ignite.xml).


The data the scenario works on (topics, tables, queues, jobs) is created by the test's `@BeforeAll` method against the engine's own API, outside Aegis; see the test source.

## 5. Flavor A: without Knox

The file: [`aegis/own-host.yaml`](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/aegis-2.0-review-20261009/examples/adopters/ignite/aegis/own-host.yaml), exactly what this door loaded: the shipped adopter profile with the engine address and egress network substituted, plus a `host:` section (loopback listener on a free port, one static bearer token per person, read from the environment).

Environment the gateway reads:

- `AEGIS_SIGNING_KEY`: approval signing key, at least 32 bytes
- `AEGIS_VALIDATION_TOKEN_APPROVER`: bearer token for that persona, at least 16 characters
- `AEGIS_VALIDATION_TOKEN_OPERATOR`: bearer token for that persona, at least 16 characters
- `AEGIS_VALIDATION_TOKEN_OUTSIDER`: bearer token for that persona, at least 16 characters
- `AEGIS_VALIDATION_TOKEN_READER`: bearer token for that persona, at least 16 characters

Start it from the repository root after `mvn -B -Preal-engines -DskipTests install`:

```bash
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar \
  --config examples/adopters/ignite/aegis/own-host.yaml
```

The gateway logs `Aegis gateway listening on http://127.0.0.1:<port>/mcp`; `port: 0` in the file means any free port. The admin API is `http://127.0.0.1:<port>/aegis/v1/`. The engine address in the file is the port the test picked for its engine; change it to yours.

A request, the same as the test's first one (`ignite_get_version` as `guest`):

```bash
AEGIS_PORT=8080
curl -s http://127.0.0.1:${AEGIS_PORT}/mcp \
  -H "Authorization: Bearer $AEGIS_VALIDATION_TOKEN_READER" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -H 'MCP-Protocol-Version: 2026-07-28' -H 'Mcp-Method: tools/call' -H 'Mcp-Name: ignite_get_version' \
  -d '{"method":"tools/call","id":1,"jsonrpc":"2.0","params":{"name":"ignite_get_version","arguments":{},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}'
```

Approvals use the admin API on the same door: `POST <admin>/approvals/<id>/approve` as an approver, then `GET <admin>/approvals/<id>` as the requester returns the single-use token. A call carries Aegis controls in `params._meta`: `io.github.vaquarkhan.aegis/dryRun`, `.../approvalToken` and `.../dryRunReceipt`.

For an IDE you want a fixed port: in your copy of `own-host.yaml` set `host.listen.port: 8080`, and point the engine address at your engine. Nothing else changes.

## 6. Flavor B: with Knox

The manual commands in this section are POSIX shell commands. On Windows, use the supported `examples/adopters/run-example.ps1 ignite -Keep` runner from section 3.
The files: [`aegis/knox-aegis.yaml`](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/aegis-2.0-review-20261009/examples/adopters/ignite/aegis/knox-aegis.yaml), the same adopter profile with no `host:` section (Knox is the host), and [`aegis/knox-topology.xml`](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/aegis-2.0-review-20261009/examples/adopters/ignite/aegis/knox-topology.xml), the Knox topology: ShiroProvider against Knox's demo LDAP, Default identity assertion with `group.principal.mapping` (person to adopter groups), `AclsAuthz` on the route, and the `AEGIS_MCP` service.

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
mkdir -p conf/aegis && cp $REPO/examples/adopters/ignite/aegis/knox-aegis.yaml conf/aegis/ignite.yaml
cp $REPO/examples/adopters/ignite/aegis/knox-topology.xml conf/topologies/aegis-ignite.xml   # then replace ${KNOX_HOME} in it
# 5. Knox's demo LDAP (port 33389) and the gateway
java -jar bin/ldap.jar conf &
java -jar bin/gateway.jar
```

Environment for the gateway process (the Aegis configuration reads it):

- `AEGIS_SIGNING_KEY`: approval signing key, at least 32 bytes

The route: `https://localhost:<gateway.port>/gateway/aegis-ignite/aegis-mcp/v1/mcp`; the admin API is `.../aegis-mcp/v1/admin/`. Knox demo LDAP users are in `conf/users.ldif`; supply that installation's demo credentials through your terminal or IDE, never in a committed configuration.

A request, the same as the test's first one (`ignite_get_version` as `guest`):

```bash
KNOX_PORT=8443
java -jar "$AEGIS_KNOX_HOME/bin/knoxcli.jar" export-cert --type PEM
KNOX_CA_CERT="$PWD/gateway-client-trust.pem"
read -r -p 'Knox demo user: ' KNOX_USER
# curl prompts for the password because the user value contains no colon.
curl -sS --cacert "$KNOX_CA_CERT" https://localhost:${KNOX_PORT}/gateway/aegis-ignite/aegis-mcp/v1/mcp \
  --user "$KNOX_USER" \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -H 'MCP-Protocol-Version: 2026-07-28' -H 'Mcp-Method: tools/call' -H 'Mcp-Name: ignite_get_version' \
  -d '{"method":"tools/call","id":1,"jsonrpc":"2.0","params":{"name":"ignite_get_version","arguments":{},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}'
```

Approvals use the admin API on the same door: `POST <admin>/approvals/<id>/approve` as an approver, then `GET <admin>/approvals/<id>` as the requester returns the single-use token. A call carries Aegis controls in `params._meta`: `io.github.vaquarkhan.aegis/dryRun`, `.../approvalToken` and `.../dryRunReceipt`.

The curl example exports Knox's certificate and supplies it with `--cacert`; do not replace this with `-k`. Verify the exported certificate through a trusted channel before using it. Curl prompts for the Knox password because `--user` contains only the user name, keeping the password out of shell history and process arguments.

For an IDE, set `gateway.port` to 8443 in `conf/gateway-site.xml`.

## 7. Connect your IDE

Ready-made files, in each IDE's documented format, are in [`adopters/tutorials/ignite/ide/`](https://github.com/vaquarkhan/aegis-mcp-gateway/tree/aegis-2.0-review-20261009/adopters/tutorials/ignite/ide):

| IDE | File | Door |
| --- | --- | --- |
| VS Code | `ide/vscode/mcp.json` (copy to `.vscode/mcp.json`) | own host, bearer token prompted |
| VS Code | `ide/vscode/mcp.knox.json` | Knox, HTTP Basic prompted |
| Kiro | `ide/kiro/mcp.json` (copy to `.kiro/settings/mcp.json`) | own host enabled, Knox disabled |
| Cursor | `ide/cursor/mcp.json` (copy to `.cursor/mcp.json`) | own host |

Own host: `http://127.0.0.1:8080/mcp`. Knox: `https://localhost:8443/gateway/aegis-ignite/aegis-mcp/v1/mcp`; trust Knox's certificate first (`java -jar bin/knoxcli.jar export-cert --type PEM`). The token you give the IDE decides who you are to Aegis: use the token of the person whose permissions you want the assistant to have.

These files were checked by `DocumentationAssetsTest` (they parse and name these endpoints). The MCP transport they use was exercised with the official MCP Python SDK in the [Spark and Iceberg tutorial](../TUTORIAL-SPARK-ICEBERG.md); the IDEs themselves were not driven by a test.

## 8. Prompts to try

Ask the assistant in plain language; it picks the tool. What Aegis answered for the same call in the kept own-host run is in the last column.

| Prompt | Tool | Class | What Aegis answered, by person |
| --- | --- | --- | --- |
| Use aegis-ignite to get version | `ignite_get_version` | `READ` | `guest`, read by READER: `ALLOWED`; `admin`, same read by an outsider: `DENIED:POLICY_DENIED:step3` |
| Use aegis-ignite to list caches | `ignite_list_caches` | `READ` | `guest`, read by READER: `ALLOWED`; `admin`, same read by an outsider: `DENIED:POLICY_DENIED:step3` |
| Use aegis-ignite to destroy cache with cache `scratch_ownhost` | `ignite_destroy_cache` | `DESTRUCTIVE` | `guest`, destructive by READER: `DENIED:READONLY_CALLER:step1`; `sam`, destructive without approval: `DENIED:APPROVAL_REQUIRED:step4`; `sam`, dry run: `ALLOWED`; `sam`, execute with approval and receipt: `ALLOWED`; `sam`, replay of the same approval: `DENIED:APPROVAL_REQUIRED:step4` |

A `DESTRUCTIVE` call takes four turns, all visible in section 9: the call is denied with an approval request id; a dry run returns a receipt; a different person approves in the admin API; the call with the token and receipt runs once, and a replay is refused.

## 9. What the run showed

### Without Knox

| # | Step | Caller | Request | Result |
| --- | --- | --- | --- | --- |
| 1 | read by READER | `guest` | `tools/call ignite_get_version {}` | HTTP 200, `ALLOWED` |
| 2 | same read by an outsider | `admin` | `tools/call ignite_get_version {}` | HTTP 200, `DENIED:POLICY_DENIED:step3` |
| 3 | read by READER | `guest` | `tools/call ignite_list_caches {}` | HTTP 200, `ALLOWED` |
| 4 | same read by an outsider | `admin` | `tools/call ignite_list_caches {}` | HTTP 200, `DENIED:POLICY_DENIED:step3` |
| 5 | destructive by READER | `guest` | `tools/call ignite_destroy_cache {"cache":"scratch_ownhost"}` | HTTP 200, `DENIED:READONLY_CALLER:step1` |
| 6 | destructive without approval | `sam` | `tools/call ignite_destroy_cache {"cache":"scratch_ownhost"}` | HTTP 200, `DENIED:APPROVAL_REQUIRED:step4` |
| 7 | dry run | `sam` | `tools/call ignite_destroy_cache {"cache":"scratch_ownhost"}` | HTTP 200, `ALLOWED` |
| 8 | requester approves own request | `sam` | `POST approvals/apr_bfe0945fcb3c21d3ec34bf94a24fcc34/approve` | HTTP 403 |
| 9 | independent approval | `tom` | `POST approvals/apr_bfe0945fcb3c21d3ec34bf94a24fcc34/approve` | HTTP 200 |
| 10 | requester collects the token | `sam` | `GET approvals/apr_bfe0945fcb3c21d3ec34bf94a24fcc34` | HTTP 200 |
| 11 | execute with approval and receipt | `sam` | `tools/call ignite_destroy_cache {"cache":"scratch_ownhost"}` | HTTP 200, `ALLOWED` |
| 12 | replay of the same approval | `sam` | `tools/call ignite_destroy_cache {"cache":"scratch_ownhost"}` | HTTP 200, `DENIED:APPROVAL_REQUIRED:step4` |

### With Knox

| # | Step | Caller | Request | Result |
| --- | --- | --- | --- | --- |
| 1 | read by READER | `guest` | `tools/call ignite_get_version {}` | HTTP 200, `ALLOWED` |
| 2 | same read by an outsider | `admin` | `tools/call ignite_get_version {}` | HTTP 403 |
| 3 | read by READER | `guest` | `tools/call ignite_list_caches {}` | HTTP 200, `ALLOWED` |
| 4 | same read by an outsider | `admin` | `tools/call ignite_list_caches {}` | HTTP 403 |
| 5 | destructive by READER | `guest` | `tools/call ignite_destroy_cache {"cache":"scratch_knox"}` | HTTP 200, `DENIED:READONLY_CALLER:step1` |
| 6 | destructive without approval | `sam` | `tools/call ignite_destroy_cache {"cache":"scratch_knox"}` | HTTP 200, `DENIED:APPROVAL_REQUIRED:step4` |
| 7 | dry run | `sam` | `tools/call ignite_destroy_cache {"cache":"scratch_knox"}` | HTTP 200, `ALLOWED` |
| 8 | requester approves own request | `sam` | `POST approvals/apr_7257b59e49de571bb582ff0f4b89dda9/approve` | HTTP 403 |
| 9 | independent approval | `tom` | `POST approvals/apr_7257b59e49de571bb582ff0f4b89dda9/approve` | HTTP 200 |
| 10 | requester collects the token | `sam` | `GET approvals/apr_7257b59e49de571bb582ff0f4b89dda9` | HTTP 200 |
| 11 | execute with approval and receipt | `sam` | `tools/call ignite_destroy_cache {"cache":"scratch_knox"}` | HTTP 200, `ALLOWED` |
| 12 | replay of the same approval | `sam` | `tools/call ignite_destroy_cache {"cache":"scratch_knox"}` | HTTP 200, `DENIED:APPROVAL_REQUIRED:step4` |

Full requests and responses: [own host](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/aegis-2.0-review-20261009/examples/adopters/ignite/results/own-host-transcript.log), [Knox](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/aegis-2.0-review-20261009/examples/adopters/ignite/results/knox-transcript.log).

## 10. Audit and clean up

Every decision, approval and result is in the hash-chained journal. Check a kept one:

```bash
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar \
  --verify-audit examples/adopters/ignite/results/own-host-audit.jsonl
```

In your own deployment, add `audit.checkpoint` so the head is exported to storage the journal writer cannot rewrite. Verify it with `--verify-audit` plus your journal and checkpoint file paths.

When you ran the full scenario, the test teardown stopped the processes and containers it started. For a manual run, stop the foreground Aegis, Knox, and engine processes with Ctrl+C in their terminals, then stop any background process you started from section 4.
Remove temporary configuration copies, exported certificates, logs, and audit journals only after retaining any evidence your review or incident process requires.

Limits of this run are listed in [the example README](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/aegis-2.0-review-20261009/examples/adopters/ignite/README.md).
