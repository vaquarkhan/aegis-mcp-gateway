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
# Apache BookKeeper example: with Knox and without Knox

This example runs the shipped profile
[`adopters/configurations/adapters/bookkeeper/bookkeeper.yaml`](../../../adopters/configurations/adapters/bookkeeper/bookkeeper.yaml)
against a real Apache BookKeeper 4.18.1 bookie with its HTTP admin server, through Aegis's own server and
inside a stock Apache Knox 3.0.0 gateway. Only the bookie's HTTP address and the egress network are substituted.

Status: **retained historical run**, both front doors. The kept logs are in [results/](results/).

## Files in this folder

| File | What it is |
| --- | --- |
| [`HOW-TO-RUN.md`](HOW-TO-RUN.md) | written by the test harness during the kept run: the exact command line each engine process was started with, how each front door was started with the files below, a sample `curl` request, and every request the test sent with its result. Start here to repeat the run by hand |
| [`aegis/own-host.yaml`](aegis/own-host.yaml) | the Aegis configuration the own-host door loaded: the shipped profile with the engine address substituted, plus a `host:` section |
| [`aegis/knox-aegis.yaml`](aegis/knox-aegis.yaml) | the Aegis configuration loaded inside Knox (no `host:` section) |
| [`aegis/knox-topology.xml`](aegis/knox-topology.xml) | the Knox topology: demo LDAP authentication, group mapping, `AclsAuthz`, and the `AEGIS_MCP` service |
| `engine/` | the engine configuration files the test wrote: [`bk_server.conf`](engine/bk_server.conf), [`zoo.cfg`](engine/zoo.cfg) |
| [`CreateLedgers.java`](CreateLedgers.java) | writes three ledgers with BookKeeper's own client, run with the JDK source launcher |
| [`results/`](results/) | the kept transcripts, Aegis audit journals and Surefire report of that run |
| [`BookKeeperFrontDoorsTest.java`](../../aegis-validation/src/test/java/io/github/vaquarkhan/aegis/validation/BookKeeperFrontDoorsTest.java) | the test itself: how the engine is started and seeded, and every assertion |

## How the run works

1. `mvn -B -Preal-engines -DskipTests install` resolves BookKeeper 4.18.1 (`bookkeeper-server` and the
   `vertx-http-server` HTTP admin module) in `examples/aegis-validation-bookkeeper-server`, and builds the
   Aegis jars, including the Knox bundle.
2. `BookKeeperFrontDoorsTest` starts, in order: a ZooKeeper server for metadata, `bookkeeper shell
   initnewcluster`, the bookie (`org.apache.bookkeeper.server.Main`, what `bin/bookkeeper bookie` runs) with
   `httpServerEnabled=true`, and `CreateLedgers.java`, which writes three ledgers (0, 1, 2) of five entries
   each. Section 1 of `HOW-TO-RUN.md` has each command line.
3. Without Knox: the test builds Aegis from `aegis/own-host.yaml`, starts Aegis's own server on loopback, and
   sends MCP `tools/call` requests over HTTP as each person, with that person's bearer token.
4. With Knox: the test copies the Aegis bundle into a stock Knox 3.0.0 `ext/`, writes `aegis/knox-topology.xml`
   to `conf/topologies/`, starts Knox's demo LDAP and the gateway, and sends the same requests with HTTP Basic
   as the same people. Knox authenticates and checks the route ACL; Aegis governs the MCP call.
5. After the governed delete, the test lists ledgers on the bookie directly to check what really changed.

`bookkeeper shell simpletest` is not used to make ledgers because it deletes the ledger it writes.

## What the test proves

| Step | Caller | Call | Own host | Knox |
| --- | --- | --- | --- | --- |
| 1 | `guest` (reader) | `bookkeeper_bookie_info`, `bookkeeper_list_bookies`, `bookkeeper_list_ledgers` | allowed; free and total disk space, the bookie `127.0.0.1:<port>`, the real ledgers | allowed |
| 2 | `admin` (outsider) | the same reads | Aegis denies: `POLICY_DENIED` | Knox `AclsAuthz`: HTTP 403 |
| 3 | `guest` | `bookkeeper_delete_ledger` | `READONLY_CALLER` | `READONLY_CALLER` |
| 4 | `sam` (operator) | the same call | `APPROVAL_REQUIRED`; the ledger is still listed | same |
| 5 | `sam` | dry run (`GATEWAY` mode: nothing sent to the bookie) | receipt and approval request; the ledger is still listed | same |
| 6 | `sam` | approves own request | HTTP 403 | same |
| 7 | `tom` (approver) | approves | HTTP 200 | same |
| 8 | `sam` | delete with token and receipt | allowed; the bookie no longer lists the ledger (1 for the own host, 2 for Knox), ledger 0 is still there | same |
| 9 | `sam` | same token again | denied: single use | same |

| Person | Groups in this profile |
| --- | --- |
| `guest` | `bookkeeper-users` |
| `sam` | `bookkeeper-operators` |
| `tom` | `bookkeeper-approvers` |
| `admin` | none, and outside the Knox ACL |

## Run it

```powershell
$env:AEGIS_KNOX_HOME = 'C:\knox\knox-3.0.0'     # from ../knox/install-knox.ps1
../run-example.ps1 bookkeeper -Keep
```

```bash
export AEGIS_KNOX_HOME=/opt/knox/knox-3.0.0     # from ../knox/install-knox.sh
../run-example.sh bookkeeper --keep
```

## Findings from the real bookie

- `DELETE /api/v1/ledger/delete?ledger_id=999` for a ledger that does not exist still answers HTTP 200
  "Deleted ledger: 999". A success reply does not prove the ledger existed; the test checks the ledger
  list before and after.
- The bookie's runtime classpath does not carry ZooKeeper's server-side metrics jar, so the metadata
  ZooKeeper runs from the ZooKeeper server classpath, as a separate ensemble does in a deployment.

## Limits

- The bookie's HTTP admin server has no authentication; keep it on a network only the gateway reaches.
- The dry run is `GATEWAY` mode; the admin API has no validate-only delete.
- One bookie, `InterleavedLedgerStorage`. Ensembles and other ledger storage use the same admin API.
