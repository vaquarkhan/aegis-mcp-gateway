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
# Apache Calcite example: with Knox and without Knox

This example runs the shipped profile
[`adopters/configurations/adapters/calcite/calcite.yaml`](../../../adopters/configurations/adapters/calcite/calcite.yaml)
against real Apache Calcite 1.42.0 behind the Apache Calcite Avatica 1.28.0 standalone server, through
Aegis's own server and inside a stock Apache Knox 3.0.0 gateway. Only the Avatica address and the egress
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
| [`CalciteFrontDoorsTest.java`](../../aegis-validation/src/test/java/io/github/vaquarkhan/aegis/validation/CalciteFrontDoorsTest.java) | the test itself: how the engine is started and seeded, and every assertion |

## How the run works

1. `mvn -B -Preal-engines -DskipTests install` resolves the engine (the `examples/aegis-validation-*-server` module for this adopter, or a distribution named in this README) and builds the Aegis jars, including the Knox bundle.
2. `CalciteFrontDoorsTest` starts the real engine in its own process, as section 1 of `HOW-TO-RUN.md` shows, and creates the data the scenario works on through the engine's own API, outside Aegis.
3. Without Knox: the test builds Aegis from `aegis/own-host.yaml`, starts Aegis's own server on loopback, and sends MCP `tools/call` requests over HTTP as each person, with that person's bearer token.
4. With Knox: the test copies the Aegis bundle into a stock Knox 3.0.0 `ext/`, writes `aegis/knox-topology.xml` to `conf/topologies/`, starts Knox's demo LDAP and the gateway, and sends the same requests with HTTP Basic as the same people. Knox authenticates and checks the route ACL; Aegis governs the MCP call.
5. After every governed write, the test reads the engine directly to check what really changed. Each request and response goes to the transcript, and the Aegis audit journal is copied next to it.

## What runs

Calcite is a library, so something has to serve it. Here that is
`org.apache.calcite.avatica.standalone.StandaloneServer` in its own JVM, wrapping the JDBC URL
`jdbc:calcite:model=...`. The model gives Calcite one schema, `SALES`, from Calcite's file adapter over a
CSV file the test writes (`ORDERS`, three rows). Its classpath comes from
`examples/aegis-validation-calcite-server`; Avatica 1.28.0 is the release calcite-core 1.42.0 is built
against.

The profile reaches it with `type: jdbc` (module `aegis-source-jdbc`) and Avatica's own remote driver,
`org.apache.calcite.avatica.remote.Driver`. Aegis ships no JDBC driver, so the operator adds it:

| Front door | What the operator adds |
| --- | --- |
| Without Knox | `aegis-source-jdbc` and the Avatica client jar (`org.apache.calcite.avatica:avatica:1.28.0`) on the gateway classpath |
| With Knox | the same two jars in Knox's `ext/` next to the Aegis bundle |

## What the test proves

The profile is read-only by design: it offers no write tool at all, and binds the `sql-readonly` hook to
`jdbc_run_query` in `ENFORCE` mode. So the story here is not an approval flow but a refusal:

| Step | Caller | Call | Own host | Knox |
| --- | --- | --- | --- | --- |
| 1 | `guest` (reader) | `jdbc_list_tables` on `SALES` | allowed; Calcite lists `ORDERS` | allowed |
| 2 | `guest` | `jdbc_run_query`: `SELECT ITEM, PRICE ... WHERE PRICE > 5` | allowed; Calcite returns `widget` and `gadget` | allowed |
| 3 | `admin` (outsider) | the same reads | Aegis denies: `POLICY_DENIED` | Knox `AclsAuthz`: HTTP 403 |
| 4 | `sam` (operator) | `jdbc_run_query`: `DELETE FROM SALES.ORDERS` | `INVALID_INPUT` at step 8 (the SQL guard) | same |
| 5 | `sam` | `jdbc_run_query`: `/* report */ DROP TABLE SALES.ORDERS` | `INVALID_INPUT` at step 8 | same |

After steps 4 and 5 the test asks Calcite directly, over its own Avatica connection, and `ORDERS` still has
three rows. Calcite's file adapter cannot be written to anyway, so the row count proves the call was
refused before it reached Calcite, which is what the decision code says, rather than proving a write
would otherwise have succeeded. The guard is an indicator, not the control: the profile's own comment says
the real control is a read-only database account.

| Person | Groups in this profile |
| --- | --- |
| `guest` | `calcite-users` |
| `sam` | `calcite-operators` |
| `tom` | `calcite-approvers` (unused: the profile has nothing to approve) |
| `admin` | none, and outside the Knox ACL |

## Run it

```powershell
$env:AEGIS_KNOX_HOME = 'C:\knox\knox-3.0.0'     # from ../knox/install-knox.ps1
../run-example.ps1 calcite -Keep
```

```bash
export AEGIS_KNOX_HOME=/opt/knox/knox-3.0.0     # from ../knox/install-knox.sh
../run-example.sh calcite --keep
```

## Results

| File | What it is |
| --- | --- |
| `results/own-host-transcript.log`, `results/knox-transcript.log` | every request and response, including the real rows Calcite returned |
| `results/own-host-audit.jsonl`, `results/knox-audit.jsonl` | the Aegis journals; verify with `--verify-audit` |
| `results/knox-ext-plugins.log` | the jars added to Knox `ext/` |
| `results/test-report.txt` | the Surefire summary |

## Limits and one finding

- Calcite here serves CSV files. In production Avatica usually fronts another engine (Apache Druid serves
  it at `/druid/v2/sql/avatica/`); the profile and the governance are the same.
- Finding: plugin source factories (`jdbc`, `zookeeper`, `spark-connect`, `kafka-admin`) read their
  `...Env` secrets from the process environment directly, not through the gateway's configured
  environment lookup. That is correct for a deployed process, and it is why the Knox run needs nothing
  special. The own-host run happens inside the test JVM, so it uses the factory's test seam
  (`JdbcToolSourceFactory.useEnvForTesting`). Routing plugin secrets through the gateway's lookup would
  be an SPI change and is recorded in [ROADMAP.md](../../../docs/ROADMAP.md).
