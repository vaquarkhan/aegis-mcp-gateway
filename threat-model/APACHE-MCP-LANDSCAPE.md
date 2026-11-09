# Apache MCP landscape: proposals vs what Aegis has built

Date: 2026-11-07. This is the accuracy-checked version of the "what Apache communities are proposing
vs what we already built" comparison. Every Aegis claim below was checked against the repository, and
the links are real repository paths, not placeholders.

Repository: https://github.com/vaquarkhan/aegis-mcp-gateway

## Status legend

| Status | Meaning |
| --- | --- |
| BUILT | A deployable adapter config plus a tutorial from a retained own-host and Knox run, with tests |
| CONFIG-ONLY | A deployable adapter config with a contract test, but no retained real-engine run and no tutorial yet |
| NOT BUILT | No profile today; candidate for a config, or on the roadmap, or complementary only |

## Headline numbers (verified)

- 27 deployable adapter configurations and 123 MCP tools, all loaded and governance-tested by
  `AdapterConfigsTest`, listed in `adopters/configurations/adapters/`.
- 27 project tutorials in `docs/tutorials/` plus the Spark-with-Iceberg walkthrough, each from a
  retained own-host and Knox run.
- 4 profiles are CONFIG-ONLY and awaiting a real-engine run: Atlas, Cassandra, Impala, Paimon.

## Corrections to the earlier draft

- AsterixDB and SkyWalking are now **config-only** (contract-tested adapters added in this pass): a
  deployable config and a contract test, but no tutorial and no retained real-engine run yet.
- Hudi is still **not built**: it has no standalone HTTP surface (it runs through Spark or Flink
  procedures), so it stays on the roadmap rather than shipping a fictional HTTP config.
- Cassandra and Atlas are **config-only**: they have an adapter config and a contract test, but no
  tutorial and no retained real-engine run. The earlier "profiles and tutorials" claim is only half
  true for them (and for Impala and Paimon).
- Profile names like `mcp-adapter-hive` or `mcp-adapter-flink` are not the real paths. The configs are
  `adopters/configurations/adapters/<name>` (REST adapters) and
  `adopters/configurations/<name>` (the Flink, Kafka, Spark, Spark Connect and Iceberg reference configs).
- External links (project dev@ threads, Jira issues, and CVE identifiers) in the draft were not
  verified here. Confirm each CVE id on NVD and each thread URL on the project's own archive before
  publishing. The Aegis-side links below are verified.

## The mapping

