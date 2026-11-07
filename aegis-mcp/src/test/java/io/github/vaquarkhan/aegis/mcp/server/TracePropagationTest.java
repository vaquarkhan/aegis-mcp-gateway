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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.engine.GovernanceSettings;
import io.github.vaquarkhan.aegis.engine.audit.InMemoryAuditSink;
import io.github.vaquarkhan.aegis.engine.catalog.CatalogIntegrity;
import io.github.vaquarkhan.aegis.engine.content.ContentGovernor;
import io.github.vaquarkhan.aegis.engine.content.ContentSettings;
import io.github.vaquarkhan.aegis.engine.control.CircuitBreakers;
import io.github.vaquarkhan.aegis.engine.control.EgressGuard;
import io.github.vaquarkhan.aegis.engine.control.InjectionScreen;
import io.github.vaquarkhan.aegis.engine.control.OutputGuard;
import io.github.vaquarkhan.aegis.engine.control.RateLimiter;
import io.github.vaquarkhan.aegis.engine.control.RulePolicy;
import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.json.JsonValues;
import io.github.vaquarkhan.aegis.engine.model.ToolClass;
import io.github.vaquarkhan.aegis.engine.observe.DecisionMetrics;
import io.github.vaquarkhan.aegis.engine.state.InMemoryStateStore;
import io.github.vaquarkhan.aegis.mcp.client.HttpMcpClient;
import io.github.vaquarkhan.aegis.mcp.client.McpContentSource;
import io.github.vaquarkhan.aegis.mcp.client.McpProxyToolSource;
import io.github.vaquarkhan.aegis.mcp.http.McpHttpRequest;
import io.github.vaquarkhan.aegis.mcp.http.McpHttpResponse;
import io.github.vaquarkhan.aegis.mcp.testing.TestMcpServer;
import io.github.vaquarkhan.aegis.mcp.testing.TestEngines;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The caller's W3C trace reaches the downstream MCP server through the gateway, for tools and for
 * content, via {@code _meta.traceparent} and the {@code traceparent} header.
 */
class TracePropagationTest {

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String INBOUND = "00-" + TRACE + "-00f067aa0ba902b7-01";

    private static McpHttpResponse post(McpServerHandler h, String method, Map<String, Object> params, String name,
                                        Map<String, List<String>> extraHeaders, boolean metaTrace) {
        Map<String, Object> p = new LinkedHashMap<>(params);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("io.modelcontextprotocol/protocolVersion", "2026-07-28");
        if (metaTrace) {
            meta.put("traceparent", INBOUND);
        }
        p.put("_meta", meta);
        Map<String, List<String>> headers = new LinkedHashMap<>(extraHeaders);
        headers.put("MCP-Protocol-Version", List.of("2026-07-28"));
        headers.put("Mcp-Method", List.of(method));
        if (name != null) {
            headers.put("Mcp-Name", List.of(name));
        }
        return h.handleHttp(new McpHttpRequest("POST", "", headers,
                Json.writeBytes(Map.of("jsonrpc", "2.0", "id", 1L, "method", method, "params", p))), ALICE);
    }

    private static void assertDownstreamTrace(TestMcpServer s, String method) {
        Map<String, Object> last = null;
        Map<String, String> lastHeaders = null;
        for (int i = s.received.size() - 1; i >= 0; i--) {
            if (method.equals(s.received.get(i).get("method"))) {
                last = s.received.get(i);
                lastHeaders = s.receivedHeaders.get(i);
                break;
            }
        }
        String inMeta = JsonValues.string(JsonValues.object(JsonValues.object(last, "params"), "_meta"), "traceparent");
        assertTrue(inMeta.startsWith("00-" + TRACE + "-"), inMeta);
        assertEquals(inMeta, lastHeaders.get("traceparent"));
    }

