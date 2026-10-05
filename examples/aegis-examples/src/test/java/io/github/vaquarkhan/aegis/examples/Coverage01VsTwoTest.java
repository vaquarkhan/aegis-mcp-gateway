/*
 * Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vaquarkhan.aegis.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.config.ConfigLoader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Feature-coverage regression: every tool Aegis 0.1 shipped must still be accounted for in 2.0.
 *
 * <p>This exists because a tool can disappear in a release without anything failing. The 0.1 surface
 * is frozen in {@code src/test/resources/aegis-0.1-tool-inventory.tsv} (156 rows, 33 engines, extracted
 * from the 0.1 {@code tools.yaml} resources the 0.1 catalog actually loaded). Every row must resolve to
 * exactly one of four verdicts:
 *
 * <ul>
 *   <li><b>kept</b> - the 2.0 configs expose {@code <engine>_<tool>} (2.0 prefixes tool names with the
 *       engine so two adapters can never collide) or the bare {@code <tool>};</li>
 *   <li><b>renamed</b> - listed in {@link #RENAMED}, possibly to several 2.0 tools where one 0.1 tool
 *       was split;</li>
 *   <li><b>backend changed</b> - listed in {@link #BACKEND_CHANGED}: the capability survived but it now
 *       reaches the engine through different software, and the tool name may have changed with it. That
 *       is a coverage change a reader must be told about, so the entry records the old and the new
 *       backend by name;</li>
 *   <li><b>deliberately absent</b> - listed in {@link #ABSENT} with the reason.</li>
 * </ul>
 *
 * <p>Anything else fails the build. The tables are also checked in the other direction, so an entry
 * naming a 0.1 tool that never existed, or a 2.0 target that does not exist, fails too: the tables
 * cannot rot into fiction.
 *
 * <p>It additionally asserts that no tool became <em>less</em> guarded. A 0.1 {@code READ} may become
 * {@code MUTATE} in 2.0 (more controls apply), but a 0.1 {@code DESTRUCTIVE} that came back as
 * {@code READ} would be a silent downgrade of an approval gate, so the class ordinal may only rise.
 *
 * <p>The narrative version of this audit is {@code docs/COVERAGE-0.1-VS-2.0.md}; this test is what
 * keeps it true.
 */
class Coverage01VsTwoTest {

    static final int EXPECTED_01_ROWS = 156;
    static final int EXPECTED_01_ENGINES = 33;
    /** All configuration files participating in this comparison, including Burr's 10-tool addition. */
    static final int EXPECTED_SHIPPED_TOOLS = 164;
    /** Non-Burr 2.0 tools participating in the comparison; this includes 2.0 additions as well as successors. */
    static final int EXPECTED_COMPARABLE_TOOLS = 154;

    /** 0.1 {@code engine/tool} to the 2.0 tool name(s) that carry it. */
    static final Map<String, List<String>> RENAMED = Map.of(
            // Iceberg's REST loadTable is what 0.1 called get_table.
            "iceberg/get_table", List.of("iceberg_load_table"),
            // 0.1 alter_table's description is "Alter Iceberg table properties", which is exactly what
            // the REST updateTable properties call does, so the 2.0 name is the honest one.
            "iceberg/alter_table", List.of("iceberg_set_table_properties"),
            // One 0.1 tool split into three, because "query the schema registry" is three endpoints.
            "kafka/query_schema_registry", List.of("schema_list_subjects", "schema_latest_version",
                    "schema_check_compatibility"),
            // Hive has no REST SQL endpoint: 0.1 and early 2.0 both invented one. The capability is real
            // through HiveServer2's own JDBC driver, so the tool moved to the jdbc source.
            "hive/run_sql_readonly", List.of("jdbc_run_query"));

