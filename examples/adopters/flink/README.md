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
# Apache Flink example: with Knox and without Knox

This example runs the shipped profile
[`adopters/configurations/flink/flink.yaml`](../../../adopters/configurations/flink/flink.yaml)
against a real Apache Flink 1.20.1 cluster, twice: once behind Aegis's own server and once inside a
stock Apache Knox 3.0.0 gateway. Nothing in the YAML changes between the two runs except the JobManager
address and the egress network. The own-host run appends a `host:` section; the Knox run uses a topology.

Status: **verified on this tree**, both front doors. The kept logs are in [results/](results/).

## Files in this folder

| File | What it is |
| --- | --- |
| [`HOW-TO-RUN.md`](HOW-TO-RUN.md) | written by the test harness during the kept run: the exact command line each engine was started with, how each front door was started with the files below, a sample `curl` request, and every request the test sent with its result. Start here to repeat the run by hand |
| [`aegis/own-host.yaml`](aegis/own-host.yaml) | the Aegis configuration the own-host door loaded: the shipped profile with the engine address substituted, plus a `host:` section |
| [`aegis/knox-aegis.yaml`](aegis/knox-aegis.yaml) | the Aegis configuration loaded inside Knox (no `host:` section) |
| [`aegis/knox-topology.xml`](aegis/knox-topology.xml) | the Knox topology: demo LDAP authentication, group mapping, `AclsAuthz`, and the `AEGIS_MCP` service |
| [`results/`](results/) | the kept transcripts, Aegis audit journals and Surefire report of that run |
| [`FlinkFrontDoorsTest.java`](../../aegis-validation/src/test/java/io/github/vaquarkhan/aegis/validation/FlinkFrontDoorsTest.java) | the test itself: how the engine is started and seeded, and every assertion |

## How the run works

1. `mvn -B -Preal-engines -DskipTests install` resolves the engine (the `examples/aegis-validation-*-server` module for this adopter, or a distribution named in this README) and builds the Aegis jars, including the Knox bundle.
2. `FlinkFrontDoorsTest` starts the real engine in its own process, as section 1 of `HOW-TO-RUN.md` shows, and creates the data the scenario works on through the engine's own API, outside Aegis.
3. Without Knox: the test builds Aegis from `aegis/own-host.yaml`, starts Aegis's own server on loopback, and sends MCP `tools/call` requests over HTTP as each person, with that person's bearer token.
4. With Knox: the test copies the Aegis bundle into a stock Knox 3.0.0 `ext/`, writes `aegis/knox-topology.xml` to `conf/topologies/`, starts Knox's demo LDAP and the gateway, and sends the same requests with HTTP Basic as the same people. Knox authenticates and checks the route ACL; Aegis governs the MCP call.
5. After every governed write, the test reads the engine directly to check what really changed. Each request and response goes to the transcript, and the Aegis audit journal is copied next to it.

## What the test proves

`FlinkFrontDoorsTest` starts a Flink MiniCluster with its real REST endpoint, submits a job that runs
until cancelled, and then makes the same calls through each front door:

| Step | Caller | Call | Own host | Knox |
| --- | --- | --- | --- | --- |
| 1 | `guest` (reader) | `flink_list_jobs` | allowed, lists the real job | allowed, lists the real job |
| 2 | `admin` (outsider) | `flink_list_jobs` | Aegis denies: `POLICY_DENIED` | Knox `AclsAuthz` refuses the route: HTTP 403 |
| 3 | `guest` | `flink_cancel_job` | `READONLY_CALLER` | `READONLY_CALLER` |
| 4 | `sam` (operator) | `flink_cancel_job` | `APPROVAL_REQUIRED`, job still RUNNING | same |
| 5 | `sam` | dry run | receipt and approval request issued, job still RUNNING | same |
| 6 | `sam` | approves own request | HTTP 403 (separation of duties) | same |
| 7 | `tom` (approver) | approves | HTTP 200 | same |
| 8 | `sam` | cancel with token and receipt | allowed; Flink reports the job **CANCELED** | same |
| 9 | `sam` | same token again | denied: single use | same |

