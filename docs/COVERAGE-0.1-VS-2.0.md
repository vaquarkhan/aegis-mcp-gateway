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
# Aegis 0.1 and 2.0 coverage contract

This is a behavioral comparison, not a module-count comparison. It answers three questions:

1. Does every Aegis 0.1 tool have an explicit 2.0 verdict?
2. Did any tool become less guarded?
3. What is the actual current 2.0 shipped surface?

**Naming.** The legacy line is **Aegis 0.1** (release `0.1.0`). There is no separate product called
“Aegis 1.0” in this repository. People sometimes say “1.0” when they mean that pre-2.0 line; this
document and `Coverage01VsTwoTest` always mean **0.1 → 2.0**.

The executable authority is `Coverage01VsTwoTest`. It reads the frozen 0.1 inventory extracted from the
tracked 0.1 `tools.yaml` resources and the 2.0 configuration files. A changed count or a 0.1 tool with
no verdict fails the build. This page explains that executable contract. The product-boundary record is
[HISTORY.md](HISTORY.md).

## Product shape: 0.1 versus 2.0

| Area | Aegis 0.1 | Aegis 2.0 |
| --- | --- | --- |
| Backend integrations | One Java `mcp-adapter-<engine>` module per engine | Named YAML under `adopters/configurations/adapters/<adopter>/`, plus shared profiles (Flink, Iceberg, Kafka, Spark) |
| Typed protocols | Mostly adapter-local | Shared modules when a real client protocol requires one: JDBC, ZooKeeper, Kafka Admin, Spark Connect, A2A |
| Governance | In the 0.1 core | One governance engine shared by own-host and optional Knox |
| Planned typed sources | Product-specific clients in the 0.1 line | Arrow, Beam, Hudi, Kudu, and Ozone are later typed sources, not part of this release |
| Kafka and Kafka Connect | Kafka adapter surface | One shared **Kafka** profile and tutorial covering the broker **and** Connect (`docs/tutorials/kafka.md`, `docs/TUTORIAL-KAFKA-AND-KAFKA-CONNECT.md`) |
| Repository | Separate 0.1 tree / release | 2.0 is the repository root; 0.1 modules are not in this build |

**Coverage is not the same as a real-engine Keep.** This page accounts for every 0.1 tool and the
shipped 2.0 surface. Whether a profile has a kept own-host + Knox run against a live product is tracked
in [evidence/ADOPTER-REAL-ENGINE-STATUS.md](evidence/ADOPTER-REAL-ENGINE-STATUS.md) and
[VALIDATION.md](VALIDATION.md).

## The current numbers

| Measure | Aegis 0.1 | Aegis 2.0 | Enforcement |
| --- | ---: | ---: | --- |
| Engine surfaces in the frozen inventory | 33 | 28 in this release, 5 planned later | frozen inventory and verdict test |
| Implemented tools inherited from or replacing a 0.1 capability | 156 | **152** | exact `Coverage01VsTwoTest` verdict accounting |
| Non-Burr tools in the comparison surface | not applicable | **155** | `EXPECTED_COMPARABLE_TOOLS` (152 successors + 2 Kafka Admin reads + 1 Superset chart preview) |
| New 2.0 Kafka Admin tools | 0 | 2 | cluster and topic-configuration reads through the real AdminClient |
| New Burr tools | 0 | 10 | Apache Burr (incubating) is a 2.0 addition, not a 0.1 successor |
| Configuration files participating in the comparison | 33 Java adapter modules | 25 deployable adapter configurations plus shared reference configs | parsed from YAML |
| Shipped tools participating in the comparison | 156 | **165** | `EXPECTED_SHIPPED_TOOLS` (155 non-Burr + 10 Burr) |
| Adapter configurations | n/a | **25 deployable adapter configurations and 116 tools** | exact `AdapterConfigsTest` assertion |
| Implemented tools that became less guarded | n/a | **0** | class-order assertion |

How the 2.0 totals fit together:

```
152  successors of 0.1 capabilities (kept / renamed / backend-changed)
+  2  new Kafka Admin read tools
+  1  Superset chart read (dry-run preview for chart delete)
= 155  comparable (non-Burr) tools
+ 10  Burr (2.0-only)
= 165  shipped comparison tools
```

Aegis 2.0 ships 152 successors of the 0.1 tool inventory, plus Kafka Admin reads, a Superset chart preview, and Apache Burr (incubating). Arrow, Beam, Hudi, Kudu, and Ozone are planned typed sources and are not part of this release.

## Side-by-side: 0.1 engine surface versus Aegis 2.0

