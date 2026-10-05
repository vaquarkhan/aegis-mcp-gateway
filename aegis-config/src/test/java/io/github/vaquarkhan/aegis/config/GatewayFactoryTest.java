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
package io.github.vaquarkhan.aegis.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.vaquarkhan.aegis.config.http.HttpToolExecutor;
import io.github.vaquarkhan.aegis.config.http.HttpToolSpec;
import io.github.vaquarkhan.aegis.engine.CallOutcome;
import io.github.vaquarkhan.aegis.engine.audit.AuditChainVerifier;
import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.model.DenyCodes;
import io.github.vaquarkhan.aegis.engine.model.ToolCall;
import io.github.vaquarkhan.aegis.mcp.http.McpHttpRequest;
import io.github.vaquarkhan.aegis.mcp.http.McpHttpResponse;
import io.github.vaquarkhan.aegis.mcp.testing.TestMcpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayFactoryTest {

    @TempDir
    Path dir;

    static final CallerIdentity ALICE = CallerIdentity.of("alice", Set.of("analysts"), "idp", "jwt");
    static final CallerIdentity OLGA = CallerIdentity.of("olga", Set.of("analysts", "operators"), "idp", "jwt");

    private static final Map<String, String> ENV = Map.of(
            "AEGIS_SIGNING_KEY", "k".repeat(40),
            "REST_TOKEN", "service-token-123");

    private BuildContext ctx() {
        return BuildContext.defaults(dir).withEnv(ENV::get);
    }

    /** Tiny REST backend recording requests. */
    static final class Rest implements AutoCloseable {
        final HttpServer server;
        final List<String> seen = Collections.synchronizedList(new ArrayList<>());

        Rest() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", ex -> {
                String auth = ex.getRequestHeaders().getFirst("Authorization");
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                seen.add(ex.getRequestMethod() + " " + ex.getRequestURI().getRawPath()
                        + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery())
                        + " auth=" + auth + " body=" + body);
                byte[] b = ("{\"items\":[{\"name\":\"orders\"}],\"secret\":\"" + "AKIA" + "ABCDEFGHIJKLMNOP\"}")
                        .getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(200, b.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(b);
                }
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private String yaml(String restUrl, String mcpUrl) {
        return """
                gateway:
                  name: test-gw
                access:
                  writeGroups: [operators]
                  approverGroups: [approvers]
                approval:
                  keyEnv: AEGIS_SIGNING_KEY
                policy:
                  rules:
                    - id: analysts-read
                      groups: [analysts]
                      classes: [READ]
                    - id: operators-write
                      groups: [operators]
                      classes: [MUTATE, DESTRUCTIVE]
                egress:
                  allowCidrs: [127.0.0.0/8]
                audit:
                  file: audit/audit.jsonl
                sources:
                  - id: rest
                    type: http
                    baseUrl: %s
                    credentials: {type: bearer, tokenEnv: REST_TOKEN}
                    tools:
                      - name: list_tables
                        description: List tables in a namespace.
                        class: READ
                        inputSchema:
                          type: object
                          properties: {namespace: {type: string}, pageSize: {type: integer}}
                          required: [namespace]
                        request:
                          method: GET
                          path: /v1/namespaces/{namespace}/tables
                          query: {pageSize: "{pageSize}"}
                        response: {pointer: /items}
                      - name: drop_table
                        description: Drop a table.
                        class: DESTRUCTIVE
                        inputSchema:
                          type: object
                          properties: {namespace: {type: string}, table: {type: string}}
                          required: [namespace, table]
                        request:
                          method: DELETE
                          path: /v1/namespaces/{namespace}/tables/{table}
                          query: {purgeRequested: "true"}
                        resources:
                          - {type: "iceberg:table", pointers: [/namespace, /table]}
                  - id: legacy-mcp
                    type: mcp
                    url: %s
                    namePrefix: "kafka_"
                    tools:
                      list_topics: {class: READ}
                      delete_topic:
                        class: READ
                        resources: [{type: "kafka:topic", pointer: /topic}]
                """.formatted(restUrl, mcpUrl);
    }

    @Test
    void yamlConfigBuildsAGovernedGatewayOverRestAndAnExistingMcpServer() throws Exception {
        try (Rest rest = new Rest();
             TestMcpServer mcp = new TestMcpServer(TestMcpServer.Mode.LEGACY_SESSION)) {
            mcp.tool("list_topics", "List topics.", Map.of("type", "object"), a -> TestMcpServer.textResult("orders"));
            Map<String, Object> ann = Map.of("destructiveHint", true);
            mcp.tool("delete_topic", "Delete topic.", Map.of("type", "object",
                    "properties", Map.of("topic", Map.of("type", "string"))), a -> TestMcpServer.textResult("deleted"));
            // The downstream marks delete_topic destructive; the operator (wrongly) said READ.
            // Hints may only raise authority, so it must end up DESTRUCTIVE.
            mcp.annotate("delete_topic", ann);

            Path cfg = dir.resolve("aegis.yaml");
            Files.writeString(cfg, yaml(rest.url(), mcp.uri().toString()));
            try (Gateway g = GatewayFactory.build(cfg, ctx())) {
                CallOutcome list = g.engine().call(ALICE, new ToolCall("1", "list_tables",
                        Map.of("namespace", "sales db", "pageSize", 10L), Map.of(), ""));
                assertTrue(list.allowed(), list.decision().toString());
                String out = Json.write(list.toMcpResult());
                assertTrue(out.contains("orders"));
                assertFalse(out.contains("AKIA"), "pointer selects /items only");
                assertEquals("GET /v1/namespaces/sales%20db/tables?pageSize=10 auth=Bearer service-token-123 body=",
                        rest.seen.get(0));

                assertTrue(g.engine().call(ALICE, new ToolCall("1", "kafka_list_topics", Map.of(), Map.of(), "")).allowed());
                CallOutcome del = g.engine().call(ALICE, new ToolCall("1", "kafka_delete_topic", Map.of("topic", "t"), Map.of(), ""));
                assertEquals(DenyCodes.READONLY_CALLER, del.decision().code(), "raised to DESTRUCTIVE by the upstream hint");

                CallOutcome drop = g.engine().call(OLGA, new ToolCall("1", "drop_table",
                        Map.of("namespace", "sales", "table", "orders"), Map.of(), ""));
                assertEquals(DenyCodes.APPROVAL_REQUIRED, drop.decision().code());
                assertEquals(1, rest.seen.size(), "nothing reached the backend for the denied drop");
            }
            AuditChainVerifier.Report r = AuditChainVerifier.verify(dir.resolve("audit/audit.jsonl"));
            assertTrue(r.ok(), r.error());
            assertTrue(r.records() >= 5);
        }
    }

    @Test
    void unknownKeysAnywhereFailStartup() throws Exception {
        Map<String, Object> cfg = new java.util.LinkedHashMap<>(Json.parseObject("""
                {"access":{"writeGrups":["ops"]},
                 "policy":{"rules":[{"id":"r","groups":["*"]}]},
                 "audit":{"file":"a.jsonl"},
                 "sources":[{"id":"s","type":"http","baseUrl":"http://127.0.0.1:1",
                   "tools":[{"name":"t","description":"d","class":"READ"}]}]}"""));
        ConfigException e = assertThrows(ConfigException.class, () -> GatewayFactory.build(cfg, ctx()));
        assertTrue(e.getMessage().contains("writeGrups"), e.getMessage());
    }

    @Test
    void pathPlaceholderMustBeRequiredAndPolicyMustExist() {
        Map<String, Object> bad = Json.parseObject("""
                {"policy":{"rules":[{"id":"r","groups":["*"]}]},"audit":{"file":"a.jsonl"},
                 "sources":[{"id":"s","type":"http","baseUrl":"http://127.0.0.1:1",
                   "tools":[{"name":"t","description":"d","class":"READ",
                     "inputSchema":{"type":"object","properties":{"id":{"type":"string"}}},
                     "request":{"path":"/jobs/{id}"}}]}]}""");
        assertTrue(assertThrows(ConfigException.class, () -> GatewayFactory.build(bad, ctx())).getMessage().contains("placeholder"));
        Map<String, Object> noPolicy = Json.parseObject("""
                {"audit":{"file":"a.jsonl"},
                 "sources":[{"id":"s","type":"http","baseUrl":"http://127.0.0.1:1",
                   "tools":[{"name":"t","description":"d","class":"READ"}]}]}""");
        assertTrue(assertThrows(ConfigException.class, () -> GatewayFactory.build(noPolicy, ctx())).getMessage().contains("policy"));
    }

    @Test
    void missingSecretFailsFastAndMissingApprovalKeyFailsClosed() throws Exception {
        Map<String, Object> missingSecret = Json.parseObject("""
                {"policy":{"rules":[{"id":"r","groups":["*"]}]},"audit":{"file":"a.jsonl"},
                 "sources":[{"id":"s","type":"http","baseUrl":"http://127.0.0.1:1",
                   "credentials":{"type":"bearer","tokenEnv":"NOT_SET"},
                   "tools":[{"name":"t","description":"d","class":"READ"}]}]}""");
        assertTrue(assertThrows(ConfigException.class, () -> GatewayFactory.build(missingSecret, ctx())).getMessage().contains("NOT_SET"));

        Map<String, Object> noKey = Json.parseObject("""
                {"access":{"writeGroups":["ops"],"approverGroups":["appr"]},
                 "policy":{"rules":[{"id":"r","groups":["*"]}]},"audit":{"file":"a.jsonl"},"egress":{"allowCidrs":["127.0.0.0/8"]},
                 "sources":[{"id":"s","type":"http","baseUrl":"http://127.0.0.1:1",
                   "tools":[{"name":"w","description":"d","class":"MUTATE"}]}]}""");
        try (Gateway g = GatewayFactory.build(noKey, ctx())) {
            CallOutcome o = g.engine().call(CallerIdentity.of("u", Set.of("ops"), "i", "t"), new ToolCall("1", "w", Map.of(), Map.of(), ""));
            assertEquals(DenyCodes.APPROVAL_REQUIRED, o.decision().code());
            assertTrue(o.decision().reason().contains("not configured"));
        }
    }

    @Test
    void sourceTrustQuarantineHidesEveryToolUntilOperatorConfigActivatesTheSource() throws Exception {
        Map<String, Object> quarantined = Json.parseObject("""
                {"policy":{"rules":[{"id":"r","groups":["*"]}]},"audit":{"file":"trust.jsonl"},
                 "sources":[{"id":"catalog","type":"http","baseUrl":"http://127.0.0.1:1",
                   "trust":{"state":"QUARANTINED","evidence":"docs/evidence/catalog-1.0.md"},
                   "tools":[{"name":"list","description":"d","class":"READ"}]}]}""");
        try (Gateway g = GatewayFactory.build(quarantined, ctx())) {
            assertTrue(g.engine().listTools(ALICE).isEmpty(), "a quarantined source must contribute no callable old tool");
        }
        Map<String, Object> missingEvidence = Json.parseObject("""
                {"policy":{"rules":[{"id":"r","groups":["*"]}]},"audit":{"file":"trust2.jsonl"},
                 "sources":[{"id":"catalog","type":"http","baseUrl":"http://127.0.0.1:1",
                   "trust":{"state":"QUARANTINED"},
                   "tools":[{"name":"list","description":"d","class":"READ"}]}]}""");
        Exception e = assertThrows(Exception.class, () -> GatewayFactory.build(missingEvidence, ctx()));
        assertTrue(String.valueOf(e.getMessage()).contains("evidence"), e.toString());
    }

    @Test
    void dryRunModesThatWouldRunTheRealCallAreRejectedAtStartup() throws Exception {
        // ARGUMENT flag not referenced by the http template: the backend would never see it.
        Map<String, Object> argNotSent = Json.parseObject("""
                {"policy":{"rules":[{"id":"r","groups":["*"]}]},"audit":{"file":"a.jsonl"},
                 "sources":[{"id":"s","type":"http","baseUrl":"http://127.0.0.1:1",
                   "tools":[{"name":"drop","description":"d","class":"DESTRUCTIVE",
                     "inputSchema":{"type":"object","properties":{"validateOnly":{"type":"boolean"}}},
                     "request":{"method":"DELETE","path":"/x"},
                     "dryRun":{"mode":"ARGUMENT","argument":"validateOnly"}}]}]}""");
        assertTrue(assertThrows(ConfigException.class, () -> GatewayFactory.build(argNotSent, ctx()))
                .getMessage().contains("not referenced"));

        // ARGUMENT flag the upstream schema does not declare (mcp source).
        try (TestMcpServer mcp = new TestMcpServer(TestMcpServer.Mode.MODERN)) {
            mcp.tool("delete_topic", "Delete.", Map.of("type", "object"), a -> TestMcpServer.textResult("deleted"));
            Map<String, Object> undeclared = Json.parseObject("""
                    {"policy":{"rules":[{"id":"r","groups":["*"]}]},"audit":{"file":"b.jsonl"},"egress":{"allowCidrs":["127.0.0.0/8"]},
                     "sources":[{"id":"m","type":"mcp","url":"%s",
                       "tools":{"delete_topic":{"dryRun":{"mode":"ARGUMENT","argument":"validateOnly"}}}}]}"""
                    .formatted(mcp.uri()));
            Exception e = assertThrows(Exception.class, () -> GatewayFactory.build(undeclared, ctx()));
            assertTrue(String.valueOf(e.getMessage()).contains("not a property")
                    || String.valueOf(e.getCause()).contains("not a property"), e.toString());
        }
    }

    @Test
    void resourcesAndPromptsAreOptInStrictAndServedThroughTheHandler() throws Exception {
        try (TestMcpServer mcp = new TestMcpServer(TestMcpServer.Mode.MODERN)) {
            mcp.tool("list_topics", "List topics.", Map.of("type", "object"), a -> TestMcpServer.textResult("orders"));
            mcp.resource("file:///data/a.csv", "a", "text/csv", "id\n1");
            mcp.prompt("summarize", "Summarize a table.", Map.of("table", true), "Summarize {table}.");
            String base = """
                    {"policy":{"rules":[{"id":"r","groups":["*"],"classes":["READ"]}]},"audit":{"file":"c.jsonl"},
                     "egress":{"allowCidrs":["127.0.0.0/8"]},
                     "sources":[{"id":"m","type":"mcp","url":"%s","namePrefix":"m_"%s}]}""";
            // Off by default: tools only, content methods are not offered.
            try (Gateway g = GatewayFactory.build(Json.parseObject(base.formatted(mcp.uri(), "")), ctx())) {
                McpHttpResponse r = g.mcp().handleHttp(modernPost("resources/list", Map.of(), null), ALICE);
                assertTrue(r.bodyText().contains("-32601"), r.bodyText());
            }
            String on = ",\"resources\":{\"uriPatterns\":[\"file:///data/*\"]},\"prompts\":{\"names\":[\"sum*\"]},\"completions\":true";
            try (Gateway g = GatewayFactory.build(Json.parseObject(base.formatted(mcp.uri(), on)), ctx())) {
                McpHttpResponse read = g.mcp().handleHttp(modernPost("resources/read", Map.of("uri", "file:///data/a.csv"),
                        "file:///data/a.csv"), ALICE);
                assertEquals(200, read.status(), read.bodyText());
                assertTrue(read.bodyText().contains("id\\n1"), read.bodyText());
                McpHttpResponse get = g.mcp().handleHttp(modernPost("prompts/get",
                        Map.of("name", "m_summarize", "arguments", Map.of("table", "t1")), "m_summarize"), ALICE);
                assertTrue(get.bodyText().contains("Summarize t1."), get.bodyText());
            }
            // Strictness: "*" patterns, completions without content, unknown keys, content on http sources.
            assertThrows(ConfigException.class, () -> GatewayFactory.build(Json.parseObject(base.formatted(mcp.uri(),
                    ",\"resources\":{\"uriPatterns\":[\"*\"]}")), ctx()));
            assertThrows(ConfigException.class, () -> GatewayFactory.build(Json.parseObject(base.formatted(mcp.uri(),
                    ",\"completions\":true")), ctx()));
            assertThrows(ConfigException.class, () -> GatewayFactory.build(Json.parseObject(base.formatted(mcp.uri(),
                    ",\"resources\":{\"uriPatterns\":[\"file:///x/*\"],\"subscribe\":true}")), ctx()));
            Map<String, Object> httpWithResources = Json.parseObject("""
                    {"policy":{"rules":[{"id":"r","groups":["*"]}]},"audit":{"file":"d.jsonl"},
                     "sources":[{"id":"s","type":"http","baseUrl":"http://127.0.0.1:1","resources":{"uriPatterns":["file:///x/*"]},
                       "tools":[{"name":"t","description":"d","class":"READ"}]}]}""");
            assertTrue(assertThrows(ConfigException.class, () -> GatewayFactory.build(httpWithResources, ctx()))
                    .getMessage().contains("resources"));
        }
    }

    private static McpHttpRequest modernPost(String method, Map<String, Object> params, String name) {
        Map<String, Object> p = new java.util.LinkedHashMap<>(params);
        p.put("_meta", Map.of("io.modelcontextprotocol/protocolVersion", "2026-07-28"));
        Map<String, java.util.List<String>> h = new java.util.LinkedHashMap<>();
        h.put("MCP-Protocol-Version", java.util.List.of("2026-07-28"));
        h.put("Mcp-Method", java.util.List.of(method));
        if (name != null) {
            h.put("Mcp-Name", java.util.List.of(name));
        }
        return new McpHttpRequest("POST", "", h, Json.writeBytes(Map.of("jsonrpc", "2.0", "id", 1L, "method", method, "params", p)));
    }

    @Test
    void httpTemplatesAreInjectionSafe() {
        HttpToolExecutor e = new HttpToolExecutor(URI.create("http://h:1/api/"),
                new HttpToolSpec("GET", "/topics/{topic}", Map.of("q", "{q}"), null, Map.of(), null, null, 0), null);
        assertEquals("http://h:1/api/topics/a%3Fb%23?q=x%26y%3Dz", e.render(Map.of("topic", "a?b#", "q", "x&y=z")).toString());
        assertThrows(IllegalArgumentException.class, () -> e.render(Map.of("topic", "..")));
        // Separators and escapes are refused outright (some backends decode %2F before routing).
        assertThrows(IllegalArgumentException.class, () -> e.render(Map.of("topic", "a/../admin")));
        assertThrows(IllegalArgumentException.class, () -> e.render(Map.of("topic", "a%2Fb")));
        assertThrows(IllegalArgumentException.class, () -> e.render(Map.of("topic", "x..y")));
        assertThrows(IllegalArgumentException.class, () -> e.render(Map.of("topic", Map.of("x", 1L))));
        assertEquals("http://h:1/api/topics/t", e.render(Map.of("topic", "t")).toString(), "absent optional query omitted");
    }

    @Test
    void pathTypedPlaceholderAcceptsDepthButNotTraversal() {
        // APIs whose identifier is itself a path: HDFS files, ZooKeeper znodes, object-store keys.
        HttpToolExecutor e = new HttpToolExecutor(URI.create("http://h:1"),
                new HttpToolSpec("GET", "/webhdfs/v1/{path/}", Map.of("op", "LISTSTATUS"), null, Map.of(), null, null, 0), null);
        assertEquals("http://h:1/webhdfs/v1/user/hive/warehouse?op=LISTSTATUS",
                e.render(Map.of("path", "/user/hive/warehouse")).toString());
        assertEquals("http://h:1/webhdfs/v1/user/hive?op=LISTSTATUS", e.render(Map.of("path", "user/hive/")).toString(),
                "leading and trailing slashes are normalised");
        assertEquals("http://h:1/webhdfs/v1/?op=LISTSTATUS", e.render(Map.of("path", "/")).toString(), "root");
        assertEquals("http://h:1/webhdfs/v1/a%20b/c%23d?op=LISTSTATUS", e.render(Map.of("path", "/a b/c#d")).toString(),
                "every segment is encoded individually");
        // Depth is allowed; climbing, escapes and empty segments are not.
        for (String bad : new String[] {"/user/../etc", "..", "/a/./b", "/a//b", "/a/%2e%2e/b", "/a/b\\c", "/a/x..y/b"}) {
            assertThrows(IllegalArgumentException.class, () -> e.render(Map.of("path", bad)), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> e.render(Map.of()), "a missing path-typed argument is refused");
        // The placeholder name is still discovered, so startup still requires it in inputSchema.required.
        assertEquals(List.of("path"), HttpToolExecutor.pathPlaceholders("/webhdfs/v1/{path/}"));
        assertEquals(List.of("a", "b"), HttpToolExecutor.pathPlaceholders("/x/{a}/y/{b/}"));
    }
}
