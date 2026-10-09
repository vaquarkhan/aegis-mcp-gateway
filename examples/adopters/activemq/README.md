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
# Apache ActiveMQ Classic example: with Knox and without Knox

This example runs the shipped profile
[`adopters/configurations/adapters/activemq/activemq.yaml`](../../../adopters/configurations/adapters/activemq/activemq.yaml)
against a stock Apache ActiveMQ Classic 6.3.2 broker, through Aegis's own server and inside a stock
Apache Knox 3.0.0 gateway. Only the broker address and the egress network are substituted.

Status: **verified on this tree**, both front doors. The kept logs are in [results/](results/).

## Files in this folder

| File | What it is |
| --- | --- |
| [`HOW-TO-RUN.md`](HOW-TO-RUN.md) | written by the test harness during the kept run: the exact command line each engine was started with, how each front door was started with the files below, a sample `curl` request, and every request the test sent with its result. Start here to repeat the run by hand |
| [`aegis/own-host.yaml`](aegis/own-host.yaml) | the Aegis configuration the own-host door loaded: the shipped profile with the engine address substituted, plus a `host:` section |
| [`aegis/knox-aegis.yaml`](aegis/knox-aegis.yaml) | the Aegis configuration loaded inside Knox (no `host:` section) |
| [`aegis/knox-topology.xml`](aegis/knox-topology.xml) | the Knox topology: demo LDAP authentication, group mapping, `AclsAuthz`, and the `AEGIS_MCP` service |
| `engine/` | the engine configuration files the test wrote: [`activemq.xml`](engine/activemq.xml), [`jetty-spring.properties`](engine/jetty-spring.properties), [`jolokia-access.xml`](engine/jolokia-access.xml) |
| [`results/`](results/) | the kept transcripts, Aegis audit journals and Surefire report of that run |
| [`ActiveMqFrontDoorsTest.java`](../../aegis-validation/src/test/java/io/github/vaquarkhan/aegis/validation/ActiveMqFrontDoorsTest.java) | the test itself: how the engine is started and seeded, and every assertion |

## How the run works

1. `mvn -B -Preal-engines -DskipTests install` resolves the engine (the `examples/aegis-validation-*-server` module for this adopter, or a distribution named in this README) and builds the Aegis jars, including the Knox bundle.
2. `ActiveMqFrontDoorsTest` starts the real engine in its own process, as section 1 of `HOW-TO-RUN.md` shows, and creates the data the scenario works on through the engine's own API, outside Aegis.
3. Without Knox: the test builds Aegis from `aegis/own-host.yaml`, starts Aegis's own server on loopback, and sends MCP `tools/call` requests over HTTP as each person, with that person's bearer token.
4. With Knox: the test copies the Aegis bundle into a stock Knox 3.0.0 `ext/`, writes `aegis/knox-topology.xml` to `conf/topologies/`, starts Knox's demo LDAP and the gateway, and sends the same requests with HTTP Basic as the same people. Knox authenticates and checks the route ACL; Aegis governs the MCP call.
5. After every governed write, the test reads the engine directly to check what really changed. Each request and response goes to the transcript, and the Aegis audit journal is copied next to it.

## What runs

`examples/aegis-validation-activemq-server` unpacks the official binary distribution
(`org.apache.activemq:apache-activemq:6.3.2:bin:zip`, the file the ActiveMQ download page links). The
test starts it as `bin/activemq start` does (`org.apache.activemq.console.Main start` with the same
system properties) from a copy of its `conf/`, so the web console, Jetty security and Jolokia are the
shipped ones.

The copy differs from the shipped `conf/` only by these operator steps:

| File | Change | Why |
| --- | --- | --- |
| `jetty-spring.properties`, `activemq.xml` | loopback host and free ports | test isolation |
| `users.properties`, `groups.properties` | `admin=admin` becomes a dedicated `aegis-gateway` account in `admins` | only the gateway holds the broker credential |
| `activemq.xml` | static queues `orders`, `scratch_ownhost`, `scratch_knox` | the data the scenario works on |
| `jolokia-access.xml` | `exec` added to the allowed commands; `removeQueue` removed from the Broker MBean deny list | what the profile documents; everything else stays denied, including `purge` |

## Why the profile changed

The first run against a real broker showed the previous profile could not work with ActiveMQ 6:

- `jolokia-access.xml` allows POST only. The old GET read/search paths were refused by the broker.
- Jolokia refuses a request without an `Origin` header, and `/api/jolokia` needs basic auth.
- The old `activemq_purge_queue` targeted the queue's own MBean. With POST, that MBean name sits inside the
  JSON body, and Aegis never splices an argument into a string. The profile now uses the Broker MBean's
  `removeQueue(java.lang.String)`, where the queue is a separate argument bound to the typed
  `activemq:queue` resource.

## What the test proves

| Step | Caller | Call | Own host | Knox |
| --- | --- | --- | --- | --- |
| 1 | `guest` (reader) | `activemq_get_broker`, `activemq_list_queues` | allowed; `BrokerVersion` 6.3.2 and the real queue MBeans | allowed |
| 2 | `admin` (outsider) | the same reads | Aegis denies: `POLICY_DENIED` | Knox `AclsAuthz`: HTTP 403 |
| 3 | `guest` | `activemq_remove_queue` | `READONLY_CALLER` | `READONLY_CALLER` |
| 4 | `sam` (operator) | the same call | `APPROVAL_REQUIRED`; the queue still exists | same |
| 5 | `sam` | dry run (`GATEWAY` mode: nothing sent to the broker) | receipt and approval request; the queue still exists | same |
| 6 | `sam` | approves own request | HTTP 403 | same |
| 7 | `tom` (approver) | approves | HTTP 200 | same |
| 8 | `sam` | remove with token and receipt | allowed; the broker no longer lists the queue, `orders` is still there | same |
| 9 | `sam` | same token again | denied: single use | same |

The queue list is read from Jolokia directly, outside Aegis.

| Person | Groups in this profile |
| --- | --- |
| `guest` | `activemq-users` |
| `sam` | `activemq-operators` |
| `tom` | `activemq-approvers` |
| `admin` | none, and outside the Knox ACL |

## Run it

```powershell
$env:AEGIS_KNOX_HOME = 'C:\knox\knox-3.0.0'     # from ../knox/install-knox.ps1
../run-example.ps1 activemq -Keep
```

```bash
export AEGIS_KNOX_HOME=/opt/knox/knox-3.0.0     # from ../knox/install-knox.sh
../run-example.sh activemq --keep
```

## Results

| File | What it is |
| --- | --- |
| `results/own-host-transcript.log`, `results/knox-transcript.log` | every request and response, per front door |
| `results/own-host-audit.jsonl`, `results/knox-audit.jsonl` | the Aegis journals; verify with `--verify-audit` |
| `results/test-report.txt` | the Surefire summary |

## Limits

- Jolokia puts its application status in the JSON body even when transport HTTP is 200. This profile
  requires `response.status.pointer: /status` to equal `200`, so an embedded broker refusal is now a
  fail-closed `BACKEND_ERROR` rather than an allowed result. The retained run proves the success path;
  `HttpSourcesAndWiringTest` drives success, refusal, missing status and invalid JSON cases.
- The dry run is `GATEWAY` mode: Jolokia has no validate-only mode for `removeQueue`, and reading one
  queue's MBean would need the queue name spliced into the MBean name.
- One broker. A network of brokers uses the same Jolokia API on each broker.
