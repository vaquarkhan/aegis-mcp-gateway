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
package io.github.vaquarkhan.aegis.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.config.BuildContext;
import io.github.vaquarkhan.aegis.config.ConfigLoader;
import io.github.vaquarkhan.aegis.config.Gateway;
import io.github.vaquarkhan.aegis.config.GatewayFactory;
import io.github.vaquarkhan.aegis.config.openapi.OpenApiDraft;
import io.github.vaquarkhan.aegis.engine.CallOutcome;
import io.github.vaquarkhan.aegis.engine.catalog.ToolCatalog;
import io.github.vaquarkhan.aegis.engine.control.SchemaValidator;
import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.json.JsonValues;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.model.DenyCodes;
import io.github.vaquarkhan.aegis.engine.model.ToolCall;
import io.github.vaquarkhan.aegis.engine.model.ToolClass;
import io.github.vaquarkhan.aegis.validation.frontdoor.JavaServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code --openapi-draft} on the published Apache Iceberg 1.10.1 REST catalog OpenAPI document
 * (copied byte for byte from tag apache-iceberg-1.10.1, file open-api/rest-catalog-open-api.yaml),
 * then a reviewer's edits, then calls through the gateway to the official Iceberg 1.10.1
 * {@code RESTCatalogServer}. Shows that the draft loads, that unreviewed tools stay DESTRUCTIVE and
 * refused, and that a reviewed tool reaches the real catalog with the document's schema enforced.
 */
class OpenApiDraftIcebergTest {

    private static JavaServer catalog;
    private static int port;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    Path dir;

    @BeforeAll
    static void startCatalog() throws Exception {
        Path work = Path.of("target", "iceberg-openapi-draft").toAbsolutePath();
        Path warehouse = Files.createDirectories(work.resolve("warehouse"));
        port = JavaServer.freePort();
        Map<String, String> env = Map.of(
                "CATALOG_REST_PORT", Integer.toString(port),
                "CATALOG_WAREHOUSE", warehouse.toString().replace('\\', '/'),
                "CATALOG_IO__IMPL", "org.apache.iceberg.inmemory.InMemoryFileIO",
                "CATALOG_CATALOG__IMPL", "org.apache.iceberg.jdbc.JdbcCatalog",
                "CATALOG_URI", "jdbc:sqlite:file:" + work.resolve("catalog-" + System.nanoTime() + ".db").toString().replace('\\', '/'),
                "CATALOG_JDBC_USER", "aegis",
                "CATALOG_JDBC_PASSWORD", "aegis");
        catalog = new JavaServer("aegis.iceberg.serverClasspathFile", List.of("-Xmx1G"),
                "org.apache.iceberg.rest.RESTCatalogServer", List.of(), env, work.resolve("iceberg-rest.log"));
        catalog.awaitReady(() -> send("GET", "/v1/config", null) == 200, Duration.ofSeconds(120), "Iceberg REST catalog");
        assertEquals(200, send("POST", "/v1/namespaces", "{\"namespace\":[\"sales\"],\"properties\":{}}"));
    }

    @AfterAll
    static void stopCatalog() throws Exception {
        if (catalog != null) {
            catalog.close();
        }
    }