    @Test
    void toolCallTraceReachesTheExistingMcpServer() throws Exception {
        try (TestMcpServer s = new TestMcpServer(TestMcpServer.Mode.MODERN);
             HttpMcpClient client = new HttpMcpClient(s.uri(), HttpMcpClient.Era.AUTO, null, 1 << 20, Duration.ofSeconds(2))) {
            s.tool("list_topics", "List topics.", Map.of("type", "object"), a -> TestMcpServer.textResult("orders"));
            TestEngines.Built b = TestEngines.build(List.of(new McpProxyToolSource("k", client, Map::of, 2_000)),
                    Map.of("list_topics", ToolClass.READ));
            McpServerHandler h = new McpServerHandler(b.engine, ServerOptions.defaults(), new SessionRegistry(Clock.systemUTC(), 60_000, 10));
            McpHttpResponse viaMeta = post(h, "tools/call", Map.of("name", "list_topics", "arguments", Map.of()), "list_topics", Map.of(), true);
            assertTrue(viaMeta.bodyText().contains(TRACE), viaMeta.bodyText());
            assertDownstreamTrace(s, "tools/call");
            McpHttpResponse viaHeader = post(h, "tools/call", Map.of("name", "list_topics", "arguments", Map.of()), "list_topics",
                    Map.of("traceparent", List.of(INBOUND)), false);
            assertTrue(viaHeader.bodyText().contains(TRACE), "the HTTP header is the fallback carrier");
            assertDownstreamTrace(s, "tools/call");
            assertTrue(b.audit.records().stream().allMatch(r -> TRACE.equals(r.get("traceId"))));
        }
    }

    @Test
    void contentTraceReachesTheServerAndMetricsCountIt() throws Exception {
        try (TestMcpServer s = new TestMcpServer(TestMcpServer.Mode.MODERN);
             HttpMcpClient client = new HttpMcpClient(s.uri(), HttpMcpClient.Era.AUTO, null, 1 << 20, Duration.ofSeconds(2))) {
            s.resource("file:///data/a.csv", "a", "text/csv", "1");
            DecisionMetrics metrics = new DecisionMetrics();
            InMemoryAuditSink audit = new InMemoryAuditSink();
            GovernanceSettings gs = new GovernanceSettings("t", Set.of(), Set.of(), null, true, List.of(), RateLimiter.DISABLED,
                    InjectionScreen.defaults(), OutputGuard.defaults(),
                    new EgressGuard(List.of("127.0.0.1"), List.of("127.0.0.0/8"), Set.of("http"), null), List.of(), true, false,
                    List.of(metrics));
            ContentSettings cs = new ContentSettings("fs", "", true, List.of("file:///data/*"), Set.of(), false, false, List.of(),
                    Set.of(), false, false, 2_000);
            ContentGovernor g = new ContentGovernor(gs, List.of(new ContentGovernor.Binding(new McpContentSource("fs", client), cs, Map::of)),
                    new RulePolicy(List.of(new RulePolicy.Rule("r", RulePolicy.Effect.ALLOW, Set.of("*"), Set.of(), List.of(),
                            Set.of(ToolClass.READ), List.of()))),
                    new InMemoryStateStore(), audit, null, new CircuitBreakers(5, 1_000, Clock.systemUTC()),
                    new CatalogIntegrity(CatalogIntegrity.Mode.TOFU, Map.of(), null), InjectionScreen.defaults(),
                    new ContentGovernor.Options(false, true, true), Clock.systemUTC(), e -> { });
            g.load();
            TestEngines.Built b = TestEngines.build(List.of(), Map.of());
            McpServerHandler h = new McpServerHandler(b.engine, g, ServerOptions.defaults(), new SessionRegistry(Clock.systemUTC(), 60_000, 10));
            McpHttpResponse r = post(h, "resources/read", Map.of("uri", "file:///data/a.csv"), "file:///data/a.csv", Map.of(), true);
            assertEquals(200, r.status(), r.bodyText());
            assertDownstreamTrace(s, "resources/read");
            assertTrue(audit.records().stream().allMatch(x -> TRACE.equals(x.get("traceId"))));
            assertTrue(metrics.renderPrometheus().contains(
                    "aegis_decisions_total{kind=\"content\",method=\"resources/read\",source=\"fs\",outcome=\"ALLOWED\"} 1"),
                    metrics.renderPrometheus());
        }
    }
}
