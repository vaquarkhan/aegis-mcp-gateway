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
# Apache Spark with Apache Iceberg: with Knox and without Knox

The automated run of the [Spark + Iceberg tutorial](../../../docs/TUTORIAL-SPARK-ICEBERG.md). One gateway
governs two real engines at once, through both front doors:

- an Apache Iceberg 1.10.1 REST catalog (`RESTCatalogServer`) writing real metadata and Parquet files to a
  local warehouse;
- an Apache Spark 4.0.1 Spark Connect server with `iceberg-spark-runtime-4.0_2.13:1.10.1` and a `lake`
  catalog on that REST catalog.

The configuration is the tutorial's own
[`aegis-spark-iceberg.yaml`](../../../adopters/tutorials/spark-iceberg/aegis-spark-iceberg.yaml), with only the
catalog and Spark ports substituted.

| File | What it is |
| --- | --- |
| [`HOW-TO-RUN.md`](HOW-TO-RUN.md) | written by the harness during the kept run: engine command lines, both front doors, a sample request, and every step with its result |
| [`aegis/own-host.yaml`](aegis/own-host.yaml) | the configuration the own-host door loaded (with a `host:` section) |
| [`aegis/knox-aegis.yaml`](aegis/knox-aegis.yaml), [`aegis/knox-topology.xml`](aegis/knox-topology.xml) | the configuration and topology used inside Knox 3.0.0 |
| [`results/`](results/) | transcripts, both audit journals, the Knox plugin installation log and the Surefire report |
| [`SparkIcebergFrontDoorsTest.java`](../../aegis-validation-spark-connect/src/test/java/io/github/vaquarkhan/aegis/validation/SparkIcebergFrontDoorsTest.java) | the test and every assertion |

## What the run proved (2026-10-03)

| Step | Caller | Call | Own host | Knox |
| --- | --- | --- | --- | --- |
| 1 | reader | `iceberg_list_tables {namespace: sales}` | allowed | allowed |
| 2 | outsider | the same | `SCOPE_DENIED` | Knox `AclsAuthz`: HTTP 403 |
| 3 | reader | `spark_preview_table lake.sales.orders` | allowed, 3 rows | allowed |
| 4 | reader | `iceberg_list_tables {namespace: finance}`, `spark_preview_table lake.finance.ledger` | `SCOPE_DENIED` | `SCOPE_DENIED` |
| 5 | engineer | `iceberg_drop_table finance.ledger` | `POLICY_DENIED` (never-drop-finance) | same |
| 6 | reader | `spark_sql DELETE ... WHERE id = 3` | `READONLY_CALLER` | same |
| 7 | engineer | the same, no approval | `APPROVAL_REQUIRED`; 3 rows remain | same |
| 8 | engineer | dry run (`EXPLAIN`) | receipt and approval request; 3 rows remain | same |
| 9 | engineer | approves own request | HTTP 403 | same |
| 10 | approver | approves | HTTP 200 | same |
| 11 | engineer | executes with token and receipt | allowed; Spark reads 2 rows back | same |
| 12 | engineer | replays the same approval | `APPROVAL_REQUIRED`, already used | same |
| 13 | reader | `iceberg_list_snapshots sales.orders` | allowed; the delete is a new snapshot | allowed |

Surefire: 2 tests, 0 failures, 0 skipped. Both journals pass `--verify-audit` (own host 21 records, Knox 18).

On Windows the run adds `aegis-windows-localfs` to the catalog and Spark classpaths (Hadoop's local file
system without the `winutils.exe` permission calls); see the tutorial's Windows note.

## Run it

```powershell
$env:AEGIS_KNOX_HOME = 'C:\knox\knox-3.0.0'     # from ../knox/install-knox.ps1
../run-example.ps1 spark-iceberg -Keep
```

```bash
export AEGIS_KNOX_HOME=/opt/knox/knox-3.0.0
../run-example.sh spark-iceberg --keep
```