    @Test
    void draftFromThePublishedDocumentGovernsTheRealCatalog() throws Exception {
        Path spec = Path.of("src", "test", "resources", "openapi", "iceberg-1.10.1-rest-catalog-open-api.yaml");
        OpenApiDraft.Draft draft = OpenApiDraft.generate(OpenApiDraft.read(spec),
                new OpenApiDraft.Options("iceberg", "http://127.0.0.1:" + port));
        assertTrue(draft.tools() >= 25, "one tool per JSON operation, got " + draft.tools());
        Files.writeString(dir.resolve("draft.yaml"), draft.yaml());

        // 1. The draft as generated loads, and every tool is DESTRUCTIVE.
        try (Gateway g = GatewayFactory.build(dir.resolve("draft.yaml"), BuildContext.defaults(dir))) {
            List<ToolCatalog.Entry> entries = g.engine().catalog().entries();
            assertEquals(draft.tools(), entries.size());
            for (ToolCatalog.Entry e : entries) {
                assertEquals(ToolClass.DESTRUCTIVE, e.tool().toolClass(), e.tool().exposedName());
            }
        }

        // 2. A reviewer's edits: this catalog has no prefix (GET /v1/config returns none), so drop the
        //    {prefix} segment; classify two reads; grant analysts READ only.
        Map<String, Object> cfg = new LinkedHashMap<>(ConfigLoader.parseYaml(draft.yaml(), "draft"));
        Map<String, Object> source = JsonValues.asObject(JsonValues.asList(cfg.get("sources")).get(0));
        for (Object t : JsonValues.asList(source.get("tools"))) {
            Map<String, Object> tool = JsonValues.asObject(t);
            Map<String, Object> req = JsonValues.asObject(tool.get("request"));
            req.put("path", String.valueOf(req.get("path")).replace("/v1/{prefix}", "/v1"));
            Map<String, Object> schema = JsonValues.asObject(tool.get("inputSchema"));
            JsonValues.asObject(schema.get("properties")).remove("prefix");
            List<Object> declared = JsonValues.asList(schema.get("required"));
            if (declared != null) {
                List<Object> required = new ArrayList<>(declared);
                required.remove("prefix");
                schema.put("required", required);
            }
            if (Set.of("iceberg_list_namespaces", "iceberg_load_namespace_metadata").contains(tool.get("name"))) {
                tool.put("class", "READ");
            }
        }
        cfg.put("policy", Map.of("rules", List.of(Map.of("id", "analysts-read", "groups", List.of("analysts"),
                "classes", List.of("READ")))));
        cfg.put("egress", Map.of("allowCidrs", List.of("127.0.0.0/8")));

        CallerIdentity analyst = CallerIdentity.of("ana", Set.of("analysts"), "idp", "jwt");
        try (Gateway g = GatewayFactory.build(cfg, BuildContext.defaults(dir))) {
            CallOutcome listed = g.engine().call(analyst, new ToolCall("1", "iceberg_list_namespaces", Map.of(), Map.of(), ""));
            assertTrue(listed.allowed(), listed.decision().reason());
            String text = Json.write(listed.toMcpResult());
            assertTrue(text.contains("sales"), "the real catalog's namespace comes back: " + text);

            CallOutcome meta = g.engine().call(analyst, new ToolCall("2", "iceberg_load_namespace_metadata",
                    Map.of("namespace", "sales"), Map.of(), ""));
            assertTrue(meta.allowed(), meta.decision().reason());

            // A tool nobody classified is still DESTRUCTIVE and refused to a reader.
            CallOutcome drop = g.engine().call(analyst, new ToolCall("3", "iceberg_drop_namespace",
                    Map.of("namespace", "sales"), Map.of(), ""));
            assertFalse(drop.allowed());
            assertEquals(DenyCodes.READONLY_CALLER, drop.decision().code(), drop.decision().reason());
            assertEquals(200, send("GET", "/v1/namespaces/sales", null), "namespace sales still exists");

            // The document's own schema is enforced: CreateNamespaceRequest requires "namespace".
            Map<String, Object> create = g.engine().catalog().lookup("iceberg_create_namespace").orElseThrow()
                    .tool().descriptor().inputSchema();
            assertNull(SchemaValidator.validate(create, Json.parse("{\"body\":{\"namespace\":[\"ops\"]}}")));
            assertNotNull(SchemaValidator.validate(create, Json.parse("{\"body\":{\"properties\":{}}}")));
            assertNotNull(SchemaValidator.validate(create, Json.parse("{\"body\":{\"namespace\":\"ops\"}}")),
                    "namespace is an array of strings in the document");
        }
    }

    static int send(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
