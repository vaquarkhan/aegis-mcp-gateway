package io.github.vaquarkhan.aegis.adapter.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.vaquarkhan.aegis.core.auth.CallerIdentity;
import io.github.vaquarkhan.aegis.core.config.GatewayConfig;
import io.github.vaquarkhan.aegis.core.spi.CallContext;
import io.github.vaquarkhan.aegis.core.spi.ToolClass;
import io.github.vaquarkhan.aegis.core.spi.ToolDef;
import io.github.vaquarkhan.aegis.core.util.Inputs;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** @author Viquar Khan */
class SparkAdapterTest {

    @Test
    void taxonomyAndTools() {
        SparkAdapter adapter = new SparkAdapter();
        assertEquals("spark", adapter.engineId());
        assertEquals("batch", adapter.taxonomyClass());
        List<ToolDef> tools = adapter.tools(GatewayConfig.builder().defaults().build());
        Set<String> names = tools.stream().map(ToolDef::name).collect(Collectors.toSet());
        assertTrue(names.containsAll(Set.of(
                "list_applications", "get_application", "run_sql_readonly", "submit_batch", "kill_application")));
        assertEquals(ToolClass.DESTRUCTIVE,
                tools.stream().filter(t -> t.name().equals("submit_batch")).findFirst().orElseThrow().cls());
    }

