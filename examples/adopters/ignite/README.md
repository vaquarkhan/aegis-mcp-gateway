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
# Apache Ignite example: with Knox and without Knox

This example runs the shipped profile
[`adopters/configurations/adapters/ignite/ignite.yaml`](../../../adopters/configurations/adapters/ignite/ignite.yaml)
against a real Apache Ignite 2.18.0 server node, through Aegis's own server and inside a stock Apache
Knox 3.0.0 gateway. Only the node address and the egress network are substituted.

Status: **retained historical run**, both front doors. The kept logs are in [results/](results/).

## Files in this folder

| File | What it is |
| --- | --- |
| [`HOW-TO-RUN.md`](HOW-TO-RUN.md) | written by the test harness during the kept run: the exact command line each engine was started with, how each front door was started with the files below, a sample `curl` request, and every request the test sent with its result. Start here to repeat the run by hand |
| [`aegis/own-host.yaml`](aegis/own-host.yaml) | the Aegis configuration the own-host door loaded: the shipped profile with the engine address substituted, plus a `host:` section |
| [`aegis/knox-aegis.yaml`](aegis/knox-aegis.yaml) | the Aegis configuration loaded inside Knox (no `host:` section) |
| [`aegis/knox-topology.xml`](aegis/knox-topology.xml) | the Knox topology: demo LDAP authentication, group mapping, `AclsAuthz`, and the `AEGIS_MCP` service |
| `engine/` | the engine configuration files the test wrote: [`aegis-ignite.xml`](engine/aegis-ignite.xml) |
| [`results/`](results/) | the kept transcripts, Aegis audit journals and Surefire report of that run |
| [`IgniteFrontDoorsTest.java`](../../aegis-validation/src/test/java/io/github/vaquarkhan/aegis/validation/IgniteFrontDoorsTest.java) | the test itself: how the engine is started and seeded, and every assertion |

## How the run works

1. `mvn -B -Preal-engines -DskipTests install` resolves the engine (the `examples/aegis-validation-*-server` module for this adopter, or a distribution named in this README) and builds the Aegis jars, including the Knox bundle.
2. `IgniteFrontDoorsTest` starts the real engine in its own process, as section 1 of `HOW-TO-RUN.md` shows, and creates the data the scenario works on through the engine's own API, outside Aegis.
3. Without Knox: the test builds Aegis from `aegis/own-host.yaml`, starts Aegis's own server on loopback, and sends MCP `tools/call` requests over HTTP as each person, with that person's bearer token.
4. With Knox: the test copies the Aegis bundle into a stock Knox 3.0.0 `ext/`, writes `aegis/knox-topology.xml` to `conf/topologies/`, starts Knox's demo LDAP and the gateway, and sends the same requests with HTTP Basic as the same people. Knox authenticates and checks the route ACL; Aegis governs the MCP call.
5. After every governed write, the test reads the engine directly to check what really changed. Each request and response goes to the transcript, and the Aegis audit journal is copied next to it.

## What runs

| Service | Started like | Used by |
| --- | --- | --- |
| Ignite 2.18.0 server node with the optional `ignite-rest-http` module | `bin/ignite.sh <config.xml>`: `CommandLineStartup` with a Spring XML file, and the Java 17 options from Ignite's `bin/include/jvmdefaults.sh` | the `ignite` source (`type: http`) |

The node runs in its own JVM from the classpath `examples/aegis-validation-ignite-server` resolves
(`ignite-core`, `ignite-spring`, `ignite-rest-http`). Discovery, communication and REST are bound to
loopback; `IGNITE_UPDATE_NOTIFIER=false` stops the node from calling home. The test creates the caches
`orders`, `scratch_ownhost` and `scratch_knox` with one entry each through the REST API, outside Aegis.

## What the test proves

| Step | Caller | Call | Own host | Knox |
| --- | --- | --- | --- | --- |
| 1 | `guest` (reader) | `ignite_get_version`, `ignite_list_caches` | allowed; `2.18.0` and the real topology | allowed |
| 2 | `admin` (outsider) | the same reads | Aegis denies: `POLICY_DENIED` | Knox `AclsAuthz`: HTTP 403 |
| 3 | `guest` | `ignite_destroy_cache` | `READONLY_CALLER` | `READONLY_CALLER` |
| 4 | `sam` (operator) | the same call | `APPROVAL_REQUIRED`; the cache still exists | same |
| 5 | `sam` | dry run (`TOOL` mode: `ignite_cache_size` on the exact cache) | the live size (1), a receipt and an approval request; the cache still exists | same |
| 6 | `sam` | approves own request | HTTP 403 | same |
| 7 | `tom` (approver) | approves | HTTP 200 | same |
| 8 | `sam` | destroy with token and receipt | allowed; the topology no longer lists the cache, `orders` is still there | same |
| 9 | `sam` | same token again | denied: single use | same |

The cache list is read from `GET /ignite?cmd=top` directly, outside Aegis.

| Person | Groups in this profile |
| --- | --- |
| `guest` | `ignite-users` |
| `sam` | `ignite-operators` |
| `tom` | `ignite-approvers` |
| `admin` | none, and outside the Knox ACL |

## Run it

```powershell
$env:AEGIS_KNOX_HOME = 'C:\knox\knox-3.0.0'     # from ../knox/install-knox.ps1
../run-example.ps1 ignite -Keep
```

```bash
export AEGIS_KNOX_HOME=/opt/knox/knox-3.0.0     # from ../knox/install-knox.sh
../run-example.sh ignite --keep
```

## Results

| File | What it is |
| --- | --- |
| `results/own-host-transcript.log`, `results/knox-transcript.log` | every request and response, per front door |
| `results/own-host-audit.jsonl`, `results/knox-audit.jsonl` | the Aegis journals; verify with `--verify-audit` |
| `results/test-report.txt` | the Surefire summary |

## Limits

- Ignite REST v1 has no authentication unless a security plugin is configured, so the profile sends no
  credential. Keep the REST port on a network only the gateway can reach.
- Ignite REST v1 embeds application failures in HTTP 200 responses using `"successStatus":1`. This
  profile requires `response.status.pointer: /successStatus` to equal `0`, so a backend application
  failure is fail-closed as `BACKEND_ERROR` before the response is exposed to the model. The generic
  semantic-response test covers success, failure, missing field and invalid JSON.
- One node. A cluster serves the same REST API from each node with the module enabled.
