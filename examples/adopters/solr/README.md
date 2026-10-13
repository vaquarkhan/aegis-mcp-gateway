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
# Apache Solr example: with Knox and without Knox

This example runs the shipped profile
[`adopters/configurations/adapters/solr/solr.yaml`](../../../adopters/configurations/adapters/solr/solr.yaml)
against a real Apache Solr 9.10.1 node in SolrCloud mode, through Aegis's own server and inside a stock
Apache Knox 3.0.0 gateway. Only the node address and the egress network are substituted.

Status: **retained historical run** with Solr 9.10.1, both front doors. The kept logs are in [results/](results/).
Solr 10.0.0 needs Java 21 and was not run; the validation runs used JDK 17 (see
[Tested environments](../../../docs/VALIDATION.md#tested-environments)).

## Files in this folder

| File | What it is |
| --- | --- |
| [`HOW-TO-RUN.md`](HOW-TO-RUN.md) | written by the test harness during the kept run: the exact command line each engine was started with, how each front door was started with the files below, a sample `curl` request, and every request the test sent with its result. Start here to repeat the run by hand |
| [`aegis/own-host.yaml`](aegis/own-host.yaml) | the Aegis configuration the own-host door loaded: the shipped profile with the engine address substituted, plus a `host:` section |
| [`aegis/knox-aegis.yaml`](aegis/knox-aegis.yaml) | the Aegis configuration loaded inside Knox (no `host:` section) |
| [`aegis/knox-topology.xml`](aegis/knox-topology.xml) | the Knox topology: demo LDAP authentication, group mapping, `AclsAuthz`, and the `AEGIS_MCP` service |
| [`install-solr.ps1`](install-solr.ps1) | downloads Solr 9.10.1 and checks its SHA-512 |
| [`install-solr.sh`](install-solr.sh) | the same, for Linux and macOS |
| [`results/`](results/) | the kept transcripts, Aegis audit journals and Surefire report of that run |
| [`SolrFrontDoorsTest.java`](../../aegis-validation/src/test/java/io/github/vaquarkhan/aegis/validation/SolrFrontDoorsTest.java) | the test itself: how the engine is started and seeded, and every assertion |

## How the run works

1. `mvn -B -Preal-engines -DskipTests install` resolves the engine (the `examples/aegis-validation-*-server` module for this adopter, or a distribution named in this README) and builds the Aegis jars, including the Knox bundle.
2. `SolrFrontDoorsTest` starts the real engine in its own process, as section 1 of `HOW-TO-RUN.md` shows, and creates the data the scenario works on through the engine's own API, outside Aegis.
3. Without Knox: the test builds Aegis from `aegis/own-host.yaml`, starts Aegis's own server on loopback, and sends MCP `tools/call` requests over HTTP as each person, with that person's bearer token.
4. With Knox: the test copies the Aegis bundle into a stock Knox 3.0.0 `ext/`, writes `aegis/knox-topology.xml` to `conf/topologies/`, starts Knox's demo LDAP and the gateway, and sends the same requests with HTTP Basic as the same people. Knox authenticates and checks the route ACL; Aegis governs the MCP call.
5. After every governed write, the test reads the engine directly to check what really changed. Each request and response goes to the transcript, and the Aegis audit journal is copied next to it.

## What runs

| Service | Started like | Used by |
| --- | --- | --- |
| Solr 9.10.1 (slim distribution) with its embedded ZooKeeper | `bin/solr start -c -f`: the test launches `start.jar` from `server/` with the JVM options `solr.cmd` assembles, bound to 127.0.0.1, with its own Solr home | the `solr` source (`type: http`) |

[`install-solr.ps1`](install-solr.ps1) or [`install-solr.sh`](install-solr.sh) downloads the slim
distribution and checks its published SHA-512. The test creates the collections `products` (two
documents), `scratch_ownhost` and `scratch_knox` through the Collections API with the `_default` configset,
outside Aegis.

## What the test proves

| Step | Caller | Call | Own host | Knox |
| --- | --- | --- | --- | --- |
| 1 | `guest` (reader) | `solr_system_info`, `solr_list_collections`, `solr_query` (`name:orders` on `products`) | allowed; `9.10.1`, the real collections, the matching document | allowed |
| 2 | `admin` (outsider) | the same reads | Aegis denies: `POLICY_DENIED` | Knox `AclsAuthz`: HTTP 403 |
| 3 | `guest` | `solr_delete_collection` | `READONLY_CALLER` | `READONLY_CALLER` |
| 4 | `sam` (operator) | the same call | `APPROVAL_REQUIRED`; the collection still exists | same |
| 5 | `sam` | dry run (`GATEWAY` mode: nothing sent to Solr) | receipt and approval request; the collection still exists | same |
| 6 | `sam` | approves own request | HTTP 403 | same |
| 7 | `tom` (approver) | approves | HTTP 200 | same |
| 8 | `sam` | delete with token and receipt | allowed; Solr no longer lists the collection, `products` is still there | same |
| 9 | `sam` | same token again | denied: single use | same |

The collection list is read from the Collections API directly, outside Aegis.

| Person | Groups in this profile |
| --- | --- |
| `guest` | `solr-users` |
| `sam` | `solr-operators` |
| `tom` | `solr-approvers` |
| `admin` | none, and outside the Knox ACL |

## Run it

```powershell
./install-solr.ps1 -Destination C:\solr
$env:AEGIS_SOLR_HOME = 'C:\solr\solr-9.10.1-slim'
$env:AEGIS_KNOX_HOME = 'C:\knox\knox-3.0.0'
../run-example.ps1 solr -Keep
```

```bash
./install-solr.sh /opt/solr
export AEGIS_SOLR_HOME=/opt/solr/solr-9.10.1-slim
export AEGIS_KNOX_HOME=/opt/knox/knox-3.0.0
../run-example.sh solr --keep
```

Without `AEGIS_SOLR_HOME` the test is skipped, never passed.

## Results

| File | What it is |
| --- | --- |
| `results/own-host-transcript.log`, `results/knox-transcript.log` | every request and response, per front door |
| `results/own-host-audit.jsonl`, `results/knox-audit.jsonl` | the Aegis journals; verify with `--verify-audit` |
| `results/test-report.txt` | the Surefire summary |

## Limits

- No Solr authentication plugin is configured, so the profile sends no credential. With Basic or JWT
  auth enabled, add a `credentials` block to the source.
- The dry run is `GATEWAY` mode; the Collections API has no validate-only delete.
- One node. A multi-node SolrCloud serves the same Collections API from any node.
