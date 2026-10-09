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
package io.github.vaquarkhan.aegis.config.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.config.BuildContext;
import io.github.vaquarkhan.aegis.config.ConfigException;
import io.github.vaquarkhan.aegis.config.ConfigLoader;
import io.github.vaquarkhan.aegis.config.Gateway;
import io.github.vaquarkhan.aegis.config.GatewayFactory;
import io.github.vaquarkhan.aegis.engine.CallOutcome;
import io.github.vaquarkhan.aegis.engine.catalog.ToolCatalog;
import io.github.vaquarkhan.aegis.engine.control.SchemaValidator;
import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.json.JsonValues;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.model.DenyCodes;
import io.github.vaquarkhan.aegis.engine.model.ToolCall;
import io.github.vaquarkhan.aegis.engine.model.ToolClass;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenApiDraftTest {

    @TempDir
    Path dir;

    /** A small document that exercises every branch a real API document tends to hit. */
    static final String SPEC = """
            openapi: 3.0.3
            info: {title: Orders API, version: "1.4"}
            servers:
              - url: "{scheme}://{host}/api"
                variables:
                  scheme: {default: http}
                  host: {default: "127.0.0.1:1"}
            paths:
              /orders/{orderId}:
                parameters:
                  - {name: orderId, in: path, required: true, schema: {type: string, maxLength: 36}}
                get:
                  operationId: getOrder
                  summary: Get one order.
                  parameters:
                    - {name: X-Request-Id, in: header, schema: {type: string}}
                    - {$ref: "#/components/parameters/Expand"}
                  responses:
                    200: {description: ok}
                delete:
                  operationId: deleteOrder
                  description: |
                    Deletes the order.
                    It cannot be undone.
                  deprecated: true
                  responses:
                    204: {description: gone}
              /orders:
                post:
                  summary: Create an order.
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema: {$ref: "#/components/schemas/Order"}
                  responses:
                    201: {description: created}
              /uploads:
                put:
                  operationId: upload
                  summary: Upload a file.
                  requestBody:
                    content:
                      multipart/form-data:
                        schema: {type: object}
                  responses:
                    200: {description: ok}
            components:
              parameters:
                Expand: {name: expand, in: query, schema: {type: array, items: {type: string}}}
              schemas:
                Order:
                  type: object
                  required: [sku]
                  properties:
                    sku: {type: string, maxLength: 12, example: "SECRET-SAMPLE"}
                    note: {type: string, nullable: true}
                    parent: {$ref: "#/components/schemas/Order"}
                    customer: {$ref: "https://schemas.example.com/customer.json"}
            """;

    private Map<String, Object> spec() throws Exception {
        Path f = dir.resolve("orders.yaml");
        Files.writeString(f, SPEC, StandardCharsets.UTF_8);
        return OpenApiDraft.read(f);
    }

    @Test
    void everyToolIsDestructiveAndThePolicyDeniesEveryone() throws Exception {
        OpenApiDraft.Draft d = OpenApiDraft.generate(spec(), new OpenApiDraft.Options("orders", null));
        assertEquals(3, d.tools(), d.yaml());
        Path cfg = dir.resolve("draft.yaml");
        Files.writeString(cfg, d.yaml(), StandardCharsets.UTF_8);
        try (Gateway g = GatewayFactory.build(cfg, BuildContext.defaults(dir))) {
            List<ToolCatalog.Entry> entries = g.engine().catalog().entries();
            assertEquals(3, entries.size());
            for (ToolCatalog.Entry e : entries) {
                assertEquals(ToolClass.DESTRUCTIVE, e.tool().toolClass(), e.tool().exposedName());
            }
            CallerIdentity anyone = CallerIdentity.of("u", Set.of("admins"), "idp", "jwt");
            assertTrue(g.engine().listTools(anyone).isEmpty(), "deny-all policy: nothing is offered");
            CallOutcome o = g.engine().call(anyone, new ToolCall("1", "orders_get_order", Map.of("orderId", "a1"), Map.of(), ""));
            assertFalse(o.allowed());
            assertEquals(DenyCodes.READONLY_CALLER, o.decision().code(), "no access groups are generated");
        }
        // Even after an operator grants write and destructive access, the deny-all rule still holds.
        Map<String, Object> granted = new java.util.LinkedHashMap<>(ConfigLoader.parseYaml(d.yaml(), "draft"));
        granted.put("access", Map.of("writeGroups", List.of("admins"), "destructiveGroups", List.of("admins")));
        try (Gateway g = GatewayFactory.build(granted, BuildContext.defaults(dir))) {
            CallOutcome o = g.engine().call(CallerIdentity.of("u", Set.of("admins"), "idp", "jwt"),
                    new ToolCall("1", "orders_get_order", Map.of("orderId", "a1"), Map.of(), ""));
            assertEquals(DenyCodes.POLICY_DENIED, o.decision().code(), o.decision().reason());
        }
    }

    @Test
    void namesSchemasAndTemplatesFollowTheDocument() throws Exception {
        OpenApiDraft.Draft d = OpenApiDraft.generate(spec(), new OpenApiDraft.Options("orders", null));
        Map<String, Object> cfg = ConfigLoader.parseYaml(d.yaml(), "draft");
        Map<String, Object> source = JsonValues.asObject(JsonValues.asList(cfg.get("sources")).get(0));
        assertEquals("http://127.0.0.1:1/api", source.get("baseUrl"));
        List<Object> tools = JsonValues.asList(source.get("tools"));
        Map<String, Map<String, Object>> byName = new java.util.LinkedHashMap<>();
        for (Object t : tools) {
            Map<String, Object> m = JsonValues.asObject(t);
            byName.put((String) m.get("name"), m);
        }
        assertEquals(Set.of("orders_get_order", "orders_delete_order", "orders_post_orders"), byName.keySet());

        Map<String, Object> get = byName.get("orders_get_order");
        assertEquals(Map.of("method", "GET", "path", "/orders/{orderId}", "query", Map.of("expand", "{expand}")), get.get("request"));
        Map<String, Object> getSchema = JsonValues.asObject(get.get("inputSchema"));
        assertEquals(List.of("orderId"), getSchema.get("required"));
        assertEquals(Boolean.FALSE, getSchema.get("additionalProperties"));
        assertNull(SchemaValidator.validate(getSchema, Map.of("orderId", "a1")));
        assertNotNull(SchemaValidator.validate(getSchema, Map.of("orderId", "x".repeat(37))), "maxLength copied");
        assertNotNull(SchemaValidator.validate(getSchema, Map.of("orderId", "a", "X-Request-Id", "1")), "header left out");

        assertEquals("Deletes the order. It cannot be undone.", byName.get("orders_delete_order").get("description"));

        Map<String, Object> post = byName.get("orders_post_orders");
        assertEquals("{body}", JsonValues.asObject(post.get("request")).get("body"));
        Map<String, Object> postSchema = JsonValues.asObject(post.get("inputSchema"));
        assertEquals(List.of("body"), postSchema.get("required"));
        assertNull(SchemaValidator.validate(postSchema, Json.parse("{\"body\":{\"sku\":\"A1\",\"note\":null,"
                + "\"parent\":{\"anything\":true}}}")), "nullable became type [string, null]; the cycle is left open");
        assertNotNull(SchemaValidator.validate(postSchema, Json.parse("{\"body\":{\"sku\":\"too-long-sku-value\"}}")));
        assertNotNull(SchemaValidator.validate(postSchema, Json.parse("{\"body\":{}}")), "required copied from the $ref target");
        assertFalse(d.yaml().contains("SECRET-SAMPLE"), "examples are not copied");
        assertFalse(Json.write(cfg).contains("\"$ref\""), "no reference survives into the draft (warnings mention them in comments)");
    }

    @Test
    void whatCannotBeExpressedIsReportedNotGuessed() throws Exception {
        OpenApiDraft.Draft d = OpenApiDraft.generate(spec(), new OpenApiDraft.Options("orders", null));
        String all = String.join("\n", d.warnings());
        assertTrue(all.contains("PUT /uploads: skipped, the request body is not JSON"), all);
        assertTrue(all.contains("header parameter 'X-Request-Id' left out"), all);
        assertTrue(all.contains("query parameter 'expand' is an array"), all);
        assertTrue(all.contains("remote $ref 'https://schemas.example.com/customer.json' was not fetched"), all);
        assertTrue(all.contains("recursive $ref '#/components/schemas/Order'"), all);
        assertTrue(all.contains("base URL taken from servers[0]"), all);
        for (String w : d.warnings()) {
            assertTrue(d.yaml().contains("#  - " + w), "every warning is also in the file: " + w);
        }
        assertTrue(d.yaml().contains("# The document marks this operation deprecated."));
    }

    @Test
    void optionsAndBadInputAreChecked() throws Exception {
        Map<String, Object> s = spec();
        OpenApiDraft.Draft d = OpenApiDraft.generate(s, new OpenApiDraft.Options("orders", "https://orders.internal.example/v2"));
        assertTrue(d.yaml().contains("baseUrl: \"https://orders.internal.example/v2\""), d.yaml());
        assertFalse(String.join(" ", d.warnings()).contains("servers[0]"));
        assertThrows(ConfigException.class, () -> OpenApiDraft.generate(s, new OpenApiDraft.Options("Bad Id", null)));
        assertThrows(ConfigException.class, () -> OpenApiDraft.generate(s, new OpenApiDraft.Options("orders", "/relative")));
        assertThrows(ConfigException.class, () -> OpenApiDraft.generate(s, new OpenApiDraft.Options("orders", "ftp://x/y")));
        assertThrows(ConfigException.class, () -> OpenApiDraft.generate(Map.of("swagger", "2.0", "paths", Map.of()), null));
        assertThrows(ConfigException.class, () -> OpenApiDraft.generate(Map.of("openapi", "3.1.0", "paths", Map.of()),
                new OpenApiDraft.Options(null, "http://h")));
        Map<String, Object> noServers = Json.parseObject("{\"openapi\":\"3.1.0\",\"paths\":{\"/a\":{\"get\":{}}}}");
        assertTrue(assertThrows(ConfigException.class, () -> OpenApiDraft.generate(noServers, null)).getMessage().contains("--base-url"));
    }

    @Test
    void jsonDocumentsDuplicateNamesAndQuotingAreHandled() throws Exception {
        Path f = dir.resolve("a.json");
        Files.writeString(f, """
                {"openapi":"3.1.0","info":{"title":"Q \\"quoted\\" API","version":"1"},
                 "paths":{
                   "/a":{"get":{"operationId":"list","summary":"Line one\\nsays: \\"hi\\" # not a comment"}},
                   "/b":{"get":{"operationId":"list"}},
                   "/c/{id}":{"get":{}}}}""", StandardCharsets.UTF_8);
        OpenApiDraft.Draft d = OpenApiDraft.generate(OpenApiDraft.read(f), new OpenApiDraft.Options("q", "http://127.0.0.1:1"));
        Map<String, Object> cfg = ConfigLoader.parseYaml(d.yaml(), "draft");
        List<Object> tools = JsonValues.asList(JsonValues.asObject(JsonValues.asList(cfg.get("sources")).get(0)).get("tools"));
        assertEquals(List.of("q_list", "q_list_2", "q_get_c_by_id"),
                tools.stream().map(t -> JsonValues.asObject(t).get("name")).toList());
        assertEquals("Line one says: \"hi\" # not a comment", JsonValues.asObject(tools.get(0)).get("description"));
        assertEquals("GET /b", JsonValues.asObject(tools.get(1)).get("description"));
        Map<String, Object> idSchema = JsonValues.asObject(JsonValues.asObject(tools.get(2)).get("inputSchema"));
        assertEquals(List.of("id"), idSchema.get("required"), "undeclared path parameter added as required");
        Path out = dir.resolve("q.yaml");
        Files.writeString(out, d.yaml(), StandardCharsets.UTF_8);
        try (Gateway g = GatewayFactory.build(out, BuildContext.defaults(dir))) {
            assertEquals(3, g.engine().catalog().entries().size());
        }
    }
}
