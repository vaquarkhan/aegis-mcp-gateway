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
package io.github.vaquarkhan.aegis.source.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.vaquarkhan.aegis.config.BuildContext;
import io.github.vaquarkhan.aegis.config.ConfigException;
import io.github.vaquarkhan.aegis.config.ConfigNode;
import io.github.vaquarkhan.aegis.config.Gateway;
import io.github.vaquarkhan.aegis.config.GatewayFactory;
import io.github.vaquarkhan.aegis.engine.CallOutcome;
import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.json.JsonValues;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.model.DenyCodes;
import io.github.vaquarkhan.aegis.engine.model.ToolCall;
import io.github.vaquarkhan.aegis.engine.model.ToolClass;
import io.github.vaquarkhan.aegis.engine.model.ToolResult;
import io.github.vaquarkhan.aegis.engine.spi.ExecutionContext;
import io.github.vaquarkhan.aegis.engine.spi.ToolExecutor;
import io.github.vaquarkhan.aegis.engine.spi.ToolSource;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Card trust rules, request shape and reply handling, against a local HTTP listener that answers
 * with fixed A2A 1.0 JSON. The protocol itself is proven against the official a2a-sdk agent in
 * examples/aegis-validation (A2aAgentFrontDoorTest); this class covers the edge cases that a
 * well-behaved agent never produces.
 */
class A2aToolSourceTest {

    @TempDir
    Path dir;