    @Test
    void historyClientReadsEmbeddedFake() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/applications", exchange -> respond(exchange, 200, "[{\"id\":\"app-1\"}]"));
        server.start();
        try {
            int port = server.getAddress().getPort();
            SparkHttpClient client = new SparkHttpClient("http://127.0.0.1:" + port);
            String body = client.get("/api/v1/applications");
            assertTrue(body.contains("app-1"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void configPropertyOverridesHistoryUrl() {
        GatewayConfig cfg = GatewayConfig.builder().defaults()
                .adapterProperties(Map.of("spark.history.url", "http://127.0.0.1:9"))
                .build();
        SparkAdapter adapter = new SparkAdapter();
        assertTrue(adapter.tools(cfg).stream().anyMatch(t -> t.name().equals("list_applications")));
        assertEquals("http://127.0.0.1:9", SparkAdapter.historyUrl(cfg));
    }

    @Test
    void submitBatchPostsToLivy() throws Exception {
        List<String> methods = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        HttpServer livy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        livy.createContext("/batches", exchange -> {
            methods.add(exchange.getRequestMethod());
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 201, "{\"id\":14,\"state\":\"starting\"}");
        });
        livy.start();
        try {
            GatewayConfig cfg = livyConfig(livy.getAddress().getPort());
            String body = call(cfg, "submit_batch",
                    Map.of("file", "s3a://jobs/etl.jar", "className", "com.example.Etl"));
            assertTrue(body.contains("\"id\":14"));
            assertEquals(List.of("POST"), methods);
            assertEquals("{\"file\":\"s3a://jobs/etl.jar\",\"className\":\"com.example.Etl\"}", bodies.get(0));
        } finally {
            livy.stop(0);
        }
    }

    @Test
    void killApplicationDeletesTheLivyBatch() throws Exception {
        List<String> requests = new ArrayList<>();
        HttpServer livy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        livy.createContext("/batches/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            respond(exchange, 200, "{\"msg\":\"deleted\"}");
        });
        livy.start();
        try {
            GatewayConfig cfg = livyConfig(livy.getAddress().getPort());
            assertTrue(call(cfg, "kill_application", Map.of("appId", "14")).contains("deleted"));
            assertEquals(List.of("DELETE /batches/14"), requests);
        } finally {
            livy.stop(0);
        }
    }

    @Test
    void submitBatchRejectsAnUnusableFileReference() {
        GatewayConfig cfg = livyConfig(9);
        assertThrows(Inputs.InvalidInput.class, () -> call(cfg, "submit_batch", Map.of("file", "  ")));
        assertThrows(Inputs.InvalidInput.class,
                () -> call(cfg, "submit_batch", Map.of("file", "s3a://jobs/etl.jar\nrm -rf /")));
    }

    @Test
    void livyFailuresSurfaceSoTheBreakerCanTrip() {
        GatewayConfig cfg = livyConfig(1);
        assertThrows(IllegalStateException.class,
                () -> call(cfg, "kill_application", Map.of("appId", "14")),
                "a dead Livy must trip the breaker, not look like a successful kill");
    }

    @Test
    void readOnlySqlNeedsAConfiguredEndpoint() {
        GatewayConfig cfg = GatewayConfig.builder().defaults().build();
        Inputs.InvalidInput failure = assertThrows(Inputs.InvalidInput.class,
                () -> call(cfg, "run_sql_readonly", Map.of("sql", "select 1")));
        assertTrue(failure.getMessage().contains("SQL_BACKEND_NOT_CONFIGURED"));
    }

    @Test
    void readOnlySqlPostsToTheConfiguredEndpointAndRejectsWrites() throws Exception {
        List<String> bodies = new ArrayList<>();
        HttpServer sql = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        sql.createContext("/sql", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"rows\":[[1]]}");
        });
        sql.start();
        try {
            GatewayConfig cfg = GatewayConfig.builder().defaults()
                    .adapterProperties(Map.of(
                            "spark.sql.http.url", "http://127.0.0.1:" + sql.getAddress().getPort() + "/sql"))
                    .build();
            assertTrue(call(cfg, "run_sql_readonly", Map.of("sql", "select 1")).contains("rows"));
            assertEquals("{\"sql\":\"select 1\"}", bodies.get(0));
            assertThrows(Inputs.InvalidInput.class,
                    () -> call(cfg, "run_sql_readonly", Map.of("sql", "drop table events")),
                    "the read-only guard must run before any endpoint is contacted");
        } finally {
            sql.stop(0);
        }
    }

    @Test
    void egressAllowsHistoryLivyAndSqlHosts() {
        GatewayConfig cfg = GatewayConfig.builder().defaults()
                .adapterProperties(Map.of(
                        "spark.history.url", "http://history.internal:18080",
                        "spark.livy.url", "http://livy.internal:8998",
                        "spark.sql.http.url", "http://sql.internal:10000/sql"))
                .build();
        assertEquals(Set.of("history.internal", "livy.internal", "sql.internal"),
                new SparkAdapter().egressAllowHosts(cfg));
    }

    @Test
    void historyResourceReadsLiveApplications() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/applications", exchange -> respond(exchange, 200, "[{\"id\":\"app-9\"}]"));
        server.start();
        try {
            GatewayConfig cfg = GatewayConfig.builder().defaults()
                    .adapterProperties(Map.of(
                            "spark.history.url", "http://127.0.0.1:" + server.getAddress().getPort()))
                    .build();
            CallerIdentity caller = new CallerIdentity("t", Set.of("*"), false);
            CallContext ctx = new CallContext(
                    "spark-history", ToolClass.READ, Map.of(), caller, "tr", Optional.empty());
            assertTrue(new SparkAdapter().resources(cfg).get(0).read().apply(ctx).contains("app-9"));
        } finally {
            server.stop(0);
        }
    }

    private static final McpJsonMapper JSON = new JacksonMcpJsonMapperSupplier().get();

    @Test
    void emptySchemaSerializesCorrectly() throws Exception {
        JsonNode node = parse(new JsonSchema("object", Map.of(), null, null, null, null));

        assertEquals("object", node.get("type").asText());
        assertTrue(node.get("properties").isEmpty());
        assertFalse(node.has("required"));
    }

    @Test
    void getApplicationSchemaSerializesCorrectly() throws Exception {
        JsonNode node = parse(new JsonSchema("object", Map.of(
                "appId", Map.of("type", "string")),
                List.of("appId"), null, null, null));

        assertEquals("object", node.get("type").asText());
        assertEquals("string", node.get("properties").get("appId").get("type").asText());
        assertEquals(1, node.get("properties").size());
        assertEquals("appId", node.get("required").get(0).asText());
    }

    @Test
    void runSqlReadonlySchemaSerializesCorrectly() throws Exception {
        JsonNode node = parse(new JsonSchema("object", Map.of(
                "sql", Map.of("type", "string")),
                List.of("sql"), null, null, null));

        assertEquals("object", node.get("type").asText());
        assertEquals("string", node.get("properties").get("sql").get("type").asText());
        assertEquals(1, node.get("required").size());
        assertEquals("sql", node.get("required").get(0).asText());
    }

    @Test
    void submitBatchSchemaSerializesCorrectly() throws Exception {
        JsonNode node = parse(new JsonSchema("object", Map.of(
                "file", Map.of("type", "string"),
                "className", Map.of("type", "string"),
                "approvalToken", Map.of("type", "string")),
                List.of("file", "approvalToken"), null, null, null));

        assertEquals("object", node.get("type").asText());
        assertEquals("string", node.get("properties").get("file").get("type").asText());
        assertEquals("string", node.get("properties").get("className").get("type").asText());
        assertEquals("string", node.get("properties").get("approvalToken").get("type").asText());
        assertEquals(3, node.get("properties").size());
        assertEquals(2, node.get("required").size());
        assertEquals("file", node.get("required").get(0).asText());
        assertEquals("approvalToken", node.get("required").get(1).asText());
    }

    @Test
    void killApplicationSchemaSerializesCorrectly() throws Exception {
        JsonNode node = parse(new JsonSchema("object", Map.of(
                "appId", Map.of("type", "string"),
                "approvalToken", Map.of("type", "string")),
                List.of("appId", "approvalToken"), null, null, null));

        assertEquals("object", node.get("type").asText());
        assertEquals("string", node.get("properties").get("appId").get("type").asText());
        assertEquals("string", node.get("properties").get("approvalToken").get("type").asText());
        assertEquals(2, node.get("properties").size());
        assertEquals("appId", node.get("required").get(0).asText());
        assertEquals("approvalToken", node.get("required").get(1).asText());
    }

    private JsonNode parse(JsonSchema schema) throws Exception {
        return JSON.readValue(JSON.writeValueAsString(schema), JsonNode.class);
    }

    private static GatewayConfig livyConfig(int port) {
        return GatewayConfig.builder().defaults()
                .adapterProperties(Map.of("spark.livy.url", "http://127.0.0.1:" + port))
                .build();
    }

    private static String call(GatewayConfig cfg, String tool, Map<String, Object> args) {
        ToolDef def = new SparkAdapter().tools(cfg).stream()
                .filter(t -> t.name().equals(tool)).findFirst().orElseThrow();
        CallerIdentity caller = new CallerIdentity("test", Set.of("*"), false);
        CallContext ctx = new CallContext(tool, def.cls(), args, caller, "trace-1", Optional.empty());
        return def.backend().apply(ctx);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