    /** 0.1 {@code engine/tool} to [2.0 tool, 0.1 backend, 2.0 backend]. */
    static final Map<String, List<String>> BACKEND_CHANGED = Map.ofEntries(
            Map.entry("spark/submit_batch", List.of("spark_submit_batch", "Apache Livy batch API",
                    "Spark standalone Master REST submission server (POST /v1/submissions/create)")),
            Map.entry("spark/kill_application", List.of("spark_kill_submission", "Apache Livy or YARN",
                    "Spark standalone Master REST submission server (POST /v1/submissions/kill)")),
            // Apache Calcite is a library: there is no Calcite server and so no Calcite HTTP API. 0.1's
            // own descriptions called these a "HTTP facade", meaning the operator had to build the
            // service themselves. Calcite's supported remote surface is Avatica, whose HTTP protocol is
            // an RPC transport rather than paths a declarative http source can build, so 2.0 goes through
            // Avatica's JDBC driver.
            Map.entry("calcite/get_status", List.of("jdbc_server_info",
                    "an HTTP root the operator had to build (GET /)",
                    "Avatica's JDBC driver (DatabaseMetaData) in aegis-source-jdbc")),
            Map.entry("calcite/explain_sql", List.of("jdbc_run_query",
                    "an HTTP facade the operator had to build (POST /explain)",
                    "Avatica's JDBC driver: EXPLAIN is a statement, and the read-only classifier accepts "
                            + "EXPLAIN as a leading keyword, so it goes through jdbc_run_query")),
            Map.entry("calcite/run_sql_readonly", List.of("jdbc_run_query",
                    "an HTTP facade the operator had to build (POST /sql)",
                    "Avatica's JDBC driver (a single read statement, capped rows) in aegis-source-jdbc")),
            // 0.1's own description said "via REST facade": the znode paths belonged to
            // zookeeper-contrib-rest, a separate contrib artifact a normal ensemble does not run, so the
            // operator had to stand that up themselves. 2.0 reaches the data tree the way every other
            // ZooKeeper client does, through ZooKeeper's own Java client, so there is nothing to install.
            Map.entry("zookeeper/list_children", List.of("zookeeper_list_children",
                    "zookeeper-contrib-rest (GET /znodes/v1/<path>), which the operator had to run",
                    "ZooKeeper's own Java client (getChildren) in aegis-source-zookeeper")),
            // Same move, and the name gained _node for consistency with the other data-tree tools.
            Map.entry("zookeeper/delete_znode", List.of("zookeeper_delete_node",
                    "zookeeper-contrib-rest (DELETE /znodes/v1/<path>), which the operator had to run",
                    "ZooKeeper's own Java client (delete, with the expected version) in aegis-source-zookeeper")));

    /** 0.1 {@code engine/tool} to the reason it is not in 2.0. */
    static final Map<String, String> ABSENT = Map.ofEntries(Map.entry(
            "hive/get_webui",
            "Port 10002 is HiveServer2's web UI and it serves HTML for humans, not JSON. A tool that hands "
                    + "a scraped page to a model is a prompt-injection vector wearing a monitoring hat."),
            Map.entry("hive/list_sessions",
                    "Same web UI. Hive has no REST endpoint that returns sessions as data."),
            Map.entry("hive/kill_query",
                    "0.1 offered it as DELETE /queries/{id}, which is not a Hive endpoint. Hive cancels a "
                            + "running statement through the JDBC Statement handle of the session that started "
                            + "it, which a separate gateway call cannot reach."),
            Map.entry("flink/upload_jar",
                    "POST /jars/upload is multipart/form-data; an HTTP source builds a JSON body. Uploading "
                            + "is a release step done outside the governed surface, and running a jar is the "
                            + "governed action."),
            Map.entry("flink/run_sql_readonly",
                    "The Flink SQL Gateway needs a session lifecycle (open session, submit statement, poll "
                            + "for results): three or more calls, not one request."),
            Map.entry("flink/run_sql_ddl_dml",
                    "Same SQL Gateway session lifecycle as flink/run_sql_readonly."),
            Map.entry("iceberg/expire_snapshots",
                    "An engine procedure (Spark or Flink), not a catalog REST call. The REST catalog cannot "
                            + "run it."),
            Map.entry("iceberg/remove_orphan_files",
                    "An engine procedure, not a catalog REST call."),
            Map.entry("iceberg/rewrite_data_files",
                    "An engine procedure, not a catalog REST call."),
            Map.entry("iceberg/commit_transaction",
                    "An unbounded change set cannot be bound to one typed resource, so it cannot be governed "
                            + "honestly by object. Deliberately not exposed rather than exposed without a "
                            + "resource."),
            Map.entry("iceberg/dry_run_maintenance",
                    "Superseded by the built-in dry run: every MUTATE and DESTRUCTIVE tool has a validate "
                            + "phase, so a separate read-only companion tool is no longer needed."),
            Map.entry("spark/run_sql_readonly",
                    "Served by the Spark Connect source instead (examples/aegis-spark-connect), which is "
                            + "tested against a real Spark 4.0.1 Connect server."),
            Map.entry("activemq/purge_queue",
                    "Purge lives on the queue's own MBean, whose name contains the queue name, and ActiveMQ 6 "
                            + "accepts Jolokia only as a POST with the MBean name inside the JSON body. Building "
                            + "that name from an argument is string splicing, which Aegis does not do. The "
                            + "governed destructive tool is activemq_remove_queue (Broker MBean removeQueue, the "
                            + "queue as a separate argument), verified against a real 6.3.2 broker."),
            Map.entry("kafka/inspect_dlq",
                    "Reading records needs a consumer, not the AdminClient. Roadmap: extend the kafka-admin "
                            + "plugin source."));

