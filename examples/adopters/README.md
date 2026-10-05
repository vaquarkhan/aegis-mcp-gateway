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
# Apache adopter examples: verified support and known gaps

Each Aegis adopter profile is an Apache-engine integration configuration under
[`adopters/configurations`](../../adopters/configurations). A profile is **supported by Aegis** when it
loads and its tool shapes are governed by `AdapterConfigsTest` / `ExampleConfigsTest`; that is not the same
as a real-engine claim. A row says **real two-door verified** only when the exact profile ran against the
real engine through both Aegis front doors:

- **Own host:** `aegis-gateway` on loopback with static bearer identities.
- **Knox:** a stock Apache Knox 3.0.0 process, the Aegis extension in `ext/`, ShiroProvider against Knox's
  demo LDAP, `Default` identity assertion group mapping, and `AclsAuthz` on the route. Knox authenticates
  and admits the request; Aegis parses and governs the MCP request body.

Every verified folder contains the exact Aegis YAML used by both doors, the actual Knox topology, the
engine configuration files written by the test, a `HOW-TO-RUN.md` with engine and front-door commands, a
sample MCP request, and retained result logs. The other **6** folders (Atlas, Cassandra, Hive, Impala,
Paimon, Ranger) contain `aegis/canonical.yaml`: an exact reference copy of the profile covered by
`AdapterConfigsTest`, clearly labeled as not captured from a real-engine run. Tokens, approval receipts
and credentials are not kept.

At this point: **26 profiles have real two-door evidence** (including Docker-backed Airflow, CouchDB, Doris,
Druid, Hadoop, HBase, NiFi, Pinot, Storm, and Superset); **6 more** are profile/contract supported and still
need a product Keep (see [adopter real-engine status](../../docs/evidence/ADOPTER-REAL-ENGINE-STATUS.md)):
Hive is already `type: jdbc` (same source family as Calcite); Paimon/Cassandra/Ranger are HTTP like other
Docker Keeps; Impala SQL can move to JDBC but still needs Impala; Atlas needs its full stack. Spark Connect
does not substitute for Atlas/Ranger/Cassandra/Paimon or for Arrow Flight / Beam. ASF release-process items
are tracked separately in [docs/ROADMAP.md](../../docs/ROADMAP.md) and are not treated as open product defects.

## Complete adopter status

| Adopter/profile | Aegis config | Real engine | Own host | Knox | Evidence / reason |
| --- | --- | --- | --- | --- | --- |
| [ActiveMQ Classic](activemq/) | supported | 6.3.2 | passed | passed | real Jolokia; profile corrected for POST-only Jolokia, Origin and basic auth |
| [Airflow](airflow/) | supported | 2.9.3 | passed | passed | official `apache/airflow` Docker standalone; `/api/v1` with basic auth; Keep 2026-10-05 |
| [Atlas](atlas/) | supported | not run | not run | not run | HTTP v2 profile OK; Keep needs Atlas + Kafka + Solr + HBase (not closable via JDBC or Spark Connect) |
| [BookKeeper](bookkeeper/) | supported | 4.18.1 | passed | passed | real bookie HTTP admin plus real ZooKeeper metadata and ledgers |
| [Burr](burr/) | supported | 0.42.0 | passed | passed | real Apache Burr (incubating) tracking server and traces recorded by Burr itself |
| [Calcite](calcite/) | supported | Calcite 1.42.0 / Avatica 1.28.0 | passed | passed | real Avatica server; read-only profile refusal tested |
| [Cassandra](cassandra/) | supported | not run | not run | not run | Sidecar HTTP profile; Keep needs Sidecar + cluster (CQL would be a separate jdbc profile) |
| [CouchDB](couchdb/) | supported | 3.4.2 | passed | passed | official `couchdb` Docker image; admin basic auth; rerun 2026-10-05 |
| [Doris](doris/) | supported | 4.1.3 | passed | passed | official Doris all-in-one Docker; HTTP SQL with set password; Keep 2026-10-05 |
| [Druid](druid/) | supported | 28.0.1 | passed | passed | Druid nano-quickstart Docker with bash launcher; Keep 2026-10-05 |
| [Flume](flume/) | supported | 1.11.0 | passed | passed | real agent with live HTTP monitoring counters |
| [Flink](flink/) | supported | 1.20.1 | passed | passed | real Flink MiniCluster JobManager REST API |
| [Hadoop HDFS](hadoop/) | supported | 3.2.1 | passed | passed | Docker NameNode and DataNode; WebHDFS governed path; Keep 2026-10-05 |
| [HBase](hbase/) | supported | REST via `dajobe/hbase` | passed | passed | HBase REST Docker; governed table reads/writes; Keep 2026-10-05 |
| [Hive](hive/) | supported | not run | not run | not run | **Already `type: jdbc`**. HS2 Docker+beeline proven; FrontDoors Keep is roadmap-only (no incomplete test in tree) |
| [Iceberg REST catalog](iceberg/) | supported | 1.10.1 | passed | passed | official REST catalog server with a JDBC catalog |
| [Ignite](ignite/) | supported | 2.18.0 | passed | passed | real Ignite node with REST v1 |
| [Impala](impala/) | supported | not run | not run | not run | Coordinator HTTP ops today; SQL should use jdbc like Hive when an Impala endpoint exists |
| [Kafka and Kafka Connect](kafka/) | supported | 3.9.1 | passed | passed | real KRaft broker and standalone Connect worker |
| [Existing MCP server](existing-mcp-server/) | supported | MCP SDK 2.2.0 plus filesystem server | passed | passed | real HTTP/stdio downstream MCP; 2026 stateless and 2025 session paths recorded |
| [NiFi](nifi/) | supported | 1.28.1 | passed | passed | official NiFi Docker; HTTP + revision-aware calls; Keep 2026-10-05 |
| [Paimon](paimon/) | supported | not run | not run | not run | REST catalog HTTP (Iceberg-like); Keep needs a real Paimon REST catalog process |
| [Pinot](pinot/) | supported | 1.2.0 | passed | passed | official `apachepinot/pinot` QuickStart Docker image; disposable sample tables; rerun 2026-10-05 |
| [Pulsar](pulsar/) | supported | 4.0.13 | passed | passed | real broker/bookie with external real ZooKeeper metadata on Windows |
| [Ranger](ranger/) | supported | not run | not run | not run | Admin HTTP API; Keep needs Ranger Admin + DB |
| [Solr](solr/) | supported | 9.10.1 | passed | passed | real SolrCloud node with embedded ZooKeeper |
| [Spark](spark/) | supported | 4.0.1 | passed | passed | real application UI REST and standalone Master REST server |
| [Spark Connect](spark-connect/) | supported | 4.0.1 | passed | passed | real Spark Connect server; source plugin installed into Knox `ext/` |
| [Storm](storm/) | supported | 2.6.2 | passed | passed | Docker Nimbus, Supervisor, UI and ZooKeeper; host-built topology jar; Keep 2026-10-05 |
| [Superset](superset/) | supported | 4.0.2 | passed | passed | official Superset Docker; governed chart/dataset APIs; Keep 2026-10-05 |
| [ZooKeeper](zookeeper/) | supported | 3.9.3 | passed | passed | real ZooKeeper data tree and AdminServer; source plugin installed into Knox `ext/`; rerun 2026-10-05 ([E2E record](../../docs/evidence/E2E-VALIDATION-2026-10-05.md)) |

