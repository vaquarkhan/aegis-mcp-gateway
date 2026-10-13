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
# Apache Kafka and Kafka Connect example: with Knox and without Knox

This example runs the shipped profile
[`adopters/configurations/kafka/kafka.yaml`](../../../adopters/configurations/kafka/kafka.yaml) against a
real Apache Kafka 3.9.1 broker and a real Kafka Connect worker, through Aegis's own server and inside a
stock Apache Knox 3.0.0 gateway. Only the addresses and the egress network are substituted.

Status: **retained historical run**, both front doors. The kept logs are in [results/](results/).

## Files in this folder

| File | What it is |
| --- | --- |
| [`HOW-TO-RUN.md`](HOW-TO-RUN.md) | written by the test harness during the kept run: the exact command line each engine was started with, how each front door was started with the files below, a sample `curl` request, and every request the test sent with its result. Start here to repeat the run by hand |
| [`aegis/own-host.yaml`](aegis/own-host.yaml) | the Aegis configuration the own-host door loaded: the shipped profile with the engine address substituted, plus a `host:` section |
| [`aegis/knox-aegis.yaml`](aegis/knox-aegis.yaml) | the Aegis configuration loaded inside Knox (no `host:` section) |
| [`aegis/knox-topology.xml`](aegis/knox-topology.xml) | the Knox topology: demo LDAP authentication, group mapping, `AclsAuthz`, and the `AEGIS_MCP` service |
| `engine/` | the engine configuration files the test wrote: [`connect-standalone.properties`](engine/connect-standalone.properties), [`server.properties`](engine/server.properties) |
| [`results/`](results/) | the kept transcripts, Aegis audit journals and Surefire report of that run |
| [`KafkaFrontDoorsTest.java`](../../aegis-validation/src/test/java/io/github/vaquarkhan/aegis/validation/KafkaFrontDoorsTest.java) | the test itself: how the engine is started and seeded, and every assertion |

## How the run works

1. `mvn -B -Preal-engines -DskipTests install` resolves the engine (the `examples/aegis-validation-*-server` module for this adopter, or a distribution named in this README) and builds the Aegis jars, including the Knox bundle.
2. `KafkaFrontDoorsTest` starts the real engine in its own process, as section 1 of `HOW-TO-RUN.md` shows, and creates the data the scenario works on through the engine's own API, outside Aegis.
3. Without Knox: the test builds Aegis from `aegis/own-host.yaml`, starts Aegis's own server on loopback, and sends MCP `tools/call` requests over HTTP as each person, with that person's bearer token.
4. With Knox: the test copies the Aegis bundle into a stock Knox 3.0.0 `ext/`, writes `aegis/knox-topology.xml` to `conf/topologies/`, starts Knox's demo LDAP and the gateway, and sends the same requests with HTTP Basic as the same people. Knox authenticates and checks the route ACL; Aegis governs the MCP call.
5. After every governed write, the test reads the engine directly to check what really changed. Each request and response goes to the transcript, and the Aegis audit journal is copied next to it.

## What runs

Both services run in their own JVMs from the classpath `examples/aegis-validation-kafka-server` resolves
(the broker, Connect runtime and FileStream connectors the binary distribution ships in `libs/`):

| Service | Started like | Used by |
| --- | --- | --- |
| Kafka broker, KRaft, combined broker and controller | `kafka-storage.sh format`, then `kafka-server-start.sh` (`kafka.tools.StorageTool`, `kafka.Kafka`) | the `kafka-admin` source (plugin) |
| Kafka Connect worker, standalone | `connect-standalone.sh` (`ConnectStandalone`) | the `kafka-connect` source (`type: http`) |

The test creates the topic `orders` and, per front door, a `FileStreamSourceConnector` reading a local
file into it, the way a deployment pipeline would, outside Aegis.

| Profile source | Exercised |
| --- | --- |
| `kafka-admin` | yes: `kafka_list_topics`, `kafka_describe_cluster`, `kafka_describe_topic_configs` against the real KRaft broker |
| `kafka-connect` | yes: `connect_list_connectors`, `connect_delete_connector` |
| `schema-registry` | no: Schema Registry is a Confluent service, not part of Apache Kafka, and is not started |

## The kafka-admin plugin inside Knox

`type: kafka-admin` comes from the `aegis-examples` jar and needs `kafka-clients`. With Knox, both go in
Knox's `ext/` with the client's compression libraries (`aegis-examples/target/plugin.classpath`); Knox
keeps its own snappy. The generated `aegis-examples/target/plugin.classpath` is the reproducible
source of the plugin jar list for an operator run.

## What the test proves

| Step | Caller | Call | Own host | Knox |
| --- | --- | --- | --- | --- |
| 1 | `guest` (reader) | `kafka_list_topics`, `kafka_describe_cluster`, `kafka_describe_topic_configs`, `connect_list_connectors` | allowed; the real KRaft topic, cluster ID/nodes, topic configuration and connector | allowed |
| 2 | `admin` (outsider) | the same reads | Aegis denies: `POLICY_DENIED` | Knox `AclsAuthz`: HTTP 403 |
| 3 | `guest` | `connect_delete_connector` | `READONLY_CALLER` | `READONLY_CALLER` |
| 4 | `sam` (operator) | `connect_delete_connector` | `APPROVAL_REQUIRED`; the connector still runs | same |
| 5 | `sam` | dry run (`GATEWAY` mode: nothing sent to Connect) | receipt and approval request; connector still runs | same |
| 6 | `sam` | approves own request | HTTP 403 | same |
| 7 | `tom` (approver) | approves | HTTP 200 | same |
| 8 | `sam` | delete with token and receipt | allowed; Connect no longer lists the connector | same |
| 9 | `sam` | same token again | denied: single use | same |

The connector list is read from the Connect REST API directly, outside Aegis.

| Person | Groups in this profile |
| --- | --- |
| `guest` | `kafka-users` |
| `sam` | `kafka-operators` |
| `tom` | `kafka-approvers` |
| `admin` | none, and outside the Knox ACL |

## Run it

```powershell
$env:AEGIS_KNOX_HOME = 'C:\knox\knox-3.0.0'     # from ../knox/install-knox.ps1
../run-example.ps1 kafka -Keep
```

```bash
export AEGIS_KNOX_HOME=/opt/knox/knox-3.0.0     # from ../knox/install-knox.sh
../run-example.sh kafka --keep
```

## Results

| File | What it is |
| --- | --- |
| `results/own-host-transcript.log`, `results/knox-transcript.log` | every request and response, per front door |
| `results/own-host-audit.jsonl`, `results/knox-audit.jsonl` | the Aegis journals; verify with `--verify-audit` |
| `results/test-report.txt` | the Surefire summary |

## Limits

- The destructive call exercised is the connector delete. `kafka_delete_topic` follows the same approval
  path, but it is not run here because this run is on Windows, where Kafka cannot reliably delete a
  topic's log directory while the broker holds it open
  ([KAFKA-1194](https://issues.apache.org/jira/browse/KAFKA-1194)). Run it on Linux if you want that step.
- `kafka_create_topic` previews with Kafka's own `validateOnly` mode; covered by `ExampleConfigsTest`.
- Topic partition expansion is intentionally not exposed yet. The Kafka test client does not implement
  `createPartitions`; it will be added only with dedicated real KRaft coverage, not a simulated broker response.
- One broker, one standalone worker. A cluster and a distributed Connect group use the same APIs.
