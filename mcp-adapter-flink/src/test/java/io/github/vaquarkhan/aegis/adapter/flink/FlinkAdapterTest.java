package io.github.vaquarkhan.aegis.adapter.flink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.vaquarkhan.aegis.adapter.flink.client.SqlReadonlyGuard;
import io.github.vaquarkhan.aegis.core.config.GatewayConfig;
import io.github.vaquarkhan.aegis.core.spi.CallContext;
import io.github.vaquarkhan.aegis.core.spi.PromptDef;
import io.github.vaquarkhan.aegis.core.spi.ResourceDef;
import io.github.vaquarkhan.aegis.core.spi.ToolClass;
import io.github.vaquarkhan.aegis.core.spi.ToolDef;
import io.github.vaquarkhan.aegis.core.util.Inputs;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Flink adapter contract, catalog and guardrail tests. Fully offline: the Flink REST API is
 * faked with an embedded HTTP server.
 *
 * @author Viquar Khan
 */
class FlinkAdapterTest {

    private static final Set<String> READ_TOOLS = Set.of(
            "list_jobs", "get_job", "get_job_status", "get_job_exceptions", "get_job_metrics",
            "list_checkpoints", "list_jars", "run_sql_readonly", "get_cluster_info",
            "list_taskmanagers", "get_job_config", "get_flink_config");
    private static final Set<String> MUTATE_TOOLS = Set.of("trigger_savepoint", "rescale_job", "upload_jar");
    private static final Set<String> DESTRUCTIVE_TOOLS =
            Set.of("run_jar", "stop_job", "cancel_job", "run_sql_ddl_dml");