The frozen 0.1 inventory is
[`aegis-0.1-tool-inventory.tsv`](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/aegis-2.0-review-20261009/examples/aegis-examples/src/test/resources/aegis-0.1-tool-inventory.tsv)
(one row per tool, 33 engines, 156 tools). Aegis 2.0 ships **25 deployable adapter configurations**
under `adopters/configurations/adapters/<engine>/`, plus shared reference profiles (Flink, Iceberg, Kafka,
Spark) and typed sources (JDBC, ZooKeeper, Kafka Admin, Spark Connect, A2A, and others). The table below
is a per-engine view of the same contract `Coverage01VsTwoTest` enforces; regenerate the skeleton with
`python tools/coverage-engine-table.py` when the inventory or adapter layout changes, then keep the
notes in this file.

| 0.1 engine | 0.1 tools | Aegis 2.0 deployable adapter | Notes |
| --- | ---: | --- | --- |
| `activemq` | 3 | yes | |
| `airflow` | 5 | yes | |
| `arrow` | 3 | typed source / reference | entire surface deliberately absent (no real HTTP API) |
| `atlas` | 4 | yes | contract config; real-engine Keep still pending ([ROADMAP.md](ROADMAP.md)) |
| `beam` | 4 | typed source / reference | entire surface deliberately absent (no real HTTP API) |
| `bookkeeper` | 4 | yes | |
| `calcite` | 3 | yes | backend changed to Avatica JDBC (`aegis-source-jdbc`) |
| `cassandra` | 4 | yes | contract config; real-engine Keep still pending |
| `couchdb` | 4 | yes | |
| `doris` | 3 | yes | |
| `druid` | 4 | yes | |
| `flink` | 19 | shared profile | |
| `flume` | 2 | yes | |
| `hadoop` | 4 | yes | |
| `hbase` | 4 | yes | |
| `hive` | 4 | yes | `run_sql_readonly` successor is `jdbc_run_query`; FrontDoors Keep on roadmap (no scaffold in tree) |
| `hudi` | 4 | typed source / reference | entire surface deliberately absent (no real HTTP API) |
| `iceberg` | 12 | shared profile | |
| `ignite` | 4 | yes | |
| `impala` | 3 | yes | contract config; real-engine Keep still pending |
| `kafka` | 8 | shared profile | Kafka broker **and** Connect; plus 2 new Admin read tools in 2.0 |
| `kudu` | 3 | typed source / reference | entire surface deliberately absent (no real HTTP API) |
| `nifi` | 5 | yes | |
| `ozone` | 4 | typed source / reference | entire surface deliberately absent (no real HTTP API) |
| `paimon` | 4 | yes | contract config; real-engine Keep still pending |
| `pinot` | 4 | yes | |
| `pulsar` | 6 | yes | |
| `ranger` | 4 | yes | contract config; real-engine Keep still pending |
| `solr` | 4 | yes | |
| `spark` | 5 | shared profile | submission/kill backend changed to Spark standalone REST |
| `storm` | 4 | yes | |
| `superset` | 4 | yes | plus chart-read preview tool in 2.0 |
| `zookeeper` | 4 | yes | data-tree tools use `aegis-source-zookeeper`, not contrib REST |
| `burr` | 0 | yes | 2.0-only: 10 tools (Apache Burr incubating) |

For every 0.1 tool row, the test assigns exactly one verdict (`kept`, `renamed`, `backend changed`, or
`deliberately absent`). The narrative tables in the sections below list the non-`kept` cases only.

## The 156 verdicts

| Verdict | Count | Meaning |
| --- | ---: | --- |
| kept | 111 | The 2.0 config exposes the same capability, normally with an engine prefix to avoid collisions. |
| renamed | 4 | The capability is present under a more precise 2.0 name. |
| backend changed | 7 | The capability is present but now reaches the real product through a different supported surface. |
| deliberately absent or roadmap | 34 | The tool is not shipped in 2.0; the reason is explicit and tested. |
| **total** | **156** | Every frozen 0.1 row has exactly one verdict. |

## Named capability changes

### Renamed