**Tutorial scenario (combines two engines, not counted as an adapter profile):**

| Scenario | Aegis config | Real engines | Own host | Knox | Evidence |
| --- | --- | --- | --- | --- | --- |
| [Spark + Iceberg](spark-iceberg/) | tutorial governance file | Spark 4.0.1 Spark Connect, Iceberg 1.10.1 REST catalog | passed | passed | 2026-10-03 run of [`docs/TUTORIAL-SPARK-ICEBERG.md`](../../docs/TUTORIAL-SPARK-ICEBERG.md) on the current source and `knox-bundle` jar |

**What “passed” means:** Surefire reports 0 failures, 0 errors and 0 skipped for the two parameterized
front doors; the retained audit journals pass `--verify-audit`; and a secret scan of retained inputs and
logs is clean. A Knox case skipped because `AEGIS_KNOX_HOME` is unset is never reported as passed.

**What “not run” means:** Aegis parses and governs the profile in the deterministic default build, but this
repository does not contain an end-to-end result log for that real engine. No YAML snapshot or result log is
recorded for those rows. The constraints above are the actual blockers observed on this Windows machine,
not compatibility claims.

## Open a verified example

Start with a verified folder’s `README.md`, then open these files in this order:

1. `HOW-TO-RUN.md`: exact engine startup, standalone and Knox startup, curl request, and the actual
   step/result table.
2. `aegis/own-host.yaml`: exact own-host Aegis configuration used.
3. `aegis/knox-aegis.yaml` and `aegis/knox-topology.xml`: exact configuration and topology used inside Knox.
4. `engine/`: the real engine configuration file(s) written by the test.
5. `results/`: transcript, audit journals, optional Knox `ext/` plugin installation log, and Surefire result.

The root [README](../../README.md) and the security/conformance documents explain the security model. The
walkthroughs demonstrate it against each engine: Aegis classifies calls, derives typed resources, enforces
policy and scopes, requires independent approval for writes, requires a dry-run receipt for destructive
calls, checks egress and journals each decision. Knox adds edge authentication and route admission; it does
not inspect the MCP tool body and it does not replace Aegis governance.

## Run a verified example

```powershell
# Install Knox once (the installer verifies Apache's SHA-512).
./knox/install-knox.ps1 -Destination C:\knox
$env:AEGIS_KNOX_HOME = 'C:\knox\knox-3.0.0'

# For an engine-specific distribution, follow its folder README first, then run:
./run-example.ps1 pulsar -Keep
```

```bash
./knox/install-knox.sh /opt/knox
export AEGIS_KNOX_HOME=/opt/knox/knox-3.0.0
./run-example.sh pulsar --keep
```

`-Keep` copies the current run’s generated YAML, topology, engine files, walkthrough and evidence into that
folder. `MAVEN_CMD` can point at `mvn.cmd` if Maven is not on `PATH`. For **ZooKeeper** and **A2A**, the
script also runs `mvn -Preal-engines` on the typed source module so Knox receives `target/plugin.classpath`
as well as the source jar.

## Verify kept evidence

```bash
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar \
  --verify-audit examples/adopters/pulsar/results/knox-audit.jsonl
```

Approval tokens and dry-run receipts are replaced by `<redacted>` in the transcripts.
