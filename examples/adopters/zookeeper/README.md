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
# Apache ZooKeeper example: with Knox and without Knox

This example runs the shipped profile
[`adopters/configurations/adapters/zookeeper/zookeeper.yaml`](../../../adopters/configurations/adapters/zookeeper/zookeeper.yaml)
against a real Apache ZooKeeper 3.9.3 server, through Aegis's own server and inside a stock Apache Knox
3.0.0 gateway. Only the server addresses and the egress network are substituted.

Status: **verified on this tree**, both front doors. The kept logs are in [results/](results/).

## Files in this folder

| File | What it is |
| --- | --- |
| [`HOW-TO-RUN.md`](HOW-TO-RUN.md) | written by the test harness during the kept run: the exact command line each engine was started with, how each front door was started with the files below, a sample `curl` request, and every request the test sent with its result. Start here to repeat the run by hand |
| [`aegis/own-host.yaml`](aegis/own-host.yaml) | the Aegis configuration the own-host door loaded: the shipped profile with the engine address substituted, plus a `host:` section |
| [`aegis/knox-aegis.yaml`](aegis/knox-aegis.yaml) | the Aegis configuration loaded inside Knox (no `host:` section) |
| [`aegis/knox-topology.xml`](aegis/knox-topology.xml) | the Knox topology: demo LDAP authentication, group mapping, `AclsAuthz`, and the `AEGIS_MCP` service |
| `engine/` | the engine configuration files the test wrote: [`zoo.cfg`](engine/zoo.cfg) |
| [`results/`](results/) | the kept transcripts, Aegis audit journals and Surefire report of that run |
| [`ZooKeeperFrontDoorsTest.java`](../../aegis-validation/src/test/java/io/github/vaquarkhan/aegis/validation/ZooKeeperFrontDoorsTest.java) | the test itself: how the engine is started and seeded, and every assertion |

## How the run works

1. `mvn -B -Preal-engines -DskipTests install` resolves the engine (the `examples/aegis-validation-*-server` module for this adopter, or a distribution named in this README) and builds the Aegis jars, including the Knox bundle.
2. `ZooKeeperFrontDoorsTest` starts the real engine in its own process, as section 1 of `HOW-TO-RUN.md` shows, and creates the data the scenario works on through the engine's own API, outside Aegis.
3. Without Knox: the test builds Aegis from `aegis/own-host.yaml`, starts Aegis's own server on loopback, and sends MCP `tools/call` requests over HTTP as each person, with that person's bearer token.
4. With Knox: the test copies the Aegis bundle into a stock Knox 3.0.0 `ext/`, writes `aegis/knox-topology.xml` to `conf/topologies/`, starts Knox's demo LDAP and the gateway, and sends the same requests with HTTP Basic as the same people. Knox authenticates and checks the route ACL; Aegis governs the MCP call.
5. After every governed write, the test reads the engine directly to check what really changed. Each request and response goes to the transcript, and the Aegis audit journal is copied next to it.

## Two ZooKeeper surfaces, both exercised

| Profile source | What it talks to | Tools used here |
| --- | --- | --- |
| `zookeeper-admin` (`type: http`) | the AdminServer's JSON commands under `/commands/<name>` | `zookeeper_stat` |
| `zookeeper` (`type: zookeeper`, plugin `aegis-source-zookeeper`) | the data tree, over ZooKeeper's client protocol | `zookeeper_list_children`, `zookeeper_delete_node` |

The server is `org.apache.zookeeper.server.ZooKeeperServerMain` in its own JVM, started with a
`zoo.cfg` the way `bin/zkServer.sh` starts it, with `admin.enableServer=true`. Its classpath comes from
`examples/aegis-validation-zookeeper-server`, which adds the libraries ZooKeeper's POM marks as
`provided` because its binary distribution ships them in `lib/` (Jetty for the AdminServer, Dropwizard
metrics, Jackson, commons-cli, snappy), at the versions ZooKeeper's own parent POM pins.

## The data-tree plugin inside Knox

The `zookeeper` source is a plugin, so with Knox it is installed in Knox's `ext/` as an operator would:
`aegis-source-zookeeper-2.0.0-SNAPSHOT.jar` plus its runtime dependencies
(`aegis-source-zookeeper/target/plugin.classpath`, written by `-Preal-engines`). Knox 3.0.0 already
ships a ZooKeeper client (3.8.6, used by Knox's own HA support), so Knox keeps its own copy and the
plugin runs on it, against the 3.9.3 server. The generated
`aegis-source-zookeeper/target/plugin.classpath` is the reproducible source of the plugin jar list for
an operator run.

## What the test proves

| Step | Caller | Call | Own host | Knox |
| --- | --- | --- | --- | --- |
| 1 | `guest` (reader) | `zookeeper_stat`, `zookeeper_list_children /aegis` | allowed; real server stats and children | allowed |
| 2 | `admin` (outsider) | the same reads | Aegis denies: `POLICY_DENIED` | Knox `AclsAuthz`: HTTP 403 |
| 3 | `guest` | `zookeeper_delete_node` | `READONLY_CALLER` | `READONLY_CALLER` |
| 4 | `sam` (operator) | `zookeeper_delete_node` | `APPROVAL_REQUIRED`; the znode still exists | same |
| 5 | `sam` | dry run (`TOOL` mode: `zookeeper_stat_node` on the exact znode) | receipt and approval request; znode still exists | same |
| 6 | `sam` | approves own request | HTTP 403 | same |
| 7 | `tom` (approver) | approves | HTTP 200 | same |
| 8 | `sam` | delete with token and receipt (`version: 0`, ZooKeeper's optimistic check) | allowed; the znode is gone | same |
| 9 | `sam` | same token again | denied: single use | same |

Whether the znode exists is checked with a separate ZooKeeper client, outside Aegis.

| Person | Groups in this profile |
| --- | --- |
| `guest` | `zookeeper-users` |
| `sam` | `zookeeper-operators` |
| `tom` | `zookeeper-approvers` |
| `admin` | none, and outside the Knox ACL |

## Run it

```powershell
$env:AEGIS_KNOX_HOME = 'C:\knox\knox-3.0.0'     # from ../knox/install-knox.ps1
../run-example.ps1 zookeeper -Keep
```

```bash
export AEGIS_KNOX_HOME=/opt/knox/knox-3.0.0     # from ../knox/install-knox.sh
../run-example.sh zookeeper --keep
```

Without `AEGIS_KNOX_HOME` the Knox run is reported as skipped, never as passed.

## Results

| File | What it is |
| --- | --- |
| `results/own-host-transcript.log`, `results/knox-transcript.log` | every request and response, per front door |
| `results/own-host-audit.jsonl`, `results/knox-audit.jsonl` | the Aegis journals; verify with `--verify-audit` |
| `results/test-report.txt` | the Surefire summary |

## Limits

- One standalone server, not an ensemble; the profile's tools behave the same against an ensemble.
- The profile's `never-write-reserved-znodes` rule (no write under `/zookeeper` for anyone) and the
  `BADVERSION` path are covered by `AdapterConfigsTest` and `ZooKeeperToolSourceTest` (real embedded
  ZooKeeper), not repeated here.
- With Knox, ZooKeeper ACLs on the znodes still apply to the gateway's own session; this run uses
  open ACLs.