| Aegis 0.1 | Aegis 2.0 | Reason |
| --- | --- | --- |
| `iceberg/get_table` | `iceberg_load_table` | The Iceberg REST Catalog API calls the operation `loadTable`. |
| `iceberg/alter_table` | `iceberg_set_table_properties` | The implementation changes table properties, so the name says what it changes. |
| `kafka/query_schema_registry` | `schema_list_subjects`, `schema_latest_version`, `schema_check_compatibility` | Three endpoint and schema shapes replace one broad tool. |
| `hive/run_sql_readonly` | `jdbc_run_query` | HiveServer2 is reached through its JDBC driver. The product-stack run is on the [roadmap](ROADMAP.md#engine-coverage-verification). |

### Backend changed

| Aegis 0.1 | 0.1 surface | Aegis 2.0 | 2.0 surface |
| --- | --- | --- | --- |
| `spark/submit_batch` | Apache Livy batch API | `spark_submit_batch` | Spark standalone Master REST submission API |
| `spark/kill_application` | Apache Livy or YARN | `spark_kill_submission` | Spark standalone Master REST submission API |
| `calcite/get_status` | an operator-built HTTP root | `jdbc_server_info` | Avatica JDBC metadata |
| `calcite/explain_sql` | Avatica JDBC | `jdbc_run_query` | Avatica JDBC, with `EXPLAIN` accepted by the read-only guard |
| `calcite/run_sql_readonly` | Avatica JDBC | `jdbc_run_query` | Avatica JDBC, with a single read statement and bounded result |
| `zookeeper/list_children` | `zookeeper-contrib-rest` | `zookeeper_list_children` | ZooKeeper Java client `getChildren` |
| `zookeeper/delete_znode` | `zookeeper-contrib-rest` | `zookeeper_delete_node` | ZooKeeper Java client delete with an expected version |

The Calcite and ZooKeeper tools use the protocols those products publish. Calcite and Avatica are reached through JDBC. ZooKeeper znodes use the ZooKeeper client. `aegis-source-jdbc` is exercised against Apache Derby, and `aegis-source-zookeeper` against an embedded ZooKeeper server. Target-version runs are in [VALIDATION.md](VALIDATION.md).

### Deliberately absent or roadmap

| Capability | Status and reason |
| --- | --- |
| Flink jar upload | Not exposed. Upload is a release/CI operation; governed execution starts from an approved jar. |
| Flink SQL Gateway reads and writes | Not exposed yet. SQL Gateway needs a session lifecycle, not one declarative request. |
| Iceberg maintenance procedures | Not exposed through the catalog. They execute in Spark or Flink, not in an Iceberg REST catalog. |
| Iceberg unbounded commit transaction | Not exposed. It cannot be bound accurately to one typed resource. |
| Spark SQL read | Present through the real Spark Connect source rather than the classic Spark monitoring source. |
| Kafka DLQ inspection and three Admin writes | Roadmap items for the Kafka Admin source. |
| Livy and YARN | Roadmap sources. They are outside the current Spark standalone source. |
| Arrow Flight (3 tools) | Planned typed Flight client. See the [roadmap](ROADMAP.md#later-sources-and-engine-operations). |
| Beam JobService (4 tools) | Planned typed JobService client. See the [roadmap](ROADMAP.md#later-sources-and-engine-operations). |
| Hudi table services (4 tools) | Planned typed source. See the [roadmap](ROADMAP.md#later-sources-and-engine-operations). |
| Kudu table operations (3 tools) | Planned typed source. See the [roadmap](ROADMAP.md#later-sources-and-engine-operations). |
| Ozone volume operations (4 tools) | Planned typed source. See the [roadmap](ROADMAP.md#later-sources-and-engine-operations). |

The detailed source-by-source entry is [ROADMAP.md](ROADMAP.md).

## What this comparison proves, and what it does not

It proves 0.1 capability accounting and guard-strength preservation. It does not prove every upstream
endpoint exists in every deployed version. `AdapterConfigsTest` exercises the **25 deployable adapter
configurations and 116 tools** through Aegis governance; many HTTP adapters currently use a contract
route harness. Arrow, Beam, Hudi, Kudu, and Ozone are planned typed sources and are not in this release. The authoritative state,
including real, embedded-real, historical, contract and pending evidence, is
[VALIDATION.md](VALIDATION.md) and [evidence/CLAIMS-MATRIX.md](evidence/CLAIMS-MATRIX.md).

## Related reading

- [HISTORY.md](HISTORY.md): product boundary between the 0.1 line and 2.0
- [VALIDATION.md](VALIDATION.md): executed evidence, versions and limits
- [evidence/ADOPTER-REAL-ENGINE-STATUS.md](evidence/ADOPTER-REAL-ENGINE-STATUS.md): which adopters have kept two-door runs
- [ROADMAP.md](ROADMAP.md): remaining implementation and real-engine work
- [ARCHITECTURE.md](ARCHITECTURE.md): the control plane all source types share
- [TUTORIAL-KAFKA-AND-KAFKA-CONNECT.md](TUTORIAL-KAFKA-AND-KAFKA-CONNECT.md): Kafka broker and Connect under one profile
