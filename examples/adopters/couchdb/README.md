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
# Apache CouchDB example: with Knox and without Knox

This example runs the shipped profile
[`adopters/configurations/adapters/couchdb/couchdb.yaml`](../../../adopters/configurations/adapters/couchdb/couchdb.yaml)
against a real Apache CouchDB 3.4.2 node (official `couchdb` Docker image), through Aegis's own server and
inside a stock Apache Knox 3.0.0 gateway. Only the CouchDB address, egress CIDR and admin basic-auth
environment are substituted.

Status: **retained historical run**, both front doors. Kept logs land in
[results/](results/) after `./run-example.ps1 couchdb -Keep`.

## Files in this folder

| File | What it is |
| --- | --- |
| [`HOW-TO-RUN.md`](HOW-TO-RUN.md) | written by the test harness during a kept run |
| [`aegis/own-host.yaml`](aegis/own-host.yaml) | own-host configuration from a kept run |
| [`aegis/knox-aegis.yaml`](aegis/knox-aegis.yaml) / [`knox-topology.xml`](aegis/knox-topology.xml) | Knox path from a kept run |
| [`aegis/canonical.yaml`](aegis/canonical.yaml) | reference copy of the shipped profile |
| [`results/`](results/) | transcripts, audit journals, Surefire report |
| [`CouchdbFrontDoorsTest.java`](../../aegis-validation/src/test/java/io/github/vaquarkhan/aegis/validation/CouchdbFrontDoorsTest.java) | starts CouchDB, seeds databases, asserts both doors |

## Prerequisites

- Docker Engine reachable as `docker` on `PATH`
- `AEGIS_KNOX_HOME` for the Knox door (otherwise Knox is skipped, never passed)
- `mvn` and JDK 17+

```powershell
$env:AEGIS_KNOX_HOME = 'C:\knox\knox-3.0.0'
../run-example.ps1 couchdb -Keep
```

## What the run proves

List and get database (READ), outsider denial, destructive delete of a scratch database with independent
approval and dry-run receipt, engine state checked before/after outside Aegis, audit journals verify.
