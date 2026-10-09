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
# Apache Flume example: with Knox and without Knox

This example runs the shipped profile
[`adopters/configurations/adapters/flume/flume.yaml`](../../../adopters/configurations/adapters/flume/flume.yaml)
against a real Apache Flume 1.11.0 agent, through Aegis's own server and inside a stock Apache Knox 3.0.0
gateway. Only the agent address and the egress network are substituted.

Status: **verified on this tree**, both front doors. The kept logs are in [results/](results/).

## Files in this folder

| File | What it is |
| --- | --- |
| [`HOW-TO-RUN.md`](HOW-TO-RUN.md) | written by the test harness during the kept run: the exact command line each engine was started with, how each front door was started with the files below, a sample `curl` request, and every request the test sent with its result. Start here to repeat the run by hand |
| [`aegis/own-host.yaml`](aegis/own-host.yaml) | the Aegis configuration the own-host door loaded: the shipped profile with the engine address substituted, plus a `host:` section |
| [`aegis/knox-aegis.yaml`](aegis/knox-aegis.yaml) | the Aegis configuration loaded inside Knox (no `host:` section) |
| [`aegis/knox-topology.xml`](aegis/knox-topology.xml) | the Knox topology: demo LDAP authentication, group mapping, `AclsAuthz`, and the `AEGIS_MCP` service |
| `engine/` | the engine configuration files the test wrote: [`aegis-agent.properties`](engine/aegis-agent.properties) |
| [`results/`](results/) | the kept transcripts, Aegis audit journals and Surefire report of that run |
| [`FlumeFrontDoorsTest.java`](../../aegis-validation/src/test/java/io/github/vaquarkhan/aegis/validation/FlumeFrontDoorsTest.java) | the test itself: how the engine is started and seeded, and every assertion |

## How the run works

1. `mvn -B -Preal-engines -DskipTests install` resolves the engine (the `examples/aegis-validation-*-server` module for this adopter, or a distribution named in this README) and builds the Aegis jars, including the Knox bundle.
2. `FlumeFrontDoorsTest` starts the real engine in its own process, as section 1 of `HOW-TO-RUN.md` shows, and creates the data the scenario works on through the engine's own API, outside Aegis.
3. Without Knox: the test builds Aegis from `aegis/own-host.yaml`, starts Aegis's own server on loopback, and sends MCP `tools/call` requests over HTTP as each person, with that person's bearer token.
4. With Knox: the test copies the Aegis bundle into a stock Knox 3.0.0 `ext/`, writes `aegis/knox-topology.xml` to `conf/topologies/`, starts Knox's demo LDAP and the gateway, and sends the same requests with HTTP Basic as the same people. Knox authenticates and checks the route ACL; Aegis governs the MCP call.
5. After every governed write, the test reads the engine directly to check what really changed. Each request and response goes to the transcript, and the Aegis audit journal is copied next to it.

## What runs

| Service | Started like | Used by |
| --- | --- | --- |
| Flume 1.11.0 agent `a1` with HTTP monitoring | `bin/flume-ng agent -n a1 -f <conf> -Dflume.monitoring.type=http -Dflume.monitoring.port=<port>`: `org.apache.flume.node.Application` | the `flume` source (`type: http`) |

The agent runs in its own JVM from the classpath `examples/aegis-validation-flume-server` resolves
(`flume-ng-node` 1.11.0, the latest release on Maven Central). It moves events from a `seq` source through
a memory channel to a `null` sink, so the metrics are live counters.

## What the test proves

The profile is read-only: Flume's HTTP monitoring server has no write operations.

| Step | Caller | Call | Own host | Knox |
| --- | --- | --- | --- | --- |
| 1 | `guest` (reader) | `flume_get_metrics` | allowed; the agent's real `SOURCE.seq`, `CHANNEL.mem` and `SINK.drop` counters | allowed |
| 2 | `admin` (outsider) | the same read | Aegis denies: `POLICY_DENIED` | Knox `AclsAuthz`: HTTP 403 |
| 3 | `guest` | `flume_get_health` | allowed; see the finding below | allowed |
| 4 | `admin` | the same read | `POLICY_DENIED` | HTTP 403 |

| Person | Groups in this profile |
| --- | --- |
| `guest` | `flume-users` |
| `admin` | none, and outside the Knox ACL |

## Finding

`flume_get_health` calls `GET /`. Flume 1.11.0 answers that with an HTML line linking to `./metrics`
("For Flume metrics please click here"), not with a health status. The tool reaches the agent, so an
answer means the monitoring server is up, but it carries no component state. Use `flume_get_metrics`
for state. The profile is unchanged here; renaming or removing the tool changes the shipped tool set
and is left to a reviewed change.

## Run it

```powershell
$env:AEGIS_KNOX_HOME = 'C:\knox\knox-3.0.0'     # from ../knox/install-knox.ps1
../run-example.ps1 flume -Keep
```

```bash
export AEGIS_KNOX_HOME=/opt/knox/knox-3.0.0     # from ../knox/install-knox.sh
../run-example.sh flume --keep
```

## Results

| File | What it is |
| --- | --- |
| `results/own-host-transcript.log`, `results/knox-transcript.log` | every request and response, per front door |
| `results/own-host-audit.jsonl`, `results/knox-audit.jsonl` | the Aegis journals; verify with `--verify-audit` |
| `results/test-report.txt` | the Surefire summary |

## Limits

- Flume's HTTP monitoring has no authentication; keep its port on a network only the gateway reaches.
- No approval flow: the profile has no write tool.