Step 2 and step 3 together are the point of the Knox comparison. Knox decides who may reach
`/gateway/<topology>/aegis-mcp/v1/mcp`. Every MCP tool call is a `POST` to that one URL, with the tool
name and arguments in the JSON-RPC body, so the route rule cannot tell `flink_list_jobs` from
`flink_cancel_job`. Aegis reads the body and decides the tool, the job resource and the approval.
Without that, a user Knox admits could make the gateway cancel jobs with the gateway's own Flink
credential: the confused-deputy case. `aegis-knox3-extension`'s `KnoxAclRouteScopeTest` proves the
same property with the real Knox ACL filter at unit level.

## The two front doors

| | Without Knox (`own-host`) | With Knox (`knox`) |
| --- | --- | --- |
| Server | `aegis-gateway` (`AegisServer`) on loopback HTTP | Knox 3.0.0 `bin/gateway.jar`, HTTPS with a `localhost` certificate from `knoxcli create-cert` |
| Authentication | static bearer tokens, one per person | ShiroProvider against Knox's demo LDAP, HTTP Basic |
| Groups | `identity.static.tokens[].groups` | Default identity assertion, `group.principal.mapping` |
| Route admission | Aegis route groups (not used here) | `AclsAuthz`: `AEGIS_MCP.acl = guest,sam,tom;*;*` |
| Tool, resource, approval, audit | Aegis | Aegis (the same engine, from `ext/aegis-knox3-extension-*-knox-bundle.jar`) |

The people are Knox's demo LDAP accounts, so both runs use the same names:

| Person | Groups in this profile |
| --- | --- |
| `guest` | `flink-users` |
| `sam` | `flink-operators` |
| `tom` | `flink-approvers` |
| `admin` | none, and outside the Knox ACL |

## Run it

Requirements: JDK 17+, Maven 3.9+, network access to Maven Central the first time (Flink is downloaded
as a test dependency). For the Knox run, an unpacked Knox 3.0.0:

```powershell
../knox/install-knox.ps1 -Destination C:\knox           # verifies the published SHA-512
$env:AEGIS_KNOX_HOME = 'C:\knox\knox-3.0.0'
../run-example.ps1 flink -Keep                         # both front doors; -Keep refreshes results/
```

```bash
../knox/install-knox.sh /opt/knox
export AEGIS_KNOX_HOME=/opt/knox/knox-3.0.0
../run-example.sh flink --keep
```

Without `AEGIS_KNOX_HOME` the Knox run is reported as **skipped**, never as passed. The harness writes
`conf/topologies/aegis-flink.xml`, copies the Aegis bundle into `ext/`, sets `gateway.port` to a free
port and `gateway.host` to `127.0.0.1` in `conf/gateway-site.xml`, creates a throwaway master secret
if none exists, and stops Knox and its demo LDAP afterwards. Use a dedicated Knox copy for this.

## Results

| File | What it is |
| --- | --- |
| `results/own-host-transcript.log` | every HTTP request and response through Aegis's own server |
| `results/knox-transcript.log` | the same steps through Knox |
| `results/own-host-audit.jsonl`, `results/knox-audit.jsonl` | the Aegis hash-chained journals of those runs; check with `java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar --verify-audit <file>` |
| `results/test-report.txt` | the Surefire summary of the run |

Approval tokens and dry-run receipts are bearer secrets; the transcripts show `<redacted>` in their
place. Job ids and approval request ids are real values from the run.

## Limits

- The JobManager is a MiniCluster in the test JVM. It serves Flink's real REST API, but it is not a
  multi-host session cluster; [TUTORIAL-FLINK.md](../../../docs/TUTORIAL-FLINK.md) shows a session
  cluster run by hand.
- The Knox run uses the demo LDAP and a self-issued certificate. A production topology uses your
  directory, your certificate and your ACLs; the Aegis side does not change.
- This run covers `flink_list_jobs` and `flink_cancel_job`. The profile's other tools are covered by
  the configuration and contract tests in `examples/aegis-examples`.
