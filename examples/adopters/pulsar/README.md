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
# Apache Pulsar example: with Knox and without Knox

This example runs the shipped profile
[`adopters/configurations/adapters/pulsar/pulsar.yaml`](../../../adopters/configurations/adapters/pulsar/pulsar.yaml)
against a real Apache Pulsar 4.0.13 broker and bookie, through Aegis's own server and inside a stock Apache
Knox 3.0.0 gateway. Only the broker address and the egress network are substituted.

Status: **retained historical run**, both front doors. The kept logs are in [results/](results/).

## Files in this folder

| File | What it is |
| --- | --- |
| [`HOW-TO-RUN.md`](HOW-TO-RUN.md) | written by the test harness during the kept run: the exact command lines used for ZooKeeper and Pulsar, both Aegis front doors, a sample MCP `curl` call, and every tested step and result |
| [`aegis/own-host.yaml`](aegis/own-host.yaml) | the actual own-host Aegis configuration: profile, substituted broker address, loopback egress and `host:` identity section |
| [`aegis/knox-aegis.yaml`](aegis/knox-aegis.yaml) | the Aegis configuration Knox loaded (no `host:` section) |
| [`aegis/knox-topology.xml`](aegis/knox-topology.xml) | the actual Knox topology: demo LDAP, group mapping, `AclsAuthz` and `AEGIS_MCP` |
| [`engine/standalone.conf`](engine/standalone.conf), [`engine/zoo.cfg`](engine/zoo.cfg) | the Pulsar and external ZooKeeper configuration files the test wrote |
| [`install-pulsar.ps1`](install-pulsar.ps1), [`install-pulsar.sh`](install-pulsar.sh) | download Pulsar 4.0.13, verify Apache’s SHA-512, and unpack it |
| [`results/`](results/) | the kept transcripts, Aegis audit journals and Surefire report |
| [`PulsarFrontDoorsTest.java`](../../aegis-validation/src/test/java/io/github/vaquarkhan/aegis/validation/PulsarFrontDoorsTest.java) | the test itself: the real process launch, topic setup and assertions |

## How the run works

1. The install script fetches the official Pulsar 4.0.13 binary distribution and verifies its SHA-512.
2. `PulsarFrontDoorsTest` starts a **real external ZooKeeper** server, then Pulsar’s real
   `PulsarStandaloneStarter` with `--metadata-url zk:127.0.0.1:<port>`. Pulsar starts its real broker and
   bookie; the test creates `orders`, `scratch_ownhost`, and `scratch_knox` in `public/default` through
   Pulsar’s own Admin REST API, outside Aegis.
3. The external ZooKeeper is intentional on Windows. Pulsar 4.0.13’s default standalone RocksDB metadata
   store calls `Files.setPosixFilePermissions`, which Windows does not implement. The metadata store is
   still real: the test uses Apache ZooKeeper 3.9.3 and Pulsar’s supported `zk:` metadata URI.
4. Without Knox, Aegis loads `aegis/own-host.yaml` and listens on loopback. With Knox, the Aegis extension
   is copied to Knox `ext/`, `aegis/knox-topology.xml` is deployed, and Knox demo LDAP authenticates the
   same people over HTTPS Basic auth. `HOW-TO-RUN.md` gives the commands.
5. The test checks Pulsar directly before and after the governed delete. Audit journals and complete MCP
   transcripts are retained in `results/`.

## What the test proves

| Step | Caller | Call | Own host | Knox |
| --- | --- | --- | --- | --- |
| 1 | `guest` (reader) | `pulsar_list_clusters`, `pulsar_list_topics` | allowed; the real `standalone` cluster and topics | allowed |
| 2 | `admin` (outsider) | the same reads | Aegis denies: `POLICY_DENIED` | Knox `AclsAuthz`: HTTP 403 |
| 3 | `sam` (operator) | delete `pulsar/system/anything` | `POLICY_DENIED` by `never-delete-system-topics` before approval | same |
| 4 | `guest` | delete `public/default/scratch_<door>` | `READONLY_CALLER` | `READONLY_CALLER` |
| 5 | `sam` | the same delete | `APPROVAL_REQUIRED`; the topic remains | same |
| 6 | `sam` | dry run (`TOOL` mode: exact topic stats) | receipt and approval request; the topic remains | same |
| 7 | `sam` | approves own request | HTTP 403 | same |
| 8 | `tom` (approver) | approves | HTTP 200 | same |
| 9 | `sam` | delete with token and receipt | allowed; Pulsar no longer lists the topic and still lists `orders` | same |
| 10 | `sam` | replay the token | denied: single use | same |

| Person | Groups in this profile |
| --- | --- |
| `guest` | `pulsar-users` |
| `sam` | `pulsar-operators` |
| `tom` | `pulsar-approvers` |
| `admin` | none, and outside the Knox ACL |

## Run it

```powershell
./install-pulsar.ps1 -Destination C:\pulsar
$env:AEGIS_PULSAR_HOME = 'C:\pulsar\apache-pulsar-4.0.13'
$env:AEGIS_KNOX_HOME = 'C:\knox\knox-3.0.0'
../run-example.ps1 pulsar -Keep
```

```bash
./install-pulsar.sh /opt/pulsar
export AEGIS_PULSAR_HOME=/opt/pulsar/apache-pulsar-4.0.13
export AEGIS_KNOX_HOME=/opt/knox/knox-3.0.0
../run-example.sh pulsar --keep
```

`-Keep` copies the exact Aegis YAML, Knox topology, engine configs, walkthrough and verified audit
journals into this folder. Without `AEGIS_PULSAR_HOME` the test is skipped, never passed.

## Limits

- The standalone broker has no authentication enabled. Keep it and its Admin REST port on a network only
  the gateway reaches; production Pulsar should use its own authentication and authorization too.
- The test uses one local broker/bookie. Multi-broker clusters use the same Admin REST surface.
