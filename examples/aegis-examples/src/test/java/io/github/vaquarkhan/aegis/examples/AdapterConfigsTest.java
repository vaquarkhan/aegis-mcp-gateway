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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.config.BuildContext;
import io.github.vaquarkhan.aegis.config.ConfigLoader;
import io.github.vaquarkhan.aegis.config.Gateway;
import io.github.vaquarkhan.aegis.config.GatewayFactory;
import io.github.vaquarkhan.aegis.engine.CallOutcome;
import io.github.vaquarkhan.aegis.engine.audit.AuditChainVerifier;
import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.json.JsonValues;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.model.DenyCodes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every config-driven adapter in {@code adopters/configurations/adapters}, exercised through the
 * Aegis governance harness.
 *
 * <p>These configs replace the Aegis 0.1 Java adapter modules ({@code mcp-adapter-*}): the engines
 * that only needed an HTTP call per tool need no Java at all in 2.0, so what used to be 29 Maven
 * modules is now 29 configuration files. This test is what makes that claim checkable.
 *
 * <p>It is deliberately driven <b>from the configuration files themselves</b>: it reads each YAML,
 * discovers every tool with its class and required arguments, and calls all of them. A tool added to a
 * config is therefore tested automatically, and a tool that was dropped shows up as a count change.
 * The only hand-written part is {@link #contractHarness}, the reviewed route shapes of each engine's API; a
 * request the config builds wrongly does not match a route, the harness answers 404, and the assertion
 * that no call comes back as an error fails.
 *
 * <p>For every tool it proves: a READ works for a plain user; a MUTATE or DESTRUCTIVE tool is refused
 * to that user ({@code READONLY_CALLER}); and the validate, approve, promote flow (plus a dry-run
 * receipt for DESTRUCTIVE) reaches the backend exactly once.
 */
class AdapterConfigsTest {

    /**
     * Twenty-four replace usable parts of a 0.1 adapter; {@code burr} is new in 2.0. The Arrow,
     * Beam, Hudi, Kudu and Ozone files were removed because their declared HTTP routes do not exist.
     * Keeping them as product-named configuration made a contract harness look like an integration.
     */
    static final int EXPECTED_ADAPTERS = 25;

    /** The 116 tools are only those remaining in deployable configuration files. */
    static final int EXPECTED_TOOLS = 116;

    @TempDir
    Path dir;

    static final Map<String, String> ENV = Map.of(
            "AEGIS_SIGNING_KEY", "example-signing-key-0123456789-abcdefghij");

    /** A representative value per argument name, so every tool can actually be called. */
    static Object sample(String engine, String arg) {
        if ("path".equals(arg)) {
            // Path-typed arguments ({path/}): a real, nested path for each engine.
            return "hadoop".equals(engine) ? "/user/hive/warehouse" : "/brokers/ids";
        }
        return switch (arg) {
            // Burr tracking server. "__none__" is the literal sentinel Burr puts in the path when an
            // application has no partition key, so it is the realistic value, not a placeholder.
            case "projectId" -> "demo";
            case "appId" -> "app-1";
            case "partitionKey" -> "__none__";
            case "stepName" -> "answer_question";
            case "sequenceId" -> 0L;
            case "annotationId" -> 1L;
            case "tags" -> List.of("reviewed");
            case "thumbsUp" -> Boolean.TRUE;
            case "dagId" -> "orders_etl";
            case "queue" -> "orders.dlq";
            case "ticket" -> "t-1";
            case "action" -> "rebalance";
            case "query" -> "name:orders";
            case "guid" -> "3f2b1c";
            case "jobId" -> "job-1";
            case "ledgerId" -> "42";
            case "sql" -> "select 1";
            case "keyspace", "database" -> "sales";
            case "table" -> "orders";
            case "db" -> "orders";
            case "datasource" -> "wikipedia";
            case "queryId" -> "q-1";
            case "cache" -> "sessions";
            case "groupId" -> "pg-1";
            case "volume" -> "vol1";
            case "bucket" -> "b1";
            case "tenant" -> "public";
            case "namespace" -> "default";
            case "topic" -> "orders";
            case "service" -> "hive_prod";
            case "policyId" -> "7";
            case "collection" -> "products";
            case "q" -> "*:*";
            case "id" -> "topology-1";
            case "chartId" -> "12";
            case "revisionVersion" -> 0;
            case "revisionClientId" -> "aegis-test";
            default -> throw new IllegalStateException("no sample value for argument '" + arg + "' (" + engine + ")");
        };
    }

    /**
     * The documented endpoint shapes of each engine's HTTP API. Written from the API surface, not from
     * the config, so a path the config renders wrongly falls through to a 404.
     */
    static RestRouteContractHarness contractHarness(String engine) throws Exception {
        RestRouteContractHarness m = new RestRouteContractHarness();
        String ok = "{\"ok\":true}";
        switch (engine) {
            // ActiveMQ 6 ships jolokia-access.xml with POST only: every request is a JSON body to one URL.
            // examples/adopters/activemq runs the profile against a real 6.3.2 broker.
            case "activemq" -> m.route("POST", "/api/jolokia", 200, "{\"status\":200,\"value\":{\"ok\":true}}");
            case "airflow" -> m.route("GET", "/health", 200, ok).route("GET", "/dags", 200, ok)
                    .route("GET", "/dags/[^/]+", 200, ok).route("POST", "/dags/[^/]+/dagRuns", 200, ok)
                    .route("DELETE", "/dags/[^/]+", 200, ok);
            // Burr tracking server. These routes come from the route declarations in
            // apache/burr burr/tracking/server/run.py, not from prose. A harness derived from the
            // same guess as a config validates nothing (see docs/DEFECT-LOG.md).
            case "burr" -> m.route("GET", "/api/v0/version", 200, "{\"version\":\"0.41.0\"}")
                    .route("GET", "/api/v0/ready", 200, "true")
                    .route("GET", "/api/v0/metadata/app_spec", 200,
                            "{\"indexing\":false,\"snapshotting\":false,\"supports_demos\":true,\"supports_annotations\":true}")
                    .route("GET", "/api/v0/projects", 200, "[{\"name\":\"demo\",\"id\":\"demo\",\"num_apps\":2}]")
                    .route("GET", "/api/v0/indexing_jobs", 200, "[]")
                    .route("GET", "/api/v0/[^/]+/annotations", 200, "[]")
                    .route("GET", "/api/v0/[^/]+/[^/]+/apps", 200,
                            "{\"applications\":[{\"app_id\":\"app-1\",\"num_steps\":4,\"tags\":{}}],"
                                    + "\"total\":1,\"has_another_page\":false}")
                    .route("GET", "/api/v0/[^/]+/[^/]+/[^/]+/apps", 200,
                            "{\"application\":{\"entrypoint\":\"answer_question\"},\"children\":[],\"steps\":[]}")
                    .route("POST", "/api/v0/[^/]+/[^/]+/[^/]+/[0-9]+/annotations", 200, "{\"id\":1}")
                    .route("PUT", "/api/v0/[^/]+/[0-9]+/update_annotations", 200, "{\"id\":1}");
            case "atlas" -> m.route("GET", "/api/atlas/v2/types/typedefs", 200, ok)
                    .route("GET", "/api/atlas/v2/search/basic", 200, ok)
                    .route("GET", "/api/atlas/v2/entity/guid/[^/]+", 200, ok)
                    .route("DELETE", "/api/atlas/v2/entity/guid/[^/]+", 200, ok);
            case "bookkeeper" -> m.route("GET", "/api/v1/bookie/info", 200, ok)
                    .route("GET", "/api/v1/bookie/list_bookies", 200, ok)
                    .route("GET", "/api/v1/ledger/list", 200, ok)
                    .route("DELETE", "/api/v1/ledger/delete", 200, ok);
            // calcite is not here: Apache Calcite is a library with no server and no HTTP API, so it is
            // a jdbc source reached through Avatica's driver, so this HTTP harness has no route for it.
            case "cassandra" -> m.route("GET", "/api/v1/cassandra/native/keyspaces", 200, ok)
                    .route("GET", "/api/v1/cassandra/native/keyspaces/[^/]+/tables", 200, ok)
                    .route("GET", "/api/v1/cassandra/native/token-ranges", 200, ok)
                    .route("DELETE", "/api/v1/cassandra/native/keyspaces/[^/]+/tables/[^/]+", 200, ok);
            case "couchdb" -> m.route("GET", "/_all_dbs", 200, ok).route("GET", "/[^/]+", 200, ok)
                    .route("PUT", "/[^/]+", 200, ok).route("DELETE", "/[^/]+", 200, ok);
            case "doris" -> m.route("GET", "/api/show_proc", 200, ok).route("GET", "/api/bootstrap", 200, ok)
                    .route("GET", "/rest/v1/system", 200, ok)
                    .route("POST", "/api/query/default_cluster/information_schema", 200, ok);
            case "druid" -> m.route("GET", "/druid/coordinator/v1/datasources", 200, ok)
                    .route("GET", "/druid/coordinator/v1/datasources/[^/]+", 200, ok)
                    .route("POST", "/druid/v2/sql", 200, ok)
                    .route("DELETE", "/druid/coordinator/v1/datasources/[^/]+", 200, ok);
            case "flume" -> m.route("GET", "/metrics", 200, ok).route("GET", "/", 200, ok);
            case "hadoop" -> m.route("GET", "/webhdfs/v1/.*", 200, ok).route("PUT", "/webhdfs/v1/.*", 200, ok)
                    .route("DELETE", "/webhdfs/v1/.*", 200, ok);
            case "hbase" -> m.route("GET", "/version/cluster", 200, ok).route("GET", "/", 200, ok)
                    .route("GET", "/[^/]+/schema", 200, ok).route("DELETE", "/[^/]+/schema", 200, ok);
            // hive is not here either: HiveServer2 has no REST control API, so it is a jdbc source
            // through the Hive driver, so this HTTP harness has no route for it.
            case "ignite" -> m.route("GET", "/ignite", 200, "{\"successStatus\":0,\"response\":{\"ok\":true}}");
            case "impala" -> m.route("GET", "/", 200, ok).route("GET", "/metrics", 200, ok)
                    .route("GET", "/queries", 200, ok).route("POST", "/cancel_query", 200, ok);
            case "nifi" -> m.route("GET", "/flow/about", 200, ok)
                    .route("GET", "/flow/process-groups/root", 200, ok)
                    .route("GET", "/process-groups/[^/]+", 200, ok)
                    .route("PUT", "/flow/process-groups/[^/]+", 200, ok);
            case "paimon" -> m.route("GET", "/v1/databases", 200, ok)
                    .route("GET", "/v1/databases/[^/]+/tables", 200, ok)
                    .route("GET", "/v1/databases/[^/]+/tables/[^/]+", 200, ok)
                    .route("DELETE", "/v1/databases/[^/]+/tables/[^/]+", 200, ok);
            case "pinot" -> m.route("GET", "/tables", 200, ok).route("GET", "/tables/[^/]+", 200, ok)
                    .route("GET", "/tenants", 200, ok).route("DELETE", "/tables/[^/]+", 200, ok);
            case "pulsar" -> m.route("GET", "/admin/v2/clusters", 200, ok)
                    .route("GET", "/admin/v2/tenants", 200, ok)
                    .route("GET", "/admin/v2/persistent/[^/]+/[^/]+", 200, ok)
                    .route("GET", "/admin/v2/persistent/[^/]+/[^/]+/[^/]+/stats", 200, ok)
                    .route("PUT", "/admin/v2/persistent/[^/]+/[^/]+/[^/]+", 200, ok)
                    .route("DELETE", "/admin/v2/persistent/[^/]+/[^/]+/[^/]+", 200, ok);
            case "ranger" -> m.route("GET", "/service/public/v2/api/service", 200, ok)
                    .route("GET", "/service/public/v2/api/service/[^/]+/policy", 200, ok)
                    .route("GET", "/service/public/v2/api/policy/[^/]+", 200, ok)
                    .route("DELETE", "/service/public/v2/api/policy/[^/]+", 200, ok);
            case "solr" -> m.route("GET", "/admin/info/system", 200, ok)
                    .route("GET", "/admin/collections", 200, ok).route("GET", "/[^/]+/select", 200, ok);
            case "storm" -> m.route("GET", "/api/v1/cluster/summary", 200, ok)
                    .route("GET", "/api/v1/topology/summary", 200, ok)
                    .route("GET", "/api/v1/topology/[^/]+", 200, ok)
                    .route("POST", "/api/v1/topology/[^/]+/kill/0", 200, ok);
            case "superset" -> m.route("GET", "/api/v1/database/", 200, ok)
                    .route("GET", "/api/v1/dataset/", 200, ok).route("GET", "/api/v1/chart/", 200, ok)
                    .route("GET", "/api/v1/chart/[^/]+", 200, ok)
                    .route("DELETE", "/api/v1/chart/[^/]+", 200, ok);
            // AdminServer only. These two command names are checked against ZooKeeper's own command
            // registry in ZooKeeperAdminCommandsTest. This harness checks request rendering; the
            // ZooKeeper registry is the oracle for whether each command exists. The data tree is not HTTP.
            case "zookeeper" -> m.route("GET", "/commands/ruok", 200, ok)
                    .route("GET", "/commands/stats", 200, ok);
            default -> {
                m.close();
                throw new IllegalStateException("no documented endpoint shapes for adapter '" + engine
                        + "'; add them so its config is actually exercised");
            }
        }
        return m;
    }

    /**
     * One tool as the configuration declares it. {@code allowedGroups} matters here: a READ tool may
     * be restricted to a narrower audience than the engine's plain users (Burr's trace read is, because
     * an agent trace carries prompts and model output), and such a tool must be exercised as a caller
     * who is actually allowed to see it rather than reported as a failure.
     */
    record Tool(String name, String toolClass, List<String> required, List<String> allowedGroups,
                String sourceType) {}

    /**
     * One adapter configuration, read from disk, with every source it declares.
     *
     * <p>There is more than one source type because not every engine has a REST control plane, and some
     * have one that covers only part of what they do. Hive only speaks SQL. ZooKeeper serves server
     * commands over HTTP but its data tree only over the client protocol. Writing a declarative HTTP
     * config for the part that has no HTTP API means inventing endpoints, which is how this project
     * shipped paths that do not exist (docs/DEFECT-LOG.md). Those parts use a converter source instead,
     * and are driven differently below: the tool surface a converter contributes is checked here, and
     * the converter's behaviour is tested in its own module against real software.
     */
    record Adapter(String engine, Path file, List<Source> sources) {

        List<Tool> tools() {
            List<Tool> all = new ArrayList<>();
            for (Source s : sources) {
                all.addAll(s.tools());
            }
            return all;
        }

        List<Source> httpSources() {
            return sources.stream().filter(s -> "http".equals(s.type())).toList();
        }
    }

    /** One source inside an adapter config. {@code baseUrl} is null for anything that is not HTTP. */
    record Source(String id, String type, String baseUrl, List<Tool> tools) {}

    /** Source types an adapter config is allowed to use, and which this harness knows how to drive. */
    static final java.util.Set<String> SOURCE_TYPES = java.util.Set.of("http", "jdbc", "zookeeper");

    static List<Adapter> adapters() throws Exception {
        List<Adapter> out = new ArrayList<>();
        for (Path p : AdopterConfigurations.adapters()) {
            String engine = p.getFileName().toString().replace(".yaml", "");
                Map<String, Object> cfg = ConfigLoader.load(p);
                List<Object> sources = JsonValues.asList(cfg.get("sources"));
                assertNotNull(sources, engine + ": sources");
                assertFalse(sources.isEmpty(), engine + ": at least one source");
                List<Source> parsed = new ArrayList<>();
                for (Object srcNode : sources) {
                    Map<String, Object> src = JsonValues.asObject(srcNode);
                    String sourceType = JsonValues.string(src, "type");
                    assertTrue(SOURCE_TYPES.contains(sourceType),
                            engine + ": source type '" + sourceType + "' is not one this harness knows about");
                    // An http source lists full tool specs; a converter or plugin source names tools the
                    // source already defines and only overrides their governance. Both shapes are valid.
                    List<Object> declared = new ArrayList<>();
                    Object toolsNode = src.get("tools");
                    if (toolsNode instanceof Map<?, ?> byName) {
                        for (Map.Entry<?, ?> e : byName.entrySet()) {
                            Map<String, Object> merged = new LinkedHashMap<>(JsonValues.asObject(e.getValue()));
                            merged.put("name", String.valueOf(e.getKey()));
                            declared.add(merged);
                        }
                    } else {
                        List<Object> asList = JsonValues.asList(toolsNode);
                        assertNotNull(asList, engine + ": tools must be a list or a map");
                        declared.addAll(asList);
                    }
                    List<Tool> tools = new ArrayList<>();
                    for (Object o : declared) {
                        Map<String, Object> t = JsonValues.asObject(o);
                        Map<String, Object> schema = JsonValues.object(t, "inputSchema");
                        List<Object> req = schema == null ? List.of() : JsonValues.asList(schema.get("required"));
                        List<String> names = new ArrayList<>();
                        for (Object r : req == null ? List.of() : req) {
                            names.add(String.valueOf(r));
                        }
                        List<Object> groups = JsonValues.asList(t.get("allowedGroups"));
                        List<String> allowed = new ArrayList<>();
                        for (Object gr : groups == null ? List.of() : groups) {
                            allowed.add(String.valueOf(gr));
                        }
                        tools.add(new Tool(JsonValues.string(t, "name"), JsonValues.string(t, "class"),
                                names, allowed, sourceType));
                    }
                    parsed.add(new Source(JsonValues.string(src, "id"), sourceType,
                            JsonValues.string(src, "baseUrl"), tools));
                }
                // Ids must be unique, and one of them carries the engine's own name so a reader can find
                // the primary surface. An engine with two surfaces (ZooKeeper) suffixes the secondary one.
                List<String> ids = parsed.stream().map(Source::id).sorted().toList();
                assertEquals(ids.size(), ids.stream().distinct().count(), engine + ": duplicate source ids " + ids);
                assertTrue(ids.contains(engine), engine + ": one source id must be the file name, got " + ids);
                for (Source s : parsed) {
                    assertEquals("http".equals(s.type()), s.baseUrl() != null,
                            engine + "/" + s.id() + ": baseUrl belongs to http sources and only to them");
                }
                out.add(new Adapter(engine, p, parsed));
        }
        return out;
    }

    /**
     * Loads one adapter config, rewriting only addresses: each {@code http} source's baseUrl points
     * at {@code url}, and the private CIDR becomes loopback so the contract harness is reachable.
     * Pass {@code url} as null for a config with no http source at all.
     *
     * <p>A {@code jdbc} source and a {@code zookeeper} source both connect lazily on first use, so
     * building a gateway here never needs a live backend. That is enough to prove the file parses and
     * that the tool surface is exactly what the config declares. Executing a converter's tools
     * needs real software, which is why that happens in the converter's own module rather than against a
     * contract harness written from the same assumptions as the config.
     */
    Gateway open(Adapter a, String url) throws Exception {
        String yaml = Files.readString(a.file());
        List<String[]> swaps = new ArrayList<>();
        for (Source s : a.httpSources()) {
            swaps.add(new String[] {s.baseUrl(), url});
        }
        if (!swaps.isEmpty()) {
            swaps.add(new String[] {"allowCidrs: [10.0.0.0/8]", "allowCidrs: [127.0.0.0/8]"});
        }
        for (String[] swap : swaps) {
            assertTrue(yaml.contains(swap[0]), a.engine() + " should contain " + swap[0]);
            yaml = yaml.replace(swap[0], swap[1]);
        }
        // The engines' own JDBC drivers (Hive, Avatica) are not on this module's test classpath, and the
        // real factory refuses a named driverClass it cannot load. The harness drops that one line so the
        // real DriverManager path is built; it connects only on a tool call, which this catalog test
        // never makes. The driver itself is exercised in the real-engine validation modules.
        yaml = yaml.replaceAll("(?m)^\\s*driverClass:.*\\R", "");
        Path p = dir.resolve(a.engine() + ".yaml");
        Files.writeString(p, yaml);
        // Every credential env var the config names gets a value, read out of the file rather than listed
        // here, so adding a jdbc source does not mean remembering to extend this harness.
        Map<String, String> env = new LinkedHashMap<>(ENV);
        java.util.regex.Matcher envNames =
                java.util.regex.Pattern.compile("(?:user|username|password|digest|token)Env:\\s*([A-Z0-9_]+)").matcher(yaml);
        while (envNames.find()) {
            env.put(envNames.group(1), "not-a-real-credential");
        }
        // The gateway path resolves userEnv/passwordEnv through BuildContext.env.
        return GatewayFactory.build(p, BuildContext.defaults(dir).withEnv(env::get));
    }

    Map<String, Object> args(Adapter a, Tool t) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String arg : t.required()) {
            m.put(arg, sample(a.engine(), arg));
        }
        return m;
    }

    @Test
    void everyAdapterConfigLoadsAndEveryToolIsGoverned() throws Exception {
        List<Adapter> adapters = adapters();
        assertEquals(EXPECTED_ADAPTERS, adapters.size(), "one config per converted 0.1 adapter");
        Map<String, Integer> perEngine = new TreeMap<>();
        int tools = 0;
        for (Adapter a : adapters) {
            perEngine.put(a.engine(), a.tools().size());
            tools += a.tools().size();
            CallerIdentity user = ExampleConfigsTest.who("ana", a.engine() + "-users");
            CallerIdentity op = ExampleConfigsTest.who("oscar", a.engine() + "-operators");
            CallerIdentity approver = ExampleConfigsTest.who("petra", a.engine() + "-approvers");
            if (a.httpSources().isEmpty()) {
                try (Gateway g = open(a, null)) {
                    List<String> exposed = g.engine().listTools(op).stream().map(t -> (String) t.get("name")).sorted().toList();
                    assertEquals(a.tools().stream().map(Tool::name).sorted().toList(), exposed,
                            a.engine() + ": every configured tool is exposed to an operator");
                }
                continue;
            }
            try (RestRouteContractHarness m = contractHarness(a.engine()); Gateway g = open(a, m.url())) {
                List<String> exposed = g.engine().listTools(op).stream().map(t -> (String) t.get("name")).toList();
                assertEquals(a.tools().stream().map(Tool::name).sorted().toList(), exposed.stream().sorted().toList(),
                        a.engine() + ": every configured tool is exposed to an operator");
                for (Tool t : a.tools()) {
                    // A converter's tools are surface-checked here and executed against real software in
                    // the converter's own module. Calling one here would either need a live backend or a
                    // contract harness of it, and a contract harness written beside the config proves only that they agree.
                    if (!"http".equals(t.sourceType())) {
                        continue;
                    }
                    Map<String, Object> args = args(a, t);
                    String where = a.engine() + " " + t.name() + " " + args;
                    switch (t.toolClass()) {
                        case "READ" -> {
                            // A READ restricted by allowedGroups is invisible to a plain user by design;
                            // assert that, then exercise it as a caller who may see it.
                            if (!t.allowedGroups().isEmpty()) {
                                assertEquals(DenyCodes.NOT_EXPOSED,
                                        ExampleConfigsTest.call(g, user, t.name(), args).decision().code(),
                                        where + ": a restricted READ must not be reachable by a plain user");
                            }
                            CallerIdentity reader = t.allowedGroups().isEmpty() ? user : op;
                            CallOutcome o = ExampleConfigsTest.call(g, reader, t.name(), args);
                            assertTrue(o.allowed(), where + " -> " + o.decision());
                            assertFalse(o.result().isError(), where + " -> " + Json.write(o.result().body())
                                    + " (last request: " + m.last() + ")");
                        }
                        case "MUTATE", "DESTRUCTIVE" -> {
                            assertEquals(DenyCodes.READONLY_CALLER,
                                    ExampleConfigsTest.call(g, user, t.name(), args).decision().code(),
                                    where + ": a plain user may not call a write tool");
                            CallOutcome o = ExampleConfigsTest.validateApprovePromote(g, op, approver, t.name(), args,
                                    "DESTRUCTIVE".equals(t.toolClass()));
                            assertTrue(o.allowed(), where + " -> " + o.decision());
                            assertFalse(o.result().isError(), where + " -> " + Json.write(o.result().body())
                                    + " (last request: " + m.last() + ")");
                        }
                        default -> throw new IllegalStateException(where + ": unexpected class " + t.toolClass());
                    }
                }
            }
            assertTrue(AuditChainVerifier.verify(dir.resolve("audit/aegis-" + a.engine() + ".jsonl")).ok(),
                    a.engine() + ": audit chain verifies");
        }
        assertEquals(EXPECTED_TOOLS, tools, "converted tool count changed: " + perEngine);
    }

    @Test
    void pathTypedAdaptersAcceptDepthAndRefuseTraversal() throws Exception {
        // HDFS addresses a resource by path in the URL itself. Depth has to work, climbing must not.
        for (String[] c : new String[][] {
                {"hadoop", "hadoop_list_status", "/user/hive/warehouse", "/webhdfs/v1/user/hive/warehouse?op=LISTSTATUS"}}) {
            Adapter a = adapters().stream().filter(x -> x.engine().equals(c[0])).findFirst().orElseThrow();
            try (RestRouteContractHarness m = contractHarness(a.engine()); Gateway g = open(a, m.url())) {
                CallerIdentity user = ExampleConfigsTest.who("ana", a.engine() + "-users");
                assertTrue(ExampleConfigsTest.call(g, user, c[1], Map.of("path", c[2])).allowed());
                assertTrue(m.last().contains(c[3]), m.last());
                // Nothing reaches the backend, and two independent layers refuse it. A literal ".." is
                // rejected while the resource identifier is extracted (step 2), so it never even becomes
                // a governed resource; the remaining shapes are rejected when the target URL is built
                // and checked (step 5). Either way the refusal happens before any request is sent.
                int before = m.requests.size();
                Map<String, String> expected = new LinkedHashMap<>();
                expected.put("/user/../../etc", DenyCodes.SCOPE_DENIED);
                expected.put("/a/./b", DenyCodes.EGRESS_DENIED);
                expected.put("/a//b", DenyCodes.EGRESS_DENIED);
                expected.put("/a/%2e%2e/b", DenyCodes.EGRESS_DENIED);
                for (Map.Entry<String, String> bad : expected.entrySet()) {
                    assertEquals(bad.getValue(),
                            ExampleConfigsTest.call(g, user, c[1], Map.of("path", bad.getKey())).decision().code(),
                            a.engine() + " " + bad.getKey());
                }
                assertEquals(before, m.requests.size(), "nothing was sent for any traversal attempt");
            }
        }
    }

    @Test
    void sqlToolsClassifiedReadCannotBeUsedToWrite() throws Exception {
        // These engines' HTTP APIs run whatever statement they are given, so a tool that forwards a
        // statement is only honestly READ because the sql-readonly hook is bound to it. Without the hook
        // a model could send DDL through a tool that needs no write group, no approval and no receipt.
        // Hive and Calcite are not here any more: neither has a REST SQL endpoint, so both are jdbc
        // sources now, and their read-only enforcement is tested against the real JDBC contract in
        // JdbcToolSourceTest.runQueryRefusesAnythingThatIsNotOneReadStatement, against Apache Derby.
        Map<String, List<String>> sqlTools = Map.of(
                "doris", List.of("doris_run_sql_readonly"),
                "druid", List.of("druid_run_sql_readonly"));
        for (Map.Entry<String, List<String>> e : new TreeMap<>(sqlTools).entrySet()) {
            Adapter a = adapters().stream().filter(x -> x.engine().equals(e.getKey())).findFirst().orElseThrow();
            try (RestRouteContractHarness m = contractHarness(a.engine()); Gateway g = open(a, m.url())) {
                CallerIdentity user = ExampleConfigsTest.who("ana", a.engine() + "-users");
                for (String tool : e.getValue()) {
                    // The read path still works, otherwise the guard would just be breakage.
                    assertTrue(ExampleConfigsTest.call(g, user, tool, Map.of("sql", "select 1")).allowed(),
                            a.engine() + " " + tool + ": a read must still be allowed");
                    int before = m.requests.size();
                    for (String write : new String[] {
                            "drop table orders",
                            "delete from orders",
                            "insert into orders values (1)",
                            "update orders set total = 0",
                            "select 1; drop table orders",
                            "grant select on orders to bob"}) {
                        CallOutcome o = ExampleConfigsTest.call(g, user, tool, Map.of("sql", write));
                        assertEquals(DenyCodes.INVALID_INPUT, o.decision().code(),
                                a.engine() + " " + tool + " <- " + write + " -> " + o.decision());
                    }
                    assertEquals(before, m.requests.size(),
                            a.engine() + " " + tool + ": no writing statement reached the backend");
                }
            }
        }
    }

    @Test
    void protectedResourcesAreDeniedEvenToAnOperatorWithApproval() throws Exception {
        // Each of these configs carries a DENY rule for the engine's own system objects, which no
        // amount of approval can override: policy is evaluated before approval is ever requested.
        for (String[] c : new String[][] {
                {"couchdb", "couchdb_delete_db", "db", "_users"},
                {"hbase", "hbase_delete_table", "table", "hbase:meta"},
                {"cassandra", "cassandra_drop_table", "keyspace", "system_schema"},
                // ZooKeeper's reserved subtree, reached through the converter source. Policy runs before
                // the source is ever asked to execute, so this is denied without a live ensemble, which
                // is the point: the guard does not depend on the backend being there.
                {"zookeeper", "zookeeper_delete_node", "path", "/zookeeper/quota"}}) {
            Adapter a = adapters().stream().filter(x -> x.engine().equals(c[0])).findFirst().orElseThrow();
            Tool tool = a.tools().stream().filter(t -> t.name().equals(c[1])).findFirst().orElseThrow();
            Map<String, Object> args = args(a, tool);
            args.put(c[2], c[3]); // point it at a system object this config protects
            if ("zookeeper".equals(c[0])) {
                args.put("version", 0L); // the converter requires the expected version on every write
            }
            try (RestRouteContractHarness m = contractHarness(a.engine()); Gateway g = open(a, m.url())) {
                CallerIdentity op = ExampleConfigsTest.who("oscar", a.engine() + "-operators");
                CallOutcome o = ExampleConfigsTest.call(g, op, c[1], args);
                assertEquals(DenyCodes.POLICY_DENIED, o.decision().code(), c[0] + " " + args + " -> " + o.decision());
                assertTrue(m.requests.isEmpty(), c[0] + ": denied by policy before any request was sent");
            }
        }
    }

    /**
     * A znode path that climbs is refused while the resource identifier is extracted, which happens
     * before the source is asked for anything. So a converter source gets the same traversal guard an
     * HTTP one does, without the guard having to be written twice: it is not the URL builder that
     * catches this, it is step 2.
     *
     * <p>Shapes ZooKeeper itself rejects, such as a relative path, {@code /a//b} or a trailing slash, are
     * refused by {@code PathUtils} inside the source; those are tested in {@code aegis-source-zookeeper}
     * against a real server, where "and nothing changed" can actually be asserted.
     */
    @Test
    void zooKeeperDataTreeRefusesTraversalBeforeReachingTheEnsemble() throws Exception {
        Adapter a = adapters().stream().filter(x -> x.engine().equals("zookeeper")).findFirst().orElseThrow();
        try (RestRouteContractHarness m = contractHarness(a.engine()); Gateway g = open(a, m.url())) {
            CallerIdentity op = ExampleConfigsTest.who("oscar", "zookeeper-operators");
            for (String tool : List.of("zookeeper_list_children", "zookeeper_stat_node", "zookeeper_get_node")) {
                assertEquals(DenyCodes.SCOPE_DENIED,
                        ExampleConfigsTest.call(g, op, tool, Map.of("path", "/brokers/../../etc")).decision().code(),
                        tool + ": a climbing path must not become a governed resource");
            }
            assertEquals(DenyCodes.SCOPE_DENIED, ExampleConfigsTest.call(g, op, "zookeeper_delete_node",
                            Map.of("path", "/brokers/../../etc", "version", 0L)).decision().code());
            assertTrue(m.requests.isEmpty(), "nothing was sent anywhere for a traversal attempt");
        }
    }
}
