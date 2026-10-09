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
# Apache Spark Connect example: with Knox and without Knox

This example runs the shipped profile
[`adopters/configurations/spark-connect/spark-connect.yaml`](../../../adopters/configurations/spark-connect/spark-connect.yaml)
against a real Apache Spark 4.0.1 Spark Connect server in its own JVM, twice: once behind Aegis's own
server and once inside a stock Apache Knox 3.0.0 gateway. Only the server address and the egress
network are substituted.

Status: **verified on this tree**, both front doors. The kept logs are in [results/](results/).

## Files in this folder

| File | What it is |
| --- | --- |
| [`HOW-TO-RUN.md`](HOW-TO-RUN.md) | written by the test harness during the kept run: the exact command line each engine was started with, how each front door was started with the files below, a sample `curl` request, and every request the test sent with its result. Start here to repeat the run by hand |
| [`aegis/own-host.yaml`](aegis/own-host.yaml) | the Aegis configuration the own-host door loaded: the shipped profile with the engine address substituted, plus a `host:` section |
| [`aegis/knox-aegis.yaml`](aegis/knox-aegis.yaml) | the Aegis configuration loaded inside Knox (no `host:` section) |
| [`aegis/knox-topology.xml`](aegis/knox-topology.xml) | the Knox topology: demo LDAP authentication, group mapping, `AclsAuthz`, and the `AEGIS_MCP` service |
| [`results/`](results/) | the kept transcripts, Aegis audit journals and Surefire report of that run |
| [`SparkConnectFrontDoorsTest.java`](../../aegis-validation-spark-connect/src/test/java/io/github/vaquarkhan/aegis/validation/SparkConnectFrontDoorsTest.java) | the test itself: how the engine is started and seeded, and every assertion |

## How the run works

1. `mvn -B -Preal-engines -DskipTests install` resolves the engine (the `examples/aegis-validation-*-server` module for this adopter, or a distribution named in this README) and builds the Aegis jars, including the Knox bundle.
2. `SparkConnectFrontDoorsTest` starts the real engine in its own process, as section 1 of `HOW-TO-RUN.md` shows, and creates the data the scenario works on through the engine's own API, outside Aegis.
3. Without Knox: the test builds Aegis from `aegis/own-host.yaml`, starts Aegis's own server on loopback, and sends MCP `tools/call` requests over HTTP as each person, with that person's bearer token.
4. With Knox: the test copies the Aegis bundle into a stock Knox 3.0.0 `ext/`, writes `aegis/knox-topology.xml` to `conf/topologies/`, starts Knox's demo LDAP and the gateway, and sends the same requests with HTTP Basic as the same people. Knox authenticates and checks the route ACL; Aegis governs the MCP call.
5. After every governed write, the test reads the engine directly to check what really changed. Each request and response goes to the transcript, and the Aegis audit journal is copied next to it.

## What is different from the Flink example

Spark Connect is gRPC, not REST, so Aegis governs it through a typed source plugin
(`type: spark-connect`, module `examples/aegis-spark-connect`) that uses the official Spark Connect JVM
client. Spark never runs inside the gateway. That plugin has to be on the gateway's classpath:

| Front door | How the plugin is installed |
| --- | --- |
| Without Knox | on the classpath of the Aegis process (the ready-made `examples/aegis-gateway-spark` jar bundles it) |
| With Knox | copied into Knox's `ext/` next to the Aegis bundle: `aegis-spark-connect-2.0.0-SNAPSHOT.jar` plus its runtime dependencies (`examples/aegis-spark-connect/target/plugin.classpath`, written by `-Preal-engines`) |

The Knox installation follows one rule an operator should also follow: Knox keeps its own copy of any
library it already ships (Jackson, commons, SnakeYAML), and no logging binding is added, so Knox's own
logging is untouched. The generated `examples/aegis-spark-connect/target/plugin.classpath` is the
reproducible source of the plugin jar list for an operator run. Knox is started with the Java 17
`--add-opens` options Spark documents, because the client uses Apache Arrow.

## What the test proves

`SparkConnectFrontDoorsTest` (module `examples/aegis-validation-spark-connect`) starts
`org.apache.spark.sql.connect.service.SparkConnectServer`, creates the global temporary views `orders`
and one scratch view per front door, and then runs through each door:

| Step | Caller | Call | Own host | Knox |
| --- | --- | --- | --- | --- |
| 1 | `guest` (reader) | `spark_list_tables`, `spark_preview_table` | allowed; real rows from `orders` | allowed; real rows |
| 2 | `admin` (outsider) | the same reads | Aegis denies: `POLICY_DENIED` | Knox `AclsAuthz`: HTTP 403 |
| 3 | `guest` | `spark_drop_table` | `READONLY_CALLER` | `READONLY_CALLER` |
| 4 | `sam` (operator) | `spark_drop_table` | `APPROVAL_REQUIRED`; view still exists | same |
| 5 | `sam` | dry run (`TOOL` mode: runs `spark_describe_table`, drops nothing) | receipt and approval request; view still exists | same |
| 6 | `sam` | approves own request | HTTP 403 | same |
| 7 | `tom` (approver) | approves | HTTP 200 | same |
| 8 | `sam` | drop with token and receipt | allowed; Spark no longer lists the view | same |
| 9 | `sam` | same token again | denied: single use | same |

The view's existence is checked with a separate Spark Connect client session, outside Aegis, so the
assertions are about Spark's state rather than about what Aegis reported.

Personas and groups (Knox demo LDAP accounts):

| Person | Groups in this profile |
| --- | --- |
| `guest` | `spark-users` |
| `sam` | `spark-operators` |
| `tom` | `spark-approvers` |
| `admin` | none, and outside the Knox ACL |

## Run it

```powershell
../knox/install-knox.ps1 -Destination C:\knox
$env:AEGIS_KNOX_HOME = 'C:\knox\knox-3.0.0'
../run-example.ps1 spark-connect -Keep
```

```bash
../knox/install-knox.sh /opt/knox
export AEGIS_KNOX_HOME=/opt/knox/knox-3.0.0
../run-example.sh spark-connect --keep
```

The first run downloads the Spark 4.0.1 server, MLlib and client from Maven Central. Without
`AEGIS_KNOX_HOME` the Knox run is reported as skipped, never as passed.

## Results

| File | What it is |
| --- | --- |
| `results/own-host-transcript.log`, `results/knox-transcript.log` | every request and response, per front door |
| `results/own-host-audit.jsonl`, `results/knox-audit.jsonl` | the Aegis journals; verify with `--verify-audit` |
| `results/test-report.txt` | the Surefire summary |

## Limits

- The server runs `local[2]` in one JVM. It is the real Spark Connect server, not a cluster.
- The drop is exercised on a global temporary view, so the run leaves nothing behind. Dropping a
  catalog table goes through the same call; the profile's `no-prod-drop` rule refuses any
  `prod.*` table for everyone.
- `spark_sql` (arbitrary SQL, `DESTRUCTIVE`) is not exercised here; its approval path is the same and
  its preview is `spark_explain_sql`. See [TUTORIAL-SPARK-AND-SPARK-CONNECT.md](../../../docs/TUTORIAL-SPARK-AND-SPARK-CONNECT.md).
