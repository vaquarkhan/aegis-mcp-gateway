package io.github.vaquarkhan.aegis.adapter.iceberg;

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
class IcebergAdapterTest {

    private static final McpJsonMapper JSON = new JacksonMcpJsonMapperSupplier().get();

    private JsonNode parse(JsonSchema schema) throws Exception {
        return JSON.readValue(JSON.writeValueAsString(schema), JsonNode.class);
    }

    @Test
    void emptySchemaHasNoRequired() throws Exception {
        JsonSchema schema = new JsonSchema("object", Map.of(), null, null, null, null);
        JsonNode node = parse(schema);
        assertEquals("object", node.get("type").asText());
        assertFalse(node.has("required"));
    }

    @Test
    void taxonomyAndDestructiveTools() {
        IcebergAdapter adapter = new IcebergAdapter();
        assertEquals("iceberg", adapter.engineId());
        assertEquals("lakehouse", adapter.taxonomyClass());
        Set<String> names = adapter.tools(GatewayConfig.builder().defaults().build()).stream()
                .map(ToolDef::name).collect(Collectors.toSet());
        assertTrue(names.containsAll(Set.of(
                "list_namespaces", "get_table", "get_table_metadata", "create_namespace", "alter_table",
                "drop_table", "expire_snapshots", "remove_orphan_files", "rewrite_data_files",
                "commit_transaction", "dry_run_maintenance")));
        assertEquals(ToolClass.DESTRUCTIVE, byName(adapter, "commit_transaction").cls());
        assertEquals(ToolClass.READ, byName(adapter, "get_table_metadata").cls());
        assertEquals(ToolClass.MUTATE, byName(adapter, "create_namespace").cls());
    }

    @Test
    void commitTransactionHasADryRunCompanion() {
        IcebergAdapter adapter = new IcebergAdapter();
        CallerIdentity caller = new CallerIdentity("t", Set.of("*"), false);
        CallContext ctx = new CallContext(
                "commit_transaction",
                ToolClass.DESTRUCTIVE,
                Map.of("namespace", "db", "table", "events", "dryRun", true),
                caller,
                "tr",
                Optional.empty());
        String body = byName(adapter, "commit_transaction").backend().apply(ctx);
        assertTrue(body.contains("dry_run"));
        assertTrue(body.contains("db.events"));
    }

    @Test
    void catalogReadsSurfaceBackendFailures() {
        GatewayConfig cfg = GatewayConfig.builder().defaults()
                .adapterProperties(Map.of("iceberg.rest.catalog.url", "http://127.0.0.1:1"))
                .build();
        ToolDef tool = new IcebergAdapter().tools(cfg).stream()
                .filter(t -> t.name().equals("list_namespaces")).findFirst().orElseThrow();
        CallerIdentity caller = new CallerIdentity("t", Set.of("*"), true);
        CallContext ctx = new CallContext(
                "list_namespaces", ToolClass.READ, Map.of(), caller, "tr", Optional.empty());
        assertThrows(IllegalStateException.class, () -> tool.backend().apply(ctx),
                "a dead catalog must trip the breaker, not look like an empty result");
    }

    @Test
    void redactsVendedCredentials() {
        String raw = "{\"credential\":\"AKIASECRET\",\"token\":\"abc\"}";
        String safe = IcebergRestClient.redactCredentials(raw);
        assertFalse(safe.contains("AKIASECRET"));
        assertTrue(safe.contains("redacted"));
    }

    @Test
    void dryRunCompanionDoesNotMutate() {
        IcebergAdapter adapter = new IcebergAdapter();
        ToolDef tool = byName(adapter, "dry_run_maintenance");
        CallerIdentity caller = new CallerIdentity("t", Set.of("*"), false);
        CallContext ctx = new CallContext(
                "dry_run_maintenance",
                ToolClass.READ,
                Map.of("operation", "expire_snapshots", "namespace", "db", "table", "events"),
                caller,
                "tr",
                Optional.empty());
        String body = tool.backend().apply(ctx);
        assertTrue(body.contains("dry_run"));
        assertTrue(body.contains("db.events"));
    }