    /** The three 0.1 Kafka Admin writes 2.0 has not built yet, kept separate so the reason is one line. */
    static final Set<String> KAFKA_ADMIN_ROADMAP = Set.of(
            "kafka/alter_config", "kafka/reset_offsets", "kafka/delete_records");

    static final String KAFKA_ADMIN_REASON =
            "Needs the kafka-admin plugin source extending with the corresponding AdminClient call. "
                    + "On the roadmap and not claimed anywhere as present.";

    /**
     * Entire 0.1 engine surfaces removed from deployable configuration because their model was not a
     * real backend API. Every old tool still receives an explicit absent verdict below.
     */
    static final Map<String, String> UNIMPLEMENTED_ENGINES = Map.of(
            "arrow", "Arrow Flight is gRPC; the former HTTP facade routes do not exist. A typed Flight source is required.",
            "beam", "Beam JobService is gRPC; the former HTTP facade routes do not exist. A typed JobService source is required.",
            "hudi", "Hudi has no table-service REST API matching the former routes. Use engine procedures through a real source.",
            "kudu", "The former routes do not match a released Kudu API. A version-specific client source is required.",
            "ozone", "Ozone Manager has no volume REST API matching the former routes. A real Ozone protocol source is required.");

    record Tool(String engine, String name, String cls, String description) {}

    static int strictness(String cls) {
        return switch (cls) {
            case "READ" -> 0;
            case "MUTATE" -> 1;
            case "DESTRUCTIVE" -> 2;
            default -> -1;
        };
    }