| Apache project | What they propose or build (as reported, verify at source) | Aegis status | Aegis links (repository paths) |
| --- | --- | --- | --- |
| Burr | Optional `burr[mcp]` server with inspect, annotate, run groups | BUILT | [config](https://github.com/vaquarkhan/aegis-mcp-gateway/tree/main/adopters/configurations/adapters/burr), [tutorial](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/main/docs/tutorials/burr.md) |
| Hive | Separate or combined HMS and HS2 MCP servers | BUILT (JDBC converter) | [config](https://github.com/vaquarkhan/aegis-mcp-gateway/tree/main/adopters/configurations/adapters/hive), [tutorial](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/main/docs/tutorials/hive.md) |
| Kafka | Expose Kafka Admin and Schema Registry to agents | BUILT (reference) | [config](https://github.com/vaquarkhan/aegis-mcp-gateway/tree/main/adopters/configurations/kafka), [tutorial](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/main/docs/tutorials/kafka.md) |
| Flink | Flink SQL Gateway to agents | BUILT (reference) | [config](https://github.com/vaquarkhan/aegis-mcp-gateway/tree/main/adopters/configurations/flink), [tutorial](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/main/docs/tutorials/flink.md) |
| Spark | Spark Connect to agents | BUILT (reference) | [config](https://github.com/vaquarkhan/aegis-mcp-gateway/tree/main/adopters/configurations/spark-connect), [tutorial](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/main/docs/tutorials/spark.md) |
| Iceberg | REST catalog to agents | BUILT (reference) | [config](https://github.com/vaquarkhan/aegis-mcp-gateway/tree/main/adopters/configurations/iceberg), [tutorial](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/main/docs/tutorials/iceberg.md) |
| Superset | Experimental `mcp_service`, including `update_dashboard` | BUILT | [config](https://github.com/vaquarkhan/aegis-mcp-gateway/tree/main/adopters/configurations/adapters/superset), [tutorial](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/main/docs/tutorials/superset.md) |
| Airflow | Inspect DAG state, trigger DAG runs via MCP | BUILT | [config](https://github.com/vaquarkhan/aegis-mcp-gateway/tree/main/adopters/configurations/adapters/airflow), [tutorial](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/main/docs/tutorials/airflow.md) |
| Doris | Standalone `doris-mcp-server` (a SQL-injection CVE was reported) | BUILT | [config](https://github.com/vaquarkhan/aegis-mcp-gateway/tree/main/adopters/configurations/adapters/doris), [tutorial](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/main/docs/tutorials/doris.md) |
| Pulsar | Observability and admin to agents | BUILT | [config](https://github.com/vaquarkhan/aegis-mcp-gateway/tree/main/adopters/configurations/adapters/pulsar), [tutorial](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/main/docs/tutorials/pulsar.md) |
| NiFi | Flow control to agents | BUILT | [config](https://github.com/vaquarkhan/aegis-mcp-gateway/tree/main/adopters/configurations/adapters/nifi), [tutorial](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/main/docs/tutorials/nifi.md) |
| Pinot, Druid, Hadoop, HBase, Solr, Ranger | Query and admin surfaces to agents | BUILT | configs under [adapters/](https://github.com/vaquarkhan/aegis-mcp-gateway/tree/main/adopters/configurations/adapters) and tutorials under [docs/tutorials/](https://github.com/vaquarkhan/aegis-mcp-gateway/tree/main/docs/tutorials) |
| Cassandra | MCP over the data/admin surface | CONFIG-ONLY (no tutorial) | [config](https://github.com/vaquarkhan/aegis-mcp-gateway/tree/main/adopters/configurations/adapters/cassandra); run tracked in [ROADMAP.md](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/main/docs/ROADMAP.md) |
| Atlas | MCP over the metadata catalog | CONFIG-ONLY (no tutorial) | [config](https://github.com/vaquarkhan/aegis-mcp-gateway/tree/main/adopters/configurations/adapters/atlas); run tracked in ROADMAP |
| Impala, Paimon | MCP over query and catalog surfaces | CONFIG-ONLY (no tutorial) | configs under [adapters/](https://github.com/vaquarkhan/aegis-mcp-gateway/tree/main/adopters/configurations/adapters); runs tracked in ROADMAP |
| SkyWalking | SkyWalking MCP (SSRF and injection CVEs reported) | CONFIG-ONLY (no tutorial) | [config](https://github.com/vaquarkhan/aegis-mcp-gateway/tree/main/adopters/configurations/adapters/skywalking); 3 read tools over the OAP `POST /graphql` endpoint; run tracked in [ROADMAP.md](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/main/docs/ROADMAP.md) |
| AsterixDB | Proposed MCP server over LSM datasets | CONFIG-ONLY (no tutorial) | [config](https://github.com/vaquarkhan/aegis-mcp-gateway/tree/main/adopters/configurations/adapters/asterixdb); 4 tools over the AsterixDB HTTP API; run tracked in [ROADMAP.md](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/main/docs/ROADMAP.md) |
| Hudi | Table-service MCP | NOT BUILT (roadmap) | Needs Spark or Flink procedures, not a standalone REST surface; tracked under "Later" in [ROADMAP.md](https://github.com/vaquarkhan/aegis-mcp-gateway/blob/main/docs/ROADMAP.md) |
| Juneau, Camel | MCP wire fixtures (Juneau), Camel JBang MCP tools | NOT BUILT (complementary) | Protocol and routing level; Aegis adds governance in front, no profile is implied |
| Knox | Host integration | BUILT (front door) | [aegis-knox3-extension](https://github.com/vaquarkhan/aegis-mcp-gateway/tree/main/aegis-knox3-extension) |

Also built and not in the draft: ActiveMQ, BookKeeper, Calcite, CouchDB, Flume, Ignite, Storm, ZooKeeper
(configs under `adopters/configurations/adapters/`, tutorials under `docs/tutorials/`).

## What it would take to add the missing ones

- AsterixDB and SkyWalking: done in this pass as CONFIG-ONLY adapters (declarative config, `AdapterConfigsTest`
  entry, contract route harness), the same tier as Atlas and Cassandra. A tutorial and a "passed" mark still
  require a retained real run, which this host cannot do.
- Hudi: stays on the roadmap until a Spark or Flink procedure source exists; there is no standalone REST
  surface to govern, so no honest HTTP config is possible.
- Cassandra, Atlas, Impala, Paimon: already config-only; promoting them to BUILT needs a real-engine run
  (tracked in ROADMAP.md).