    @Test
    void restClientUsesEmbeddedFake() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/namespaces", exchange -> respond(exchange, 200, "{\"namespaces\":[[\"db\"]]}"));
        server.start();
        try {
            IcebergRestClient client = new IcebergRestClient("http://127.0.0.1:" + server.getAddress().getPort());
            assertTrue(client.get("/v1/namespaces").contains("db"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void createNamespacePostsEveryNamespaceLevel() throws Exception {
        List<String> requests = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        HttpServer catalog = catalog(requests, bodies, 200, "{\"namespace\":[\"bronze\",\"sales\"]}");
        try {
            String body = call(catalog, "create_namespace", Map.of("namespace", "bronze.sales"));
            assertTrue(body.contains("bronze"));
            assertEquals(List.of("POST /v1/namespaces"), requests);
            assertEquals("{\"namespace\":[\"bronze\",\"sales\"],\"properties\":{}}", bodies.get(0));
        } finally {
            catalog.stop(0);
        }
    }

    @Test
    void alterTablePostsASetPropertiesUpdate() throws Exception {
        List<String> requests = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        HttpServer catalog = catalog(requests, bodies, 200, "{\"metadata\":{\"table-uuid\":\"u\"}}");
        try {
            String body = call(catalog, "alter_table", Map.of(
                    "namespace", "db",
                    "table", "events",
                    "properties", Map.of("write.format.default", "parquet")));
            assertTrue(body.contains("table-uuid"));
            assertEquals(List.of("POST /v1/namespaces/db/tables/events"), requests);
            assertEquals("{\"requirements\":[],\"updates\":[{\"action\":\"set-properties\",\"updates\":"
                    + "{\"write.format.default\":\"parquet\"}}]}", bodies.get(0));
        } finally {
            catalog.stop(0);
        }
    }

    @Test
    void alterTableRejectsPropertiesThatAreNotAnObject() throws Exception {
        List<String> requests = new ArrayList<>();
        HttpServer catalog = catalog(requests, new ArrayList<>(), 200, "{}");
        try {
            assertThrows(Inputs.InvalidInput.class, () -> call(catalog, "alter_table", Map.of(
                    "namespace", "db", "table", "events", "properties", "write.format.default=parquet")));
            assertTrue(requests.isEmpty(), "an invalid argument must never reach the catalog");
        } finally {
            catalog.stop(0);
        }
    }

    @Test
    void dropTableDeletesThroughTheCatalog() throws Exception {
        List<String> requests = new ArrayList<>();
        HttpServer catalog = catalog(requests, new ArrayList<>(), 200, "{\"dropped\":true}");
        try {
            assertTrue(call(catalog, "drop_table", Map.of("namespace", "db", "table", "events"))
                    .contains("dropped"));
            assertEquals(List.of("DELETE /v1/namespaces/db/tables/events"), requests);
        } finally {
            catalog.stop(0);
        }
    }

    @Test
    void dropTableDryRunTouchesNothing() throws Exception {
        List<String> requests = new ArrayList<>();
        HttpServer catalog = catalog(requests, new ArrayList<>(), 200, "{}");
        try {
            assertTrue(call(catalog, "drop_table", Map.of("namespace", "db", "table", "events", "dryRun", true))
                    .contains("dry_run"));
            assertTrue(requests.isEmpty());
        } finally {
            catalog.stop(0);
        }
    }

    @Test
    void commitTransactionPostsTheSuppliedCommitBody() throws Exception {
        List<String> requests = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        HttpServer catalog = catalog(requests, bodies, 200, "{\"metadata-location\":\"s3://b/m.json\"}");
        try {
            String commit = "{\"requirements\":[{\"type\":\"assert-table-uuid\",\"uuid\":\"u\"}],\"updates\":[]}";
            assertTrue(call(catalog, "commit_transaction",
                    Map.of("namespace", "db", "table", "events", "commitJson", commit))
                    .contains("metadata-location"));
            assertEquals(List.of("POST /v1/namespaces/db/tables/events"), requests);
            assertEquals(commit, bodies.get(0));
        } finally {
            catalog.stop(0);
        }
    }

    @Test
    void commitTransactionWithoutABodyIsACallerError() throws Exception {
        List<String> requests = new ArrayList<>();
        HttpServer catalog = catalog(requests, new ArrayList<>(), 200, "{}");
        try {
            assertThrows(Inputs.InvalidInput.class,
                    () -> call(catalog, "commit_transaction", Map.of("namespace", "db", "table", "events")));
            assertTrue(requests.isEmpty());
        } finally {
            catalog.stop(0);
        }
    }

    @Test
    void maintenanceExecutionPointsAtTheEngineProcedure() throws Exception {
        List<String> requests = new ArrayList<>();
        HttpServer catalog = catalog(requests, new ArrayList<>(), 200, "{}");
        try {
            for (String op : List.of("expire_snapshots", "remove_orphan_files", "rewrite_data_files")) {
                IllegalStateException failure = assertThrows(IllegalStateException.class,
                        () -> call(catalog, op, Map.of("namespace", "db", "table", "events")));
                assertTrue(failure.getMessage().contains("dry_run_maintenance"));
                assertTrue(call(catalog, op, Map.of("namespace", "db", "table", "events", "dryRun", true))
                        .contains("dry_run"));
            }
            assertTrue(requests.isEmpty(), "REST catalogs do not expose snapshot maintenance");
        } finally {
            catalog.stop(0);
        }
    }

    @Test
    void catalogHostIsAllowedFromEitherConfigKey() {
        GatewayConfig yaml = GatewayConfig.builder().defaults()
                .adapterProperties(Map.of("iceberg.rest.catalog.url", "http://catalog.internal:8181"))
                .build();
        assertEquals(Set.of("catalog.internal"), new IcebergAdapter().egressAllowHosts(yaml));
        GatewayConfig env = GatewayConfig.builder().defaults()
                .adapterProperties(Map.of("ICEBERG_REST_CATALOG_URL", "http://catalog.env:8181"))
                .build();
        assertEquals(Set.of("catalog.env"), new IcebergAdapter().egressAllowHosts(env));
    }

    private static HttpServer catalog(List<String> requests, List<String> bodies, int status, String response)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, status, response);
        });
        server.start();
        return server;
    }

    private static String call(HttpServer catalog, String tool, Map<String, Object> args) {
        GatewayConfig cfg = GatewayConfig.builder().defaults()
                .adapterProperties(Map.of(
                        "iceberg.rest.catalog.url", "http://127.0.0.1:" + catalog.getAddress().getPort()))
                .build();
        ToolDef def = new IcebergAdapter().tools(cfg).stream()
                .filter(t -> t.name().equals(tool)).findFirst().orElseThrow();
        CallerIdentity caller = new CallerIdentity("test", Set.of("*"), false);
        CallContext ctx = new CallContext(tool, def.cls(), args, caller, "trace-1", Optional.empty());
        return def.backend().apply(ctx);
    }

    private static ToolDef byName(IcebergAdapter adapter, String name) {
        return adapter.tools(GatewayConfig.builder().defaults().build()).stream()
                .filter(t -> t.name().equals(name)).findFirst().orElseThrow();
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
