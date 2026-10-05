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
package io.github.vaquarkhan.aegis.mcp.server;

import static io.github.vaquarkhan.aegis.mcp.testing.TestEngines.ALICE;
import static io.github.vaquarkhan.aegis.mcp.testing.TestEngines.OLGA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.json.JsonValues;
import io.github.vaquarkhan.aegis.engine.model.ToolClass;
import io.github.vaquarkhan.aegis.engine.model.ToolDescriptor;
import io.github.vaquarkhan.aegis.engine.model.ToolResult;
import io.github.vaquarkhan.aegis.engine.spi.ExecutionContext;
import io.github.vaquarkhan.aegis.engine.spi.ToolExecutor;
import io.github.vaquarkhan.aegis.engine.spi.ToolSource;
import io.github.vaquarkhan.aegis.mcp.http.McpHttpRequest;
import io.github.vaquarkhan.aegis.mcp.http.McpHttpResponse;
import io.github.vaquarkhan.aegis.mcp.protocol.HeaderValues;
import io.github.vaquarkhan.aegis.mcp.testing.TestEngines;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class McpServerHandlerTest {

    private McpServerHandler handler;
    private McpServerHandler statelessLegacy;

    @BeforeEach
    void setUp() throws Exception {
        ToolExecutor echo = new ToolExecutor() {
            @Override
            public List<java.net.URI> egressTargets(Map<String, Object> a) {
                return List.of();
            }

            @Override
            public ToolResult execute(ExecutionContext c) {
                return ToolResult.text("echo:" + c.arguments().get("text"), false);
            }
        };
        ToolSource src = new ToolSource() {
            @Override
            public String id() {
                return "local";
            }

            @Override
            public List<SourcedTool> discover() {
                return List.of(
                        new SourcedTool(new ToolDescriptor("echo", null, "Echo.", Json.parseObject(
                                "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}"), null, null), echo),
                        new SourcedTool(new ToolDescriptor("wipe", null, "Wipe.", Json.parseObject("{\"type\":\"object\"}"), null, null), echo),
                        new SourcedTool(new ToolDescriptor("résumé", null, "Unicode name.", Json.parseObject("{\"type\":\"object\"}"), null, null), echo));
            }
        };
        TestEngines.Built b = TestEngines.build(List.of(src), Map.of("echo", ToolClass.READ, "résumé", ToolClass.READ));
        handler = new McpServerHandler(b.engine, ServerOptions.defaults(), new SessionRegistry(Clock.systemUTC(), 60_000, 100));
        statelessLegacy = new McpServerHandler(b.engine, new ServerOptions(null, null, null, false, null, 0, null, null),
                new SessionRegistry(Clock.systemUTC(), 60_000, 100));
    }

    private static Map<String, Object> modernParams(Map<String, Object> extra) {
        Map<String, Object> p = new LinkedHashMap<>(extra);
        p.put("_meta", Map.of("io.modelcontextprotocol/protocolVersion", "2026-07-28",
                "io.modelcontextprotocol/clientCapabilities", Map.of()));
        return p;
    }

    private static McpHttpRequest post(Map<String, Object> body, Map<String, String> headers) {
        Map<String, List<String>> h = new LinkedHashMap<>();
        headers.forEach((k, v) -> h.put(k, List.of(v)));
        return new McpHttpRequest("POST", "", h, Json.writeBytes(body));
    }

    private static Map<String, Object> rpc(String method, Map<String, Object> params) {
        return Map.of("jsonrpc", "2.0", "id", 7L, "method", method, "params", params);
    }

    private static Map<String, Object> body(McpHttpResponse r) {
        return Json.parseObject(r.bodyText());
    }

    private static Long errorCode(McpHttpResponse r) {
        return JsonValues.integral(JsonValues.object(body(r), "error").get("code"));
    }

    @Test
    void modernCallRequiresMatchingHeaders() {
        Map<String, Object> call = rpc("tools/call", modernParams(Map.of("name", "echo", "arguments", Map.of("text", "hi"))));
        McpHttpResponse ok = handler.handleHttp(post(call, Map.of("MCP-Protocol-Version", "2026-07-28",
                "Mcp-Method", "tools/call", "Mcp-Name", "echo")), ALICE);
        assertEquals(200, ok.status(), ok.bodyText());
        Map<String, Object> result = JsonValues.object(body(ok), "result");
        assertEquals("complete", result.get("resultType"));
        assertTrue(Json.write(result).contains("echo:hi"));

        McpHttpResponse missing = handler.handleHttp(post(call, Map.of("MCP-Protocol-Version", "2026-07-28", "Mcp-Method", "tools/call")), ALICE);
        assertEquals(400, missing.status());
        assertEquals(-32020L, errorCode(missing));

        // Header routes to a harmless tool while the body calls another: rejected, never executed.
        McpHttpResponse smuggle = handler.handleHttp(post(call, Map.of("MCP-Protocol-Version", "2026-07-28",
                "Mcp-Method", "tools/call", "Mcp-Name", "list_everything")), ALICE);
        assertEquals(-32020L, errorCode(smuggle));
    }

    @Test
    void base64SentinelNameIsDecodedBeforeComparison() {
        Map<String, Object> call = rpc("tools/call", modernParams(Map.of("name", "résumé")));
        McpHttpResponse r = handler.handleHttp(post(call, Map.of("MCP-Protocol-Version", "2026-07-28",
                "Mcp-Method", "tools/call", "Mcp-Name", HeaderValues.encode("résumé"))), ALICE);
        assertEquals(200, r.status(), r.bodyText());
        assertTrue(HeaderValues.encode("résumé").startsWith("=?base64?"));
    }

    @Test
    void unsupportedModernVersionListsSupportedVersions() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("_meta", Map.of("io.modelcontextprotocol/protocolVersion", "2099-01-01"));
        McpHttpResponse r = handler.handleHttp(post(rpc("tools/list", p),
                Map.of("MCP-Protocol-Version", "2099-01-01", "Mcp-Method", "tools/list")), ALICE);
        assertEquals(400, r.status());
        assertEquals(-32022L, errorCode(r));
        Map<String, Object> data = JsonValues.object(JsonValues.object(body(r), "error"), "data");
        assertEquals(List.of("2026-07-28"), data.get("supported"));
        assertEquals("2099-01-01", data.get("requested"));
    }

    @Test
    void discoverAndListArePrivateCacheableAndFilteredPerCaller() {
        McpHttpResponse d = handler.handleHttp(post(rpc("server/discover", modernParams(Map.of())),
                Map.of("MCP-Protocol-Version", "2026-07-28", "Mcp-Method", "server/discover")), ALICE);
        Map<String, Object> dr = JsonValues.object(body(d), "result");
        assertTrue(((List<?>) dr.get("supportedVersions")).containsAll(List.of("2026-07-28", "2025-11-25")));
        assertEquals("private", dr.get("cacheScope"));

        McpHttpResponse l = handler.handleHttp(post(rpc("tools/list", modernParams(Map.of())),
                Map.of("MCP-Protocol-Version", "2026-07-28", "Mcp-Method", "tools/list")), ALICE);
        Map<String, Object> lr = JsonValues.object(body(l), "result");
        assertEquals("private", lr.get("cacheScope"));
        assertNotNull(lr.get("ttlMs"));
        List<String> names = ((List<?>) lr.get("tools")).stream().map(t -> (String) ((Map<?, ?>) t).get("name")).toList();
        assertEquals(List.of("echo", "résumé"), names, "DESTRUCTIVE 'wipe' is hidden from a read-only caller");
    }

    @Test
    void legacySessionIsCallerBoundAndRequired() {
        McpHttpResponse init = handler.handleHttp(post(rpc("initialize", Map.of("protocolVersion", "2025-06-18",
                "capabilities", Map.of(), "clientInfo", Map.of("name", "c", "version", "1"))), Map.of()), ALICE);
        assertEquals(200, init.status());
        String sid = init.headers().get("Mcp-Session-Id");
        assertNotNull(sid);
        assertEquals("2025-06-18", JsonValues.object(body(init), "result").get("protocolVersion"));

        McpHttpResponse noSession = handler.handleHttp(post(rpc("tools/list", Map.of()), Map.of()), ALICE);
        assertEquals(400, noSession.status());
        McpHttpResponse hijack = handler.handleHttp(post(rpc("tools/list", Map.of()), Map.of("Mcp-Session-Id", sid)), OLGA);
        assertEquals(404, hijack.status(), "another principal presenting the id sees 'not found'");
        McpHttpResponse ok = handler.handleHttp(post(rpc("tools/call", Map.of("name", "echo", "arguments", Map.of("text", "x"))),
                Map.of("Mcp-Session-Id", sid, "MCP-Protocol-Version", "2025-06-18")), ALICE);
        assertEquals(200, ok.status());
        assertNull(JsonValues.object(body(ok), "result").get("resultType"), "legacy results carry no resultType");
        McpHttpResponse wrongVersion = handler.handleHttp(post(rpc("tools/list", Map.of()),
                Map.of("Mcp-Session-Id", sid, "MCP-Protocol-Version", "2025-11-25")), ALICE);
        assertEquals(400, wrongVersion.status());

        McpHttpResponse del = handler.handleHttp(new McpHttpRequest("DELETE", "", Map.of("Mcp-Session-Id", List.of(sid)), null), ALICE);
        assertEquals(200, del.status());
        assertEquals(404, handler.handleHttp(post(rpc("tools/list", Map.of()), Map.of("Mcp-Session-Id", sid)), ALICE).status());
    }

    @Test
    void legacyRequestsWithMirroredHeadersMustAlsoAgreeWithTheBody() {
        McpHttpResponse init = handler.handleHttp(post(rpc("initialize", Map.of("protocolVersion", "2025-11-25")), Map.of()), ALICE);
        String sid = init.headers().get("Mcp-Session-Id");
        McpHttpResponse smuggle = handler.handleHttp(post(rpc("tools/call", Map.of("name", "wipe")),
                Map.of("Mcp-Session-Id", sid, "Mcp-Name", "echo")), ALICE);
        assertEquals(400, smuggle.status());
        assertEquals(-32020L, errorCode(smuggle));
        McpHttpResponse ok = handler.handleHttp(post(rpc("tools/call", Map.of("name", "echo", "arguments", Map.of("text", "x"))),
                Map.of("Mcp-Session-Id", sid, "Mcp-Name", "echo", "Mcp-Method", "tools/call")), ALICE);
        assertEquals(200, ok.status(), ok.bodyText());
    }

    @Test
    void oneCallerCannotExhaustTheSessionRegistry() {
        SessionRegistry reg = new SessionRegistry(Clock.systemUTC(), 60_000, 1000);
        for (int i = 0; i < 200; i++) {
            assertTrue(reg.create(ALICE, "2025-11-25").isPresent());
        }
        assertTrue(reg.size() <= SessionRegistry.MAX_SESSIONS_PER_CALLER, "size=" + reg.size());
        assertTrue(reg.create(OLGA, "2025-11-25").isPresent(), "other callers are unaffected");
    }

    @Test
    void statelessLegacyModeAssumes20250326WithoutHeader() {
        McpHttpResponse r = statelessLegacy.handleHttp(post(rpc("tools/list", Map.of()), Map.of()), ALICE);
        assertEquals(200, r.status(), r.bodyText());
        McpHttpResponse init = statelessLegacy.handleHttp(post(rpc("initialize", Map.of("protocolVersion", "2025-11-25")), Map.of()), ALICE);
        assertNull(init.headers().get("Mcp-Session-Id"));
    }

    @Test
    void transportLevelRejections() {
        assertEquals(405, handler.handleHttp(new McpHttpRequest("GET", "", Map.of(), null), ALICE).status());
        assertEquals(403, handler.handleHttp(new McpHttpRequest("POST", "", Map.of("Origin", List.of("https://evil.example")),
                "{}".getBytes(StandardCharsets.UTF_8)), ALICE).status());
        assertEquals(401, handler.handleHttp(post(rpc("tools/list", Map.of()), Map.of()), null).status());
        McpHttpResponse batch = handler.handleHttp(new McpHttpRequest("POST", "", Map.of(), "[{}]".getBytes(StandardCharsets.UTF_8)), ALICE);
        assertEquals(400, batch.status());
        McpHttpResponse dup = handler.handleHttp(new McpHttpRequest("POST", "", Map.of(),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"method\":\"tools/call\"}".getBytes(StandardCharsets.UTF_8)), ALICE);
        assertEquals(400, dup.status());
        assertEquals(-32700L, errorCode(dup));
        Map<String, Object> initialized = Map.of("jsonrpc", "2.0", "method", "notifications/initialized");
        // Stateful mode: even a notification must belong to a session.
        assertEquals(400, handler.handleHttp(post(initialized, Map.of()), ALICE).status());
        assertEquals(202, statelessLegacy.handleHttp(post(initialized, Map.of()), ALICE).status());
        Map<String, Object> modernNote = Map.of("jsonrpc", "2.0", "method", "notifications/cancelled",
                "params", modernParams(Map.of("requestId", 1L)));
        assertEquals(202, handler.handleHttp(post(modernNote, Map.of("MCP-Protocol-Version", "2026-07-28",
                "Mcp-Method", "notifications/cancelled")), ALICE).status());
        McpHttpResponse resources = handler.handleHttp(post(rpc("resources/list", modernParams(Map.of())),
                Map.of("MCP-Protocol-Version", "2026-07-28", "Mcp-Method", "resources/list")), ALICE);
        assertEquals(404, resources.status());
        assertEquals(-32601L, errorCode(resources));
    }

    @Test
    void stdioConnectionServesBothEras() {
        McpServerHandler.StdioConnection c = handler.stdio(ALICE);
        assertTrue(c.handleLine(Json.write(rpc("tools/list", Map.of()))).contains("initialize first"));
        assertTrue(c.handleLine(Json.write(rpc("initialize", Map.of("protocolVersion", "2025-11-25")))).contains("2025-11-25"));
        assertNull(c.handleLine(Json.write(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"))));
        assertTrue(c.handleLine(Json.write(rpc("tools/call", Map.of("name", "echo", "arguments", Map.of("text", "s"))))).contains("echo:s"));
        McpServerHandler.StdioConnection m = handler.stdio(ALICE);
        String modern = m.handleLine(Json.write(rpc("tools/call", modernParams(Map.of("name", "echo", "arguments", Map.of("text", "m"))))));
        assertTrue(modern.contains("echo:m") && modern.contains("\"resultType\":\"complete\""), modern);
    }
}