    /** The frozen 0.1 surface. */
    static List<Tool> inventory() throws IOException {
        List<Tool> out = new ArrayList<>();
        try (InputStream in = Coverage01VsTwoTest.class.getResourceAsStream("/aegis-0.1-tool-inventory.tsv")) {
            assertTrue(in != null, "aegis-0.1-tool-inventory.tsv must be on the test classpath");
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String l = line.strip();
                if (l.isEmpty() || l.startsWith("#")) {
                    continue;
                }
                String[] f = l.split("\t", -1);
                assertEquals(4, f.length, "malformed inventory row: " + l);
                out.add(new Tool(f[0], f[1], f[2], f[3]));
            }
        }
        return out;
    }

    /** engine to {toolName: class}, read from the shipped 2.0 configuration files. */
    static Map<String, Map<String, String>> shipped() throws IOException {
        Map<String, Map<String, String>> out = new TreeMap<>();
        List<Path> files = new ArrayList<>(AdopterConfigurations.adapters());
        for (String profile : List.of("flink", "spark", "iceberg", "kafka")) {
            files.add(AdopterConfigurations.profile(profile));
        }
        for (Path p : files) {
            String engine = p.getFileName().toString().replace(".yaml", "");
            Map<String, String> tools = out.computeIfAbsent(engine, k -> new TreeMap<>());
            Map<String, Object> root = ConfigLoader.load(p);
            for (Object src : asList(root.get("sources"))) {
                Object toolsNode = asMap(src).get("tools");
                if (toolsNode instanceof List<?> list) {                 // HTTP source: a list of specs
                    for (Object t : list) {
                        Map<String, Object> m = asMap(t);
                        tools.put(String.valueOf(m.get("name")), String.valueOf(m.getOrDefault("class", "READ")));
                    }
                } else if (toolsNode instanceof Map<?, ?> map) {          // plugin source: name to overrides
                    for (Map.Entry<?, ?> e : map.entrySet()) {
                        Map<String, Object> m = asMap(e.getValue());
                        tools.put(String.valueOf(e.getKey()), String.valueOf(m.getOrDefault("class", "READ")));
                    }
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    @SuppressWarnings("unchecked")
    static List<Object> asList(Object o) {
        return o instanceof List ? (List<Object>) o : List.of();
    }

    @Test
    void everyAegis01ToolIsAccountedForIn20() throws Exception {
        List<Tool> old = inventory();
        Map<String, Map<String, String>> now = shipped();
        assertEquals(EXPECTED_01_ROWS, old.size(), "the frozen 0.1 inventory changed size");
        assertEquals(EXPECTED_01_ENGINES, old.stream().map(Tool::engine).distinct().count());
        // An engine can be absent only when this test carries one explicit reason for its entire old surface.
        List<String> missingEngines = old.stream().map(Tool::engine).distinct()
                .filter(e -> !now.containsKey(e) && !UNIMPLEMENTED_ENGINES.containsKey(e)).sorted().toList();
        assertTrue(missingEngines.isEmpty(), "0.1 engines with neither a 2.0 config nor an explicit absent verdict: "
                + missingEngines);

        List<String> unaccounted = new ArrayList<>();
        List<String> downgraded = new ArrayList<>();
        Map<String, String> verdicts = new LinkedHashMap<>();

        for (Tool t : old) {
            String key = t.engine() + "/" + t.name();
            Map<String, String> engine = now.getOrDefault(t.engine(), Map.of());

            if (UNIMPLEMENTED_ENGINES.containsKey(t.engine())) {
                verdicts.put(key, "deliberately absent: " + UNIMPLEMENTED_ENGINES.get(t.engine()));
                continue;
            }

            if (BACKEND_CHANGED.containsKey(key)) {
                String target = BACKEND_CHANGED.get(key).get(0);
                if (!engine.containsKey(target)) {
                    unaccounted.add(key + ": BACKEND_CHANGED names " + target + ", which 2.0 does not have");
                } else {
                    verdicts.put(key, "backend changed -> " + target);
                    checkClass(t, engine.get(target), target, downgraded);
                }
                continue;
            }
            if (RENAMED.containsKey(key)) {
                for (String target : RENAMED.get(key)) {
                    if (!engine.containsKey(target)) {
                        unaccounted.add(key + ": RENAMED names " + target + ", which 2.0 does not have");
                    } else {
                        checkClass(t, engine.get(target), target, downgraded);
                    }
                }
                verdicts.put(key, "renamed -> " + String.join(", ", RENAMED.get(key)));
                continue;
            }
            if (ABSENT.containsKey(key) || KAFKA_ADMIN_ROADMAP.contains(key)) {
                verdicts.put(key, "deliberately absent");
                continue;
            }
            String prefixed = t.engine() + "_" + t.name();
            if (engine.containsKey(prefixed)) {
                verdicts.put(key, "kept as " + prefixed);
                checkClass(t, engine.get(prefixed), prefixed, downgraded);
                continue;
            }
            if (engine.containsKey(t.name())) {
                verdicts.put(key, "kept as " + t.name());
                checkClass(t, engine.get(t.name()), t.name(), downgraded);
                continue;
            }
            unaccounted.add(key + " (0.1 class " + t.cls() + ", \"" + t.description() + "\") has no 2.0 tool, "
                    + "no RENAMED entry, no BACKEND_CHANGED entry and no ABSENT reason");
        }

        assertTrue(unaccounted.isEmpty(), "Aegis 0.1 tools dropped without an explicit verdict:\n  "
                + String.join("\n  ", unaccounted));
        assertTrue(downgraded.isEmpty(), "tools became LESS guarded in 2.0:\n  " + String.join("\n  ", downgraded));
        assertEquals(old.size(), verdicts.size(), "every 0.1 tool must get exactly one verdict");
    }

    private static void checkClass(Tool t, String newClass, String newName, List<String> downgraded) {
        int a = strictness(t.cls());
        int b = strictness(newClass);
        if (a >= 0 && b >= 0 && b < a) {
            downgraded.add(t.engine() + "/" + t.name() + " was " + t.cls() + " in 0.1 but " + newName
                    + " is " + newClass + " in 2.0");
        }
    }

    /** The verdict tables may not name a 0.1 tool that never existed. */
    @Test
    void theVerdictTablesCannotRot() throws Exception {
        Set<String> keys = new TreeSet<>();
        for (Tool t : inventory()) {
            keys.add(t.engine() + "/" + t.name());
        }
        List<String> bogus = new ArrayList<>();
        for (String k : RENAMED.keySet()) {
            if (!keys.contains(k)) {
                bogus.add("RENAMED." + k);
            }
        }
        for (String k : BACKEND_CHANGED.keySet()) {
            if (!keys.contains(k)) {
                bogus.add("BACKEND_CHANGED." + k);
            }
        }
        for (String k : ABSENT.keySet()) {
            if (!keys.contains(k)) {
                bogus.add("ABSENT." + k);
            }
        }
        for (String k : KAFKA_ADMIN_ROADMAP) {
            if (!keys.contains(k)) {
                bogus.add("KAFKA_ADMIN_ROADMAP." + k);
            }
        }
        assertTrue(bogus.isEmpty(), "verdict table entries naming tools Aegis 0.1 never had: " + bogus);

        // A tool cannot be both absent and renamed.
        for (String k : RENAMED.keySet()) {
            assertFalse(ABSENT.containsKey(k) || BACKEND_CHANGED.containsKey(k), k + " has two verdicts");
        }
        for (String k : ABSENT.keySet()) {
            assertFalse(BACKEND_CHANGED.containsKey(k) || KAFKA_ADMIN_ROADMAP.contains(k), k + " has two verdicts");
        }
        assertFalse(KAFKA_ADMIN_REASON.isBlank());
        Set<String> inventoryEngines = inventory().stream().map(Tool::engine).collect(java.util.stream.Collectors.toSet());
        assertTrue(inventoryEngines.containsAll(UNIMPLEMENTED_ENGINES.keySet()),
                "an absent-engine verdict names an engine outside the frozen inventory");
    }

    /** The exact count prevents removed or added capabilities being hidden in prose. */
    @Test
    void thePublishedTwoZeroSurfaceMatchesTheEvidenceCount() throws Exception {
        Map<String, Map<String, String>> now = shipped();
        int total = now.values().stream().mapToInt(Map::size).sum();
        int comparable = total - now.getOrDefault("burr", Map.of()).size();
        assertEquals(EXPECTED_SHIPPED_TOOLS, total, "the published total changed; update the coverage and claim matrix");
        assertEquals(EXPECTED_COMPARABLE_TOOLS, comparable,
                "the implemented 0.1 successor surface changed; update the coverage and claim matrix");
        assertTrue(comparable < EXPECTED_01_ROWS,
                "this assertion records that removing fictional routes makes 2.0 honest, not a tool-count superset");
    }
}