    private static HttpServer server;
    private static String baseUrl;

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/overview", ex -> respond(ex, "{\"slots-total\":4}"));
        server.createContext("/jobs/overview", ex -> respond(ex, "{\"jobs\":[{\"jid\":\"job-1\"}]}"));
        server.createContext("/jobs/job-1/status", ex -> respond(ex, "{\"status\":\"RUNNING\"}"));
        server.createContext("/taskmanagers", ex -> respond(ex, "{\"taskmanagers\":[]}"));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange ex, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static GatewayConfig config() {
        return GatewayConfig.builder().defaults()
                .adapterProperties(Map.of(FlinkConfigKeys.REST_URL, baseUrl))
                .build();
    }

    private static CallContext ctx(String tool, ToolClass cls, Map<String, Object> args) {
        return new CallContext(tool, cls, args, null, "test-trace", Optional.empty());
    }

    private static ToolDef tool(List<ToolDef> tools, String name) {
        return tools.stream()
                .filter(t -> t.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("tool not found: " + name));
    }

    @Test
    void engineIdentityIsStreamingFlink() {
        FlinkAdapter adapter = new FlinkAdapter();
        assertEquals("flink", adapter.engineId());
        assertEquals("streaming", adapter.taxonomyClass());
    }

    @Test
    void catalogExposesEveryDeclaredTool() {
        List<ToolDef> tools = new FlinkAdapter().tools(config());
        Set<String> names = tools.stream().map(ToolDef::name).collect(Collectors.toSet());
        assertTrue(names.containsAll(READ_TOOLS), "missing read tools");
        assertTrue(names.containsAll(MUTATE_TOOLS), "missing mutate tools");
        assertTrue(names.containsAll(DESTRUCTIVE_TOOLS), "missing destructive tools");
        assertEquals(READ_TOOLS.size() + MUTATE_TOOLS.size() + DESTRUCTIVE_TOOLS.size(), names.size());
    }

    @Test
    void toolClassesMatchTheGovernanceTaxonomy() {
        List<ToolDef> tools = new FlinkAdapter().tools(config());
        for (ToolDef def : tools) {
            ToolClass expected;
            if (READ_TOOLS.contains(def.name())) {
                expected = ToolClass.READ;
            } else if (MUTATE_TOOLS.contains(def.name())) {
                expected = ToolClass.MUTATE;
            } else {
                expected = ToolClass.DESTRUCTIVE;
            }
            assertEquals(expected, def.cls(), def.name());
        }
    }

    @Test
    void everyManifestEntryHasABackend() {
        List<FlinkToolFactory.ToolSpec> manifest = FlinkToolFactory.loadManifest();
        Set<String> built = new FlinkAdapter().tools(config()).stream()
                .map(ToolDef::name)
                .collect(Collectors.toSet());
        for (FlinkToolFactory.ToolSpec spec : manifest) {
            assertTrue(built.contains(spec.name()), "manifest tool without backend: " + spec.name());
            assertTrue(spec.inputSchema().contains("\"type\":\"object\""), "bad schema for " + spec.name());
        }
    }

    @Test
    void writeToolsRequireAnApprovalToken() {
        List<ToolDef> tools = new FlinkAdapter().tools(config());
        for (ToolDef def : tools) {
            if (def.cls() == ToolClass.READ) {
                continue;
            }
            assertTrue(def.inputSchemaJson().contains("approvalToken"), "no approvalToken in " + def.name());
        }
    }

    @Test
    void readToolReachesTheFakeRestApi() {
        List<ToolDef> tools = new FlinkAdapter().tools(config());
        String jobs = tool(tools, "list_jobs").backend().apply(ctx("list_jobs", ToolClass.READ, Map.of()));
        assertTrue(jobs.contains("job-1"));
        String status = tool(tools, "get_job_status").backend()
                .apply(ctx("get_job_status", ToolClass.READ, Map.of("jobId", "job-1")));
        assertTrue(status.contains("RUNNING"));
    }

    @Test
    void jobIdIsValidatedBeforeAnyBackendCall() {
        List<ToolDef> tools = new FlinkAdapter().tools(config());
        ToolDef getJob = tool(tools, "get_job");
        assertThrows(Inputs.InvalidInput.class,
                () -> getJob.backend().apply(ctx("get_job", ToolClass.READ, Map.of("jobId", "../../etc/passwd"))));
        assertThrows(Inputs.InvalidInput.class,
                () -> getJob.backend().apply(ctx("get_job", ToolClass.READ, Map.of())));
    }

    @Test
    void uploadJarIsRejectedWhenNoDirectoryIsAllowListed() {
        ToolDef upload = tool(new FlinkAdapter().tools(config()), "upload_jar");
        assertThrows(Inputs.InvalidInput.class,
                () -> upload.backend().apply(ctx("upload_jar", ToolClass.MUTATE, Map.of("path", "/tmp/any.jar"))));
    }

    @Test
    void readOnlySqlToolRejectsMutatingStatements() {
        ToolDef sql = tool(new FlinkAdapter().tools(config()), "run_sql_readonly");
        assertThrows(Inputs.InvalidInput.class,
                () -> sql.backend().apply(ctx("run_sql_readonly", ToolClass.READ,
                        Map.of("sql", "INSERT INTO t VALUES (1)"))));
    }

    @Test
    void sqlGuardAllowsReadsAndRejectsMutations() {
        SqlReadonlyGuard guard = new SqlReadonlyGuard();
        assertTrue(guard.isReadOnly("SELECT 1"));
        assertTrue(guard.isReadOnly("show tables"));
        assertTrue(guard.isReadOnly("DESCRIBE t"));
        assertTrue(guard.isReadOnly("WITH x AS (SELECT 1) SELECT * FROM x"));
        assertFalse(guard.isReadOnly("WITH t AS (DELETE FROM x RETURNING *) SELECT * FROM t"));
        assertFalse(guard.isReadOnly("INSERT INTO t VALUES (1)"));
        assertFalse(guard.isReadOnly("CREATE TABLE t (id INT)"));
        assertFalse(guard.isReadOnly("DROP TABLE t"));
        assertFalse(guard.isReadOnly("SELECT 1; SELECT 2"));
        assertFalse(guard.isReadOnly("SELECT 1 -- ; DROP TABLE t\n; DELETE FROM t"));
        assertFalse(guard.isReadOnly(null));
    }

    @Test
    void adapterPublishesItsSqlGuardToTheCore() {
        assertTrue(new FlinkAdapter().readOnlyGuard().isPresent());
        assertTrue(new FlinkAdapter().readOnlyGuard().orElseThrow().isReadOnly("SELECT 1"));
    }

    @Test
    void resourcesCoverOverviewJobsAndHealth() {
        List<ResourceDef> resources = new FlinkAdapter().resources(config());
        Set<String> uris = resources.stream().map(ResourceDef::uri).collect(Collectors.toSet());
        assertEquals(Set.of("flink://cluster/overview", "flink://jobs", "flink://health"), uris);
        ResourceDef overview = resources.stream()
                .filter(r -> r.uri().equals("flink://cluster/overview"))
                .findFirst()
                .orElseThrow();
        assertTrue(overview.read().apply(ctx("flink://cluster/overview", ToolClass.READ, Map.of()))
                .contains("slots-total"));
    }

    @Test
    void healthResourceReportsBackendReachability() {
        GatewayConfig cfg = GatewayConfig.builder().defaults()
                .adapterProperties(Map.of(
                        FlinkConfigKeys.REST_URL, baseUrl,
                        FlinkConfigKeys.GATEWAY_URL, "http://127.0.0.1:1"))
                .build();
        ResourceDef health = new FlinkAdapter().resources(cfg).stream()
                .filter(r -> r.uri().equals("flink://health"))
                .findFirst()
                .orElseThrow();
        String body = health.read().apply(ctx("flink://health", ToolClass.READ, Map.of()));
        assertTrue(body.contains("\"flink_rest\":true"));
        assertTrue(body.contains("\"sql_gateway\":false"));
    }

    @Test
    void egressHostsComeFromTheConfiguredEndpoints() {
        GatewayConfig cfg = GatewayConfig.builder().defaults()
                .adapterProperties(Map.of(
                        FlinkConfigKeys.REST_URL, "http://jobmanager.internal:8081",
                        FlinkConfigKeys.GATEWAY_URL, "http://sql-gateway.internal:8083"))
                .build();
        assertEquals(Set.of("jobmanager.internal", "sql-gateway.internal"),
                new FlinkAdapter().egressAllowHosts(cfg));
    }

    @Test
    void configKeysFallBackToSecureLocalDefaults() {
        GatewayConfig cfg = GatewayConfig.builder().defaults().build();
        assertEquals(FlinkConfigKeys.DEFAULT_REST_URL, FlinkConfigKeys.restUrl(cfg));
        assertEquals(FlinkConfigKeys.DEFAULT_GATEWAY_URL, FlinkConfigKeys.gatewayUrl(cfg));
        assertTrue(FlinkConfigKeys.jarUploadAllowDirs(cfg).isEmpty());
    }

    @Test
    void dottedYamlKeysOverrideEnvironmentStyleKeys() {
        GatewayConfig cfg = GatewayConfig.builder().defaults()
                .adapterProperties(Map.of(
                        FlinkConfigKeys.REST_URL, "http://from-env:8081",
                        FlinkConfigKeys.REST_URL_YAML, "http://from-yaml:8081",
                        FlinkConfigKeys.JAR_UPLOAD_ALLOW_DIRS_YAML, "/opt/jars, /srv/jars"))
                .build();
        assertEquals("http://from-yaml:8081", FlinkConfigKeys.restUrl(cfg));
        assertEquals(Set.of("/opt/jars", "/srv/jars"), FlinkConfigKeys.jarUploadAllowDirs(cfg));
    }

    @Test
    void prompts_returnsNonEmptyValidPromptList() {
        FlinkEngineAdapter adapter = new FlinkEngineAdapter();
        List<PromptDef> prompts = adapter.prompts();

        assertNotNull(prompts, "Prompts list should not be null");
        assertFalse(prompts.isEmpty(), "Flink adapter should provide at least one prompt");

        PromptDef prompt = prompts.get(0);
        assertEquals("flink-job-diagnostics", prompt.getId());
        assertNotNull(prompt.getDescription());
        assertFalse(prompt.getTemplate().isBlank(), "Prompt template must not be empty or blank");
    }
}