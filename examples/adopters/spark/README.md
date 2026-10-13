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
# Apache Spark example: with Knox and without Knox

This example runs the shipped profile
[`adopters/configurations/spark/spark.yaml`](../../../adopters/configurations/spark/spark.yaml) against
two real Apache Spark 4.0.1 services, through Aegis's own server and inside a stock Apache Knox 3.0.0
gateway. The profile is plain REST (`type: http`), so no plugin is needed in either front door.

Status: **retained historical run**, both front doors. The kept logs are in [results/](results/).

## Files in this folder

| File | What it is |
| --- | --- |
| [`HOW-TO-RUN.md`](HOW-TO-RUN.md) | written by the test harness during the kept run: the exact command line each engine was started with, how each front door was started with the files below, a sample `curl` request, and every request the test sent with its result. Start here to repeat the run by hand |
| [`aegis/own-host.yaml`](aegis/own-host.yaml) | the Aegis configuration the own-host door loaded: the shipped profile with the engine address substituted, plus a `host:` section |
| [`aegis/knox-aegis.yaml`](aegis/knox-aegis.yaml) | the Aegis configuration loaded inside Knox (no `host:` section) |
| [`aegis/knox-topology.xml`](aegis/knox-topology.xml) | the Knox topology: demo LDAP authentication, group mapping, `AclsAuthz`, and the `AEGIS_MCP` service |
| [`results/`](results/) | the kept transcripts, Aegis audit journals and Surefire report of that run |
| [`SparkFrontDoorsTest.java`](../../aegis-validation/src/test/java/io/github/vaquarkhan/aegis/validation/SparkFrontDoorsTest.java) | the test itself: how the engine is started and seeded, and every assertion |

## How the run works

1. `mvn -B -Preal-engines -DskipTests install` resolves the engine (the `examples/aegis-validation-*-server` module for this adopter, or a distribution named in this README) and builds the Aegis jars, including the Knox bundle.
2. `SparkFrontDoorsTest` starts the real engine in its own process, as section 1 of `HOW-TO-RUN.md` shows, and creates the data the scenario works on through the engine's own API, outside Aegis.
3. Without Knox: the test builds Aegis from `aegis/own-host.yaml`, starts Aegis's own server on loopback, and sends MCP `tools/call` requests over HTTP as each person, with that person's bearer token.
4. With Knox: the test copies the Aegis bundle into a stock Knox 3.0.0 `ext/`, writes `aegis/knox-topology.xml` to `conf/topologies/`, starts Knox's demo LDAP and the gateway, and sends the same requests with HTTP Basic as the same people. Knox authenticates and checks the route ACL; Aegis governs the MCP call.
5. After every governed write, the test reads the engine directly to check what really changed. Each request and response goes to the transcript, and the Aegis audit journal is copied next to it.

## The two Spark surfaces

| Profile source | Real Spark service in the test | Used for |
| --- | --- | --- |
| `spark-history` | a running Spark application (`local[1]` SparkContext) whose UI serves the monitoring REST API `/api/v1` | `spark_list_applications` |
| `spark-master` | a standalone `org.apache.spark.deploy.master.Master` with `spark.master.rest.enabled=true` | `spark_submission_status`, `spark_kill_submission` |

The same History Server API is served by a Spark History Server on `:18080` in production; the test uses
a live driver's UI, which serves the identical `/api/v1` routes.

For the kill, the test submits a driver to the master's REST submission server the way
`spark-submit --deploy-mode cluster` does. The master has no worker, so the driver waits in state
`SUBMITTED`. Killing it through Aegis must move it to `KILLED`, which the test reads back from the
master directly, outside Aegis.

## What the test proves

| Step | Caller | Call | Own host | Knox |
| --- | --- | --- | --- | --- |
| 1 | `guest` (reader) | `spark_list_applications`, `spark_submission_status` | allowed; real application and driver | allowed |
| 2 | `admin` (outsider) | the same reads | Aegis denies: `POLICY_DENIED` | Knox `AclsAuthz`: HTTP 403 |
| 3 | `guest` | `spark_kill_submission` | `READONLY_CALLER` | `READONLY_CALLER` |
| 4 | `sam` (operator) | `spark_kill_submission` | `APPROVAL_REQUIRED`; driver still `SUBMITTED` | same |
| 5 | `sam` | dry run (`TOOL` mode: runs `spark_submission_status`) | receipt and approval request; driver still `SUBMITTED` | same |
| 6 | `sam` | approves own request | HTTP 403 | same |
| 7 | `tom` (approver) | approves | HTTP 200 | same |
| 8 | `sam` | kill with token and receipt | allowed; the master reports the driver **KILLED** | same |
| 9 | `sam` | same token again | denied: single use | same |

| Person | Groups in this profile |
| --- | --- |
| `guest` | `spark-users` |
| `sam` | `spark-operators` |
| `tom` | `spark-approvers` |
| `admin` | none, and outside the Knox ACL |

## Run it

```powershell
$env:AEGIS_KNOX_HOME = 'C:\knox\knox-3.0.0'     # from ../knox/install-knox.ps1
../run-example.ps1 spark -Keep
```

```bash
export AEGIS_KNOX_HOME=/opt/knox/knox-3.0.0     # from ../knox/install-knox.sh
../run-example.sh spark --keep
```

Without `AEGIS_KNOX_HOME` the Knox run is reported as skipped, never as passed.

## Results

| File | What it is |
| --- | --- |
| `results/own-host-transcript.log`, `results/knox-transcript.log` | every request and response, per front door |
| `results/own-host-audit.jsonl`, `results/knox-audit.jsonl` | the Aegis journals; verify with `--verify-audit` |
| `results/test-report.txt` | the Surefire summary |

## Limits

- The master runs in the test JVM with no worker, so the killed driver never started. Killing a running
  driver uses the same REST call; on YARN or Kubernetes there is no standalone submission server, so
  leave the `spark-master` source out there (the profile says so).
- `spark_submit_batch` (start a driver from an allow-listed jar) is not exercised; its jar is a typed
  resource the policy allow-lists, as in the Flink example's `flink_run_jar`.
