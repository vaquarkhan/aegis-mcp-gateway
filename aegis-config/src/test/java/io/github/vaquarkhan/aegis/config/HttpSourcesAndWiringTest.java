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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.vaquarkhan.aegis.engine.CallOutcome;
import io.github.vaquarkhan.aegis.engine.audit.InMemoryAuditSink;
import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.model.DenyCodes;
import io.github.vaquarkhan.aegis.engine.model.ToolCall;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Declarative HTTP tools against a real local server, credential kinds, and the less common wiring paths. */
class HttpSourcesAndWiringTest {

    @TempDir
    Path dir;

    static final CallerIdentity ANA = CallerIdentity.of("ana", Set.of("users"), "idp", "t");
    static final Map<String, String> ENV = Map.of("SVC_TOKEN", "svc-token-123", "SVC_USER", "svc", "SVC_PASS", "p@ss",
            "SVC_KEY", "key-9", "AEGIS_SIGNING_KEY", "k".repeat(40));

    HttpServer backend;
    int port;

    @BeforeEach
    void start() throws Exception {
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            byte[] reqBody = ex.getRequestBody().readAllBytes();
            int status = 200;
            String type = "application/json";
            String body;
            switch (path) {
                case "/items" -> body = "{\"items\":[{\"id\":1}],\"secret\":\"" + "AKIA" + "ABCDEFGHIJKLMNOP\"}";
                case "/echo" -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("method", ex.getRequestMethod());
                    m.put("auth", String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
                    m.put("apiKey", String.valueOf(ex.getRequestHeaders().getFirst("X-Api-Key")));
                    m.put("traceparent", String.valueOf(ex.getRequestHeaders().getFirst("traceparent")));
                    m.put("query", String.valueOf(ex.getRequestURI().getRawQuery()));
                    m.put("body", new String(reqBody, StandardCharsets.UTF_8));
                    body = Json.write(m);
                }
                case "/text" -> {
                    type = "text/plain";
                    body = "plain words";
                }
                case "/missing" -> {
                    status = 404;
                    body = "{\"error\":\"no such thing\"}";
                }
                case "/boom" -> {
                    status = 503;
                    body = "down";
                }
                case "/badjson" -> body = "{not json";
                case "/status-ok" -> body = "{\"status\":200,\"value\":{\"id\":1}}";
                case "/status-denied" -> body = "{\"status\":403,\"error\":\"refused\",\"value\":{\"id\":1}}";
                case "/status-missing" -> body = "{\"value\":{\"id\":1}}";
                case "/status-text" -> {
                    type = "text/plain";
                    body = "not-json";
                }
                default -> {
                    status = 404;
                    body = "";
                }
            }
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", type);
            ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
            if (b.length > 0) {
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(b);
                }
            }
            ex.close();
        });
        backend.start();
        port = backend.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        backend.stop(0);
    }

    String config(String credentials, String extra) {
        return """
                {"policy": {"rules": [{"id": "all", "groups": ["users"]}]},
                 "access": {"writeGroups": ["users"]},
                 "approval": {"requiredFor": []},
                 "vrp": {"enabled": false},
                 "egress": {"allowCidrs": ["127.0.0.0/8"]},
                 "audit": {"file": "a.jsonl"}%s,
                 "sources": [{"id": "api", "type": "http", "baseUrl": "http://127.0.0.1:%d", "credentials": %s,
                   "tools": [
                     {"name": "items", "description": "Items.", "class": "READ", "request": {"path": "/items"}, "response": {"pointer": "/items"}},
                     {"name": "echo", "description": "Echo.", "class": "MUTATE",
                      "inputSchema": {"type": "object", "properties": {"count": {"type": "integer"}, "tag": {"type": "string"}}},
                      "request": {"method": "POST", "path": "/echo", "query": {"tag": "{tag}", "fixed": "1"},
                                  "body": {"n": "{count}", "all": "{*}", "literal": "x"}}},
                     {"name": "text", "description": "Text.", "class": "READ", "request": {"path": "/text"}},
                     {"name": "missing", "description": "Missing.", "class": "READ", "request": {"path": "/missing"}},
                     {"name": "boom", "description": "Boom.", "class": "READ", "request": {"path": "/boom"}},
                     {"name": "badjson", "description": "Bad.", "class": "READ", "request": {"path": "/badjson"}}]}]}
                """.formatted(extra, port, credentials);
    }

    Gateway build(String json) throws Exception {
        return GatewayFactory.build(Json.parseObject(json), BuildContext.defaults(dir).withEnv(ENV::get));
    }

    static String out(CallOutcome o) {
        return Json.write(o.toMcpResult());
    }

    @Test
    void httpToolsRenderTemplatesCredentialsAndMapResponses() throws Exception {
        try (Gateway g = build(config("{\"type\": \"basic\", \"usernameEnv\": \"SVC_USER\", \"passwordEnv\": \"SVC_PASS\"}", ""))) {
            CallOutcome items = g.engine().call(ANA, new ToolCall("1", "items", Map.of(), Map.of(), ""));
            assertTrue(items.allowed() && out(items).contains("\"id\":1") && !out(items).contains("AKIA"), out(items));

            CallOutcome echo = g.engine().call(ANA, new ToolCall("2", "echo", Map.of("count", 3L, "tag", "a b"),
                    Map.of("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"), ""));
            String e = out(echo);
            assertTrue(echo.allowed(), e);
            String basic = "Basic " + Base64.getEncoder().encodeToString("svc:p@ss".getBytes(StandardCharsets.UTF_8));
            assertTrue(e.contains(basic), "the gateway's own credential, never the caller's: " + e);
            assertTrue(e.contains("00-4bf92f3577b34da6a3ce929d0e0e4736-"), "trace context forwarded: " + e);
            assertTrue(e.contains("tag=a%20b&fixed=1"), "query in configuration order, percent-encoded: " + e);
            assertTrue(e.contains("\\\"n\\\":3") && e.contains("\\\"literal\\\":\\\"x\\\"") && e.contains("\\\"all\\\":{"), e);

            assertTrue(out(g.engine().call(ANA, new ToolCall("3", "text", Map.of(), Map.of(), ""))).contains("plain words"));
            CallOutcome missing = g.engine().call(ANA, new ToolCall("4", "missing", Map.of(), Map.of(), ""));
            assertTrue(missing.allowed() && out(missing).contains("HTTP 404") && out(missing).contains("\"isError\":true"), out(missing));
            assertEquals(DenyCodes.BACKEND_ERROR, g.engine().call(ANA, new ToolCall("5", "boom", Map.of(), Map.of(), "")).decision().code());
            assertTrue(out(g.engine().call(ANA, new ToolCall("6", "badjson", Map.of(), Map.of(), ""))).contains("invalid JSON"));
            assertNotNull(g.metrics(), "metrics are on by default");
        }
    }

    @Test
    void responseStatusCheckFailsClosedBeforeResponseProjection() throws Exception {
        String statusConfig = """
                {"policy":{"rules":[{"id":"all","groups":["users"]}]},"approval":{"requiredFor":[]},
                 "egress":{"allowCidrs":["127.0.0.0/8"]},"audit":{"file":"status.jsonl"},
                 "sources":[{"id":"api","type":"http","baseUrl":"http://127.0.0.1:%d","tools":[
                   {"name":"status_ok","description":"d","class":"READ","request":{"path":"/status-ok"},
                    "response":{"pointer":"/value","status":{"pointer":"/status","equals":200}}},
                   {"name":"status_denied","description":"d","class":"READ","request":{"path":"/status-denied"},
                    "response":{"pointer":"/value","status":{"pointer":"/status","equals":200}}},
                   {"name":"status_missing","description":"d","class":"READ","request":{"path":"/status-missing"},
                    "response":{"status":{"pointer":"/status","equals":200}}},
                   {"name":"status_text","description":"d","class":"READ","request":{"path":"/status-text"},
                    "response":{"status":{"pointer":"/status","equals":200}}}]}]}
                """.formatted(port);
        try (Gateway g = build(statusConfig)) {
            CallOutcome ok = g.engine().call(ANA, new ToolCall("1", "status_ok", Map.of(), Map.of(), ""));
            assertTrue(ok.allowed() && out(ok).contains("\"id\":1") && !out(ok).contains("\"status\":200"), out(ok));
            for (String tool : List.of("status_denied", "status_missing", "status_text")) {
                CallOutcome failed = g.engine().call(ANA, new ToolCall(tool, tool, Map.of(), Map.of(), ""));
                assertEquals(DenyCodes.BACKEND_ERROR, failed.decision().code(), tool + " -> " + out(failed));
                assertTrue(!out(failed).contains("refused"), "backend application error must not leak: " + out(failed));
            }
            // Application-level status mismatches are request rejections, not source outages. Repeating
            // them must not open a shared breaker and deny an unrelated healthy request.
            for (int i = 0; i < 2; i++) {
                assertEquals(DenyCodes.BACKEND_ERROR,
                        g.engine().call(ANA, new ToolCall("again-" + i, "status_denied", Map.of(), Map.of(), "")).decision().code());
            }
            CallOutcome healthy = g.engine().call(ANA, new ToolCall("healthy", "status_ok", Map.of(), Map.of(), ""));
            assertTrue(healthy.allowed(), "application status mismatches must not open the source breaker: " + out(healthy));
        }
    }

    @Test
    void invalidResponseStatusWiringFailsStartup() {
        String base = config("{\"type\": \"none\"}", "");
        String missingEquals = base.replace("\"response\": {\"pointer\": \"/items\"}",
                "\"response\": {\"status\": {\"pointer\": \"/status\"}}");
        String nonInteger = base.replace("\"response\": {\"pointer\": \"/items\"}",
                "\"response\": {\"status\": {\"pointer\": \"/status\", \"equals\": \"200\"}}");
        String malformedPointer = base.replace("\"response\": {\"pointer\": \"/items\"}",
                "\"response\": {\"status\": {\"pointer\": \"status\", \"equals\": 200}}");
        for (String broken : List.of(missingEquals, nonInteger, malformedPointer)) {
            assertThrows(Exception.class, () -> build(broken));
        }
    }

    @Test
    void bearerAndHeaderCredentials() throws Exception {
        try (Gateway g = build(config("{\"type\": \"bearer\", \"tokenEnv\": \"SVC_TOKEN\"}", ""))) {
            assertTrue(out(g.engine().call(ANA, new ToolCall("1", "echo", Map.of(), Map.of(), ""))).contains("Bearer svc-token-123"));
        }
        try (Gateway g = build(config("{\"type\": \"header\", \"header\": \"X-Api-Key\", \"valueEnv\": \"SVC_KEY\"}", ""))) {
            assertTrue(out(g.engine().call(ANA, new ToolCall("1", "echo", Map.of(), Map.of(), ""))).contains("key-9"));
        }
    }

    @Test
    void extraAuditSinkIsTeedAndFailsClosed() throws Exception {
        InMemoryAuditSink extra = new InMemoryAuditSink();
        BuildContext ctx = new BuildContext(ENV::get, dir, null, null, null, extra, null, null);
        try (Gateway g = GatewayFactory.build(Json.parseObject(config("{\"type\": \"none\"}", "")), ctx)) {
            assertTrue(g.engine().call(ANA, new ToolCall("1", "items", Map.of(), Map.of(), "")).allowed());
            assertEquals(2, extra.records().size(), "intent and result reach the second sink too");
            extra.setFailing(true);
            assertEquals(DenyCodes.AUDIT_UNAVAILABLE, g.engine().call(ANA, new ToolCall("2", "items", Map.of(), Map.of(), "")).decision().code());
        }
    }

    @Test
    void optionalWiring() throws Exception {
        String extra = ", \"metrics\": {\"enabled\": false}, \"catalog\": {\"mode\": \"REQUIRE_PINS\", \"refreshSeconds\": 3600}";
        try (Gateway g = build(config("{\"type\": \"none\"}", extra))) {
            assertNull(g.metrics());
            assertEquals(DenyCodes.CATALOG_INTEGRITY, g.engine().call(ANA, new ToolCall("1", "items", Map.of(), Map.of(), "")).decision().code(),
                    "REQUIRE_PINS without pins quarantines every tool");
        }
        Path file = dir.resolve("aegis.json");
        Files.writeString(file, config("{\"type\": \"none\"}", ""));
        try (Gateway g = GatewayFactory.build(file, BuildContext.defaults(dir).withEnv(ENV::get))) {
            assertTrue(g.engine().call(ANA, new ToolCall("1", "items", Map.of(), Map.of(), "")).allowed());
        }
    }

    @Test
    void invalidWiringFailsStartup() {
        String base = config("{\"type\": \"none\"}", "");
        for (String[] swap : new String[][] {
                {"\"type\": \"http\"", "\"type\": \"no-such-plugin\""},
                {"\"baseUrl\": \"http://127.0.0.1:" + port + "\"", "\"baseUrl\": \"ftp://127.0.0.1:" + port + "\""},
                {"\"baseUrl\": \"http://127.0.0.1:" + port + "\"", "\"baseUrl\": \"http://user:pw@127.0.0.1:" + port + "\""},
                {"\"id\": \"api\"", "\"id\": \"Bad Id\""},
                {"\"audit\": {\"file\": \"a.jsonl\"}", "\"audit\": {\"file\": \"a.jsonl\"}, \"hooks\": [{\"type\": \"no-such-hook\"}]"},
                {"\"audit\": {\"file\": \"a.jsonl\"}", "\"audit\": {\"file\": \"a.jsonl\"}, \"policy2\": {}"},
                {"\"credentials\": {\"type\": \"none\"}", "\"credentials\": {\"type\": \"bearer\", \"tokenEnv\": \"UNSET\"}"}}) {
            assertTrue(base.contains(swap[0]), swap[0]);
            String broken = base.replace(swap[0], swap[1]);
            assertThrows(Exception.class, () -> build(broken), swap[1]).getMessage();
        }
        String mcpStdio = """
                {"policy": {"rules": [{"id": "all", "groups": ["users"]}]}, "audit": {"file": "b.jsonl"},
                 "sources": [{"id": "m", "type": "mcp", "transport": "stdio"}]}""";
        assertTrue(assertThrows(ConfigException.class, () -> build(mcpStdio)).getMessage().contains("command"));
        String badTransport = mcpStdio.replace("\"stdio\"", "\"carrier-pigeon\"");
        assertTrue(assertThrows(ConfigException.class, () -> build(badTransport)).getMessage().contains("transport"));
        String opa = """
                {"policy": {"opa": {"url": "http://127.0.0.1:1/v1/data/aegis/allow", "tokenEnv": "UNSET"}}, "audit": {"file": "c.jsonl"},
                 "sources": [{"id": "s", "type": "http", "baseUrl": "http://127.0.0.1:1", "tools": [{"name": "t", "description": "d", "class": "READ"}]}]}""";
        assertTrue(assertThrows(ConfigException.class, () -> build(opa)).getMessage().contains("UNSET"));
        assertTrue(assertThrows(ConfigException.class, () -> build(base.replace("\"sources\": [", "\"sources\": [], \"x\": [")))
                .getMessage().contains("source"));
        assertThrows(Exception.class, () -> build(List.of(base).get(0).replace("\"class\": \"READ\", \"request\": {\"path\": \"/items\"}",
                "\"class\": \"SOMETIMES\", \"request\": {\"path\": \"/items\"}")));
    }

    /** Values that used to fall back silently, or fail without a key path, now stop startup with one. */
    @Test
    void enumeratedValuesAndPolicyEndpointsAreValidatedWithTheirKeyPath() throws Exception {
        String base = config("{\"type\": \"none\"}", "");
        String[][] cases = {
                // a typo must not quietly mean EXPOSE
                {"\"id\": \"api\"", "\"id\": \"api\", \"unlistedTools\": \"HIDDEN\"", "unlistedTools must be EXPOSE or HIDE"},
                {"\"id\": \"api\"", "\"id\": \"api\", \"defaultClass\": \"WRITE\"", "defaultClass must be READ, MUTATE or DESTRUCTIVE"},
                {"\"approval\": {\"requiredFor\": []}", "\"approval\": {\"requiredFor\": [\"READ\"]}", "requiredFor must not include READ"},
                {"{\"id\": \"all\", \"groups\": [\"users\"]}", "{\"id\": \"all\", \"groups\": [\"users\"], \"effect\": \"MAYBE\"}",
                        "rules[0].effect must be one of [ALLOW, DENY]"},
                {"\"credentials\": {\"type\": \"none\"}", "\"credentials\": {\"type\": \"kerberos\"}", "credentials.type must be one of"},
                {"\"audit\": {\"file\": \"a.jsonl\"}", "\"audit\": {\"file\": \"a.jsonl\"}, \"catalog\": {\"mode\": \"PINNED\"}",
                        "catalog.mode must be one of [TOFU, REQUIRE_PINS]"},
                {"\"audit\": {\"file\": \"a.jsonl\"}", "\"audit\": {\"file\": \"a.jsonl\"}, \"screening\": {\"content\": \"WARN\"}",
                        "screening.content must be ENFORCE, AUDIT or OFF"},
                // the OPA call leaves the host on every decision, so it passes the egress allow list
                {"\"rules\": [{\"id\": \"all\", \"groups\": [\"users\"]}]",
                        "\"rules\": [{\"id\": \"all\", \"groups\": [\"users\"]}], \"opa\": {\"url\": \"https://opa.not-allowed.test/v1/data/aegis/allow\"}",
                        "policy.opa.url is not allowed by config.egress: host not on egress allow list"},
                {"\"rules\": [{\"id\": \"all\", \"groups\": [\"users\"]}]",
                        "\"rules\": [{\"id\": \"all\", \"groups\": [\"users\"]}], \"opa\": {\"url\": \"file:///etc/opa\"}",
                        "policy.opa.url must be an absolute http(s) URL"}};
        for (String[] c : cases) {
            assertTrue(base.contains(c[0]), c[0]);
            String broken = base.replace(c[0], c[1]);
            ConfigException e = assertThrows(ConfigException.class, () -> build(broken), c[1]);
            assertTrue(e.getMessage().contains(c[2]), c[2] + " expected, got: " + e.getMessage());
        }
        // A policy endpoint the operator did allow is accepted, and the accepted names still parse.
        String allowedOpa = base.replace("\"rules\": [{\"id\": \"all\", \"groups\": [\"users\"]}]",
                        "\"rules\": [{\"id\": \"all\", \"groups\": [\"users\"]}], \"opa\": {\"url\": \"http://127.0.0.1:"
                                + port + "/v1/data/aegis/allow\"}")
                .replace("\"id\": \"api\"", "\"id\": \"api\", \"unlistedTools\": \"hide\"");
        try (Gateway g = build(allowedOpa)) {
            assertTrue(g.engine() != null);
        }
    }
}