    private HttpServer server;
    private String base;
    private volatile String card;
    private volatile int replyStatus = 200;
    private volatile String reply;
    private final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
    private final List<Map<String, List<String>>> headers = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/.well-known/agent-card.json", ex -> {
            byte[] b = card.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.createContext("/rpc", ex -> {
            requests.add(Json.parseObject(ex.getRequestBody().readAllBytes(), io.github.vaquarkhan.aegis.engine.json.JsonLimits.DEFAULT));
            headers.add(Map.copyOf(ex.getRequestHeaders()));
            byte[] b = reply.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(replyStatus, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        card = card(base + "/rpc", "JSONRPC", "1.0");
        reply = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"message\":{\"messageId\":\"r1\",\"role\":\"ROLE_AGENT\","
                + "\"parts\":[{\"text\":\"12 units of SKU-1\"},{\"data\":{\"sku\":\"SKU-1\",\"units\":12}}]}}}";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    static String card(String url, String binding, String version) {
        return "{\"name\":\"Inventory Agent\",\"description\":\"Answers stock questions.\",\"version\":\"1.0.0\","
                + "\"supportedInterfaces\":[{\"url\":\"" + url + "\",\"protocolBinding\":\"" + binding + "\",\"protocolVersion\":\"" + version + "\"}],"
                + "\"skills\":[{\"id\":\"lookup\",\"name\":\"Stock lookup\",\"description\":\"Units on hand for a SKU.\"},"
                + "{\"id\":\"reserve\",\"name\":\"Reserve stock\",\"description\":\"Holds units for an order.\"}]}";
    }

    private ToolSource source(String extraSettings) {
        Map<String, Object> s = Json.parseObject("{\"cardUrl\":\"" + base + "/.well-known/agent-card.json\"" + extraSettings + "}");
        return new A2aToolSourceFactory().create("inventory-agent", new ConfigNode("settings", s));
    }

    private static ExecutionContext ctx(Map<String, Object> args, boolean dryRun) {
        return new ExecutionContext("req-1", "inventory_agent", args, CallerIdentity.of("u", Set.of("g"), "i", "t"),
                Map.of("Authorization", "Bearer agent-service-token"), 5_000, dryRun);
    }

    @Test
    void cardBecomesOneToolWithSkillsAndEndpointInTheDescription() throws Exception {
        List<ToolSource.SourcedTool> tools = source("").discover();
        assertEquals(1, tools.size());
        ToolSource.SourcedTool t = tools.get(0);
        assertEquals("inventory_agent", t.descriptor().name());
        assertEquals("Inventory Agent", t.descriptor().title());
        String d = t.descriptor().description();
        assertTrue(d.contains("Answers stock questions.") && d.contains("[Stock lookup] Units on hand for a SKU.")
                && d.contains("[Reserve stock]") && d.contains("Calls go to " + base + "/rpc."), d);
        assertEquals(List.of("text"), t.descriptor().inputSchema().get("required"));
        assertEquals(Boolean.FALSE, t.descriptor().inputSchema().get("additionalProperties"));
        assertEquals(List.of(java.net.URI.create(base + "/rpc")), t.executor().egressTargets(Map.of()));
    }

    @Test
    void sendsOneA2aMessageAndReturnsTheReplyText() throws Exception {
        ToolExecutor exec = source("").discover().get(0).executor();
        ToolResult r = exec.execute(ctx(Map.of("text", "How many SKU-1?", "data", Map.of("sku", "SKU-1")), false));
        assertFalse(r.isError());
        String body = Json.write(r.body());
        assertTrue(body.contains("12 units of SKU-1") && body.contains("\\\"units\\\":12"), body);

        Map<String, Object> rpc = requests.get(0);
        assertEquals("2.0", rpc.get("jsonrpc"));
        assertEquals("SendMessage", rpc.get("method"));
        assertEquals("req-1", rpc.get("id"));
        Map<String, Object> msg = JsonValues.object(JsonValues.asObject(rpc.get("params")), "message");
        assertEquals("ROLE_USER", msg.get("role"));
        assertEquals(List.of(Map.of("text", "How many SKU-1?"), Map.of("data", Map.of("sku", "SKU-1"))), msg.get("parts"));
        Map<String, List<String>> h = headers.get(0);
        assertEquals(List.of("1.0"), h.get("A2a-version"));
        assertEquals(List.of("Bearer agent-service-token"), h.get("Authorization"));
        assertTrue(h.containsKey("Traceparent"));
    }

    @Test
    void taskStatesAreMappedAndFailuresAreErrors() throws Exception {
        ToolExecutor exec = source("").discover().get(0).executor();
        reply = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"task\":{\"id\":\"t1\",\"status\":{\"state\":\"TASK_STATE_COMPLETED\"},"
                + "\"artifacts\":[{\"artifactId\":\"a\",\"parts\":[{\"text\":\"reserved 3\"}]}],"
                + "\"history\":[{\"parts\":[{\"text\":\"PRIVATE HISTORY\"}]}]}}}";
        ToolResult done = exec.execute(ctx(Map.of("text", "reserve 3"), false));
        assertFalse(done.isError());
        assertTrue(Json.write(done.body()).contains("reserved 3"));
        assertFalse(Json.write(done.body()).contains("PRIVATE HISTORY"), "task history is not forwarded");

        reply = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"task\":{\"id\":\"t2\",\"status\":{\"state\":\"TASK_STATE_INPUT_REQUIRED\","
                + "\"message\":{\"parts\":[{\"text\":\"Which warehouse?\"}]}}}}}";
        ToolResult more = exec.execute(ctx(Map.of("text", "reserve"), false));
        assertTrue(more.isError());
        assertTrue(Json.write(more.body()).contains("does not continue a task") && Json.write(more.body()).contains("Which warehouse?"));

        reply = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"task\":{\"id\":\"t3\",\"status\":{\"state\":\"TASK_STATE_FAILED\"}}}}";
        assertTrue(exec.execute(ctx(Map.of("text", "x"), false)).isError());

        reply = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"error\":{\"code\":-32001,\"message\":\"Task not found\"}}";
        ToolResult err = exec.execute(ctx(Map.of("text", "x"), false));
        assertTrue(err.isError() && Json.write(err.body()).contains("-32001"));

        reply = "not json";
        assertTrue(exec.execute(ctx(Map.of("text", "x"), false)).isError());

        replyStatus = 503;
        reply = "{}";
        ToolExecutor.ExecutionException outage = assertThrows(ToolExecutor.ExecutionException.class,
                () -> exec.execute(ctx(Map.of("text", "x"), false)));
        assertTrue(outage.getMessage().contains("503"), "5xx counts against the breaker");
        replyStatus = 404;
        assertTrue(exec.execute(ctx(Map.of("text", "x"), false)).isError());
    }

    @Test
    void dryRunAndBadArgumentsSendNothing() throws Exception {
        ToolExecutor exec = source("").discover().get(0).executor();
        assertTrue(exec.execute(ctx(Map.of("text", "reserve 3"), true)).isError());
        assertTrue(exec.execute(ctx(Map.of("text", ""), false)).isError());
        assertTrue(exec.execute(ctx(Map.of("text", "x", "data", List.of(1)), false)).isError());
        assertTrue(exec.execute(ctx(Map.of(), false)).isError());
        assertTrue(requests.isEmpty(), "nothing reached the agent");
    }

    @Test
    void cardTrustRulesFailClosed() throws Exception {
        card = card("http://agents.elsewhere.example/rpc", "JSONRPC", "1.0");
        Exception cross = assertThrows(IOException.class, () -> source("").discover());
        assertTrue(cross.getMessage().contains("not the card's own origin"), cross.getMessage());
        assertThrows(IOException.class, () -> source(",\"endpointUrl\":\"" + base + "/rpc\"").discover(),
                "a pin that the card does not match is refused");
        assertEquals(1, source(",\"endpointUrl\":\"http://agents.elsewhere.example/rpc/\"").discover().size(),
                "an operator pin accepts exactly that endpoint");

        card = "{\"name\":\"Old\",\"url\":\"" + base + "/rpc\",\"protocolVersion\":\"0.3.0\",\"preferredTransport\":\"JSONRPC\"}";
        Exception old = assertThrows(IOException.class, () -> source("").discover());
        assertTrue(old.getMessage().contains("no A2A 1.x JSON-RPC interface") && old.getMessage().contains("0.3.0"), old.getMessage());
        card = card(base + "/rpc", "GRPC", "1.0");
        assertThrows(IOException.class, () -> source("").discover());
        card = card("ftp://127.0.0.1/rpc", "JSONRPC", "1.0");
        assertThrows(IOException.class, () -> source("").discover());
        card = "{\"supportedInterfaces\":[]}";
        assertThrows(IOException.class, () -> source("").discover());
        card = "[1,2]";
        assertThrows(IOException.class, () -> source("").discover());
    }

    @Test
    void settingsAreValidated() {
        A2aToolSourceFactory f = new A2aToolSourceFactory();
        assertEquals("a2a", f.type());
        for (String bad : new String[] {
                "{}", "{\"cardUrl\":\"/relative\"}", "{\"cardUrl\":\"ftp://h/x\"}", "{\"cardUrl\":\"http://u:p@h/x\"}",
                "{\"cardUrl\":\"http://h/x\",\"toolName\":\"bad name\"}", "{\"cardUrl\":\"http://h/x\",\"maxTextChars\":0}",
                "{\"cardUrl\":\"http://h/x\",\"maxResponseBytes\":10}", "{\"cardUrl\":\"http://h/x\",\"cardTimeoutMs\":1}",
                "{\"cardUrl\":\"http://h/x\",\"endpointUrl\":\"nope\"}", "{\"cardUrl\":\"http://h/x\",\"unknownKey\":1}"}) {
            assertThrows(ConfigException.class, () -> {
                ConfigNode n = new ConfigNode("settings", Json.parseObject(bad));
                f.create("s", n);
                n.finish();
            }, bad);
        }
    }

    @Test
    void throughTheGatewayTheAgentIsGovernedLikeAnyBackend() throws Exception {
        String cfg = """
                {"access":{"writeGroups":["buyers"]},
                 "audit":{"file":"audit.jsonl"},
                 "policy":{"rules":[{"id":"buyers","groups":["buyers"],"classes":["READ","MUTATE"]}]},
                 "egress":{%s"schemes":["http"]},
                 "sources":[{"id":"inventory-agent","type":"a2a",
                   "settings":{"cardUrl":"%s/.well-known/agent-card.json"},
                   "tools":{"inventory_agent":{"class":"MUTATE"}}}]}""";
        CallerIdentity buyer = CallerIdentity.of("bea", Set.of("buyers"), "idp", "jwt");
        try (Gateway g = GatewayFactory.build(Json.parseObject(cfg.formatted("\"allowHosts\":[\"127.0.0.1\"],\"allowCidrs\":[\"127.0.0.0/8\"],", base)), BuildContext.defaults(dir))) {
            assertEquals(ToolClass.MUTATE, g.engine().catalog().lookup("inventory_agent").orElseThrow().tool().toolClass());
            CallOutcome o = g.engine().call(buyer, new ToolCall("1", "inventory_agent", Map.of("text", "reserve 3"), Map.of(), ""));
            assertEquals(DenyCodes.APPROVAL_REQUIRED, o.decision().code(), "a MUTATE agent needs an approval like any write");
            assertTrue(requests.isEmpty());
        }
        try (Gateway g = GatewayFactory.build(Json.parseObject(cfg.formatted("", base)), BuildContext.defaults(dir))) {
            CallOutcome o = g.engine().call(buyer, new ToolCall("1", "inventory_agent", Map.of("text", "reserve 3"), Map.of(), ""));
            assertFalse(o.allowed());
            assertTrue(requests.isEmpty(), "nothing reached the agent: " + o.decision().code());
        }
    }

    @Test
    void renderHandlesOddReplies() {
        assertTrue(A2aToolSource.render(Map.of()).isError());
        assertTrue(A2aToolSource.render(Map.of("result", Map.of())).isError());
        assertTrue(A2aToolSource.render(Map.of("result", Map.of("task", Map.of("status", Map.of("state", "TASK_STATE_WORKING"))))).isError());
        ToolResult files = A2aToolSource.render(Map.of("result", Map.of("message", Map.of("parts",
                List.of(Map.of("url", "https://files.example/report.pdf"), Map.of("raw", "AAAA"), Map.of("other", 1), "junk")))));
        String text = Json.write(files.body());
        assertTrue(text.contains("[file at https://files.example/report.pdf]") && text.contains("[binary part omitted]"), text);
        assertNull(A2aToolSource.text(1));
    }
}
