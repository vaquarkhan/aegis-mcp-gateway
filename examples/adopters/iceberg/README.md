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
# Apache Iceberg REST catalog example: with Knox and without Knox

This example runs the shipped profile
[`adopters/configurations/iceberg/iceberg.yaml`](../../../adopters/configurations/iceberg/iceberg.yaml)
against the official Apache Iceberg 1.10.1 REST catalog server, through Aegis's own server and inside a
stock Apache Knox 3.0.0 gateway. Only the catalog address and the egress network are substituted.

Status: **retained historical run**, both front doors. The kept logs are in [results/](results/).

## Files in this folder

| File | What it is |
| --- | --- |
| [`HOW-TO-RUN.md`](HOW-TO-RUN.md) | written by the test harness during the kept run: the exact command line each engine was started with, how each front door was started with the files below, a sample `curl` request, and every request the test sent with its result. Start here to repeat the run by hand |
| [`aegis/own-host.yaml`](aegis/own-host.yaml) | the Aegis configuration the own-host door loaded: the shipped profile with the engine address substituted, plus a `host:` section |
| [`aegis/knox-aegis.yaml`](aegis/knox-aegis.yaml) | the Aegis configuration loaded inside Knox (no `host:` section) |
| [`aegis/knox-topology.xml`](aegis/knox-topology.xml) | the Knox topology: demo LDAP authentication, group mapping, `AclsAuthz`, and the `AEGIS_MCP` service |
| [`results/`](results/) | the kept transcripts, Aegis audit journals and Surefire report of that run |
| [`IcebergFrontDoorsTest.java`](../../aegis-validation/src/test/java/io/github/vaquarkhan/aegis/validation/IcebergFrontDoorsTest.java) | the test itself: how the engine is started and seeded, and every assertion |

## How the run works

1. `mvn -B -Preal-engines -DskipTests install` resolves the engine (the `examples/aegis-validation-*-server` module for this adopter, or a distribution named in this README) and builds the Aegis jars, including the Knox bundle.
2. `IcebergFrontDoorsTest` starts the real engine in its own process, as section 1 of `HOW-TO-RUN.md` shows, and creates the data the scenario works on through the engine's own API, outside Aegis.
3. Without Knox: the test builds Aegis from `aegis/own-host.yaml`, starts Aegis's own server on loopback, and sends MCP `tools/call` requests over HTTP as each person, with that person's bearer token.
4. With Knox: the test copies the Aegis bundle into a stock Knox 3.0.0 `ext/`, writes `aegis/knox-topology.xml` to `conf/topologies/`, starts Knox's demo LDAP and the gateway, and sends the same requests with HTTP Basic as the same people. Knox authenticates and checks the route ACL; Aegis governs the MCP call.
5. After every governed write, the test reads the engine directly to check what really changed. Each request and response goes to the transcript, and the Aegis audit journal is copied next to it.

## What runs

| Service | Started like | Used by |
| --- | --- | --- |
| Iceberg REST catalog, `org.apache.iceberg.rest.RESTCatalogServer` from `iceberg-open-api` 1.10.1 | the `apache/iceberg-rest-fixture` image: `CATALOG_*` environment variables, a JDBC catalog on a SQLite file, `InMemoryFileIO` | the `iceberg` source (`type: http`) |

The catalog runs in its own JVM from the classpath `examples/aegis-validation-iceberg-server` resolves.
The test creates the namespaces `sales` and `finance` and the tables `sales.orders`, `finance.ledger`
and, per front door, `sales.scratch_ownhost` or `sales.scratch_knox`, directly against the catalog,
outside Aegis.

The source is declarative HTTP, so nothing is added to Knox `ext/` beyond the Aegis bundle.

## What the test proves

Besides the approval flow, this profile shows two refusals that no approval can lift: a resource scope
(analysts only see `sales` and `marketing`) and a `DENY` rule (nobody drops a `finance` table).

| Step | Caller | Call | Own host | Knox |
| --- | --- | --- | --- | --- |
| 1 | `guest` (analyst) | `iceberg_list_tables` on `sales` | allowed; the real tables | allowed |
| 2 | `admin` (outsider) | the same read | Aegis denies: `SCOPE_DENIED` (no scope covers a caller without groups) | Knox `AclsAuthz`: HTTP 403 |
| 3 | `guest` | `iceberg_list_tables` on `finance` | `SCOPE_DENIED` | `SCOPE_DENIED` |
| 4 | `sam` (data engineer) | `iceberg_drop_table` on `finance.ledger` | `POLICY_DENIED` by rule `never-drop-finance`; the table is still there | same |
| 5 | `guest` | `iceberg_drop_table` on the scratch table | `READONLY_CALLER` | `READONLY_CALLER` |
| 6 | `sam` | the same drop | `APPROVAL_REQUIRED`; the table is still there | same |
| 7 | `sam` | dry run (`TOOL` mode: `iceberg_load_table` reads the exact table) | the real schema, a receipt and an approval request; the table is still there | same |
| 8 | `sam` | approves own request | HTTP 403 | same |
| 9 | `tom` (data steward) | approves | HTTP 200 | same |
| 10 | `sam` | drop with token and receipt | allowed; the catalog answers 404 for the table, `finance.ledger` is still 200 | same |
| 11 | `sam` | same token again | denied: single use | same |

Table state is read from the catalog REST API directly, outside Aegis.

| Person | Groups in this profile |
| --- | --- |
| `guest` | `analysts` |
| `sam` | `data-engineers` |
| `tom` | `data-stewards` |
| `admin` | none, and outside the Knox ACL |

## Run it

```powershell
$env:AEGIS_KNOX_HOME = 'C:\knox\knox-3.0.0'     # from ../knox/install-knox.ps1
../run-example.ps1 iceberg -Keep
```

```bash
export AEGIS_KNOX_HOME=/opt/knox/knox-3.0.0     # from ../knox/install-knox.sh
../run-example.sh iceberg --keep
```

The first build downloads `iceberg-open-api-1.10.1.jar` (about 210 MB; it bundles the catalog server).

## Results

| File | What it is |
| --- | --- |
| `results/own-host-transcript.log`, `results/knox-transcript.log` | every request and response, per front door |
| `results/own-host-audit.jsonl`, `results/knox-audit.jsonl` | the Aegis journals; verify with `--verify-audit` |
| `results/test-report.txt` | the Surefire summary |

## Limits

- The catalog has no authentication here: `ICEBERG_CATALOG_TOKEN` is sent as the gateway's bearer
  credential and the fixture ignores it. A production catalog (Polaris, Gravitino, a cloud catalog)
  checks it.
- `InMemoryFileIO`: tables have metadata but no data files, so `purgeRequested=true` has nothing to
  purge. The drop itself is the real catalog operation.
- `iceberg_create_namespace` and `iceberg_set_table_properties` (`MUTATE`) follow the same approval
  path; `ExampleConfigsTest` covers them against a local stub server, not against this catalog.
- Single-level namespaces only, as the profile states.
