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
package io.github.vaquarkhan.aegis.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.vaquarkhan.aegis.config.BuildContext;
import io.github.vaquarkhan.aegis.config.Gateway;
import io.github.vaquarkhan.aegis.config.GatewayFactory;
import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.json.JsonValues;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.mcp.http.McpHttpRequest;
import io.github.vaquarkhan.aegis.security.identity.JwtVerifier;
import io.github.vaquarkhan.aegis.security.perimeter.OwnServerSecuritySettings;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** End to end over real HTTP: the independent front door with its own identity. */
class GatewayHostTest {

    @TempDir
    Path dir;

    static final String SECRET = "gateway-host-test-hmac-secret-0123456789";
    static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    static final Map<String, String> ENV = Map.of("AEGIS_SIGNING_KEY", "s".repeat(48), "JWT_SECRET", SECRET);

    static String hs256(Map<String, Object> claims) throws Exception {
        return hs256(Map.of("alg", "HS256", "typ", "JWT"), claims);
    }

    static String hs256(Map<String, Object> header, Map<String, Object> claims) throws Exception {
        String input = B64.encodeToString(Json.writeBytes(header)) + "." + B64.encodeToString(Json.writeBytes(claims));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return input + "." + B64.encodeToString(mac.doFinal(input.getBytes(StandardCharsets.US_ASCII)));
    }

    static Map<String, Object> claims(String sub, List<String> groups) {
        long now = System.currentTimeMillis() / 1000;
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("iss", "https://idp.test");
        c.put("aud", "aegis");
        c.put("sub", sub);
        c.put("groups", groups);
        c.put("iat", now);
        c.put("exp", now + 300);
        return c;
    }

    /** REST backend (Flink-like) recording calls. */
    static HttpServer rest(List<String> seen) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            seen.add(ex.getRequestMethod() + " " + ex.getRequestURI() + " auth=" + ex.getRequestHeaders().getFirst("Authorization"));
            byte[] b = "{\"jobs\":[{\"id\":\"j1\",\"status\":\"RUNNING\"}]}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        });
        s.start();
        return s;
    }

    private Path config(int restPort) throws Exception {
        String json = """
                {
                  "gateway": {"name": "gateway-host-test"},
                  "access": {"writeGroups": ["operators"], "approverGroups": ["approvers"], "auditVerifierGroups": ["audit-verifiers"]},
                  "approval": {"keyEnv": "AEGIS_SIGNING_KEY"},
                  "policy": {"rules": [
                    {"id": "read", "groups": ["analysts"], "classes": ["READ"]},
                    {"id": "write", "groups": ["operators"], "classes": ["MUTATE", "DESTRUCTIVE"]}]},
                  "egress": {"allowCidrs": ["127.0.0.0/8"]},
                  "audit": {"file": "audit.jsonl"},
                  "sources": [{
                    "id": "flink", "type": "http", "baseUrl": "http://127.0.0.1:%d",
                    "tools": [
                      {"name": "flink_list_jobs", "description": "List jobs.", "class": "READ",
                       "request": {"method": "GET", "path": "/jobs/overview"}},
                      {"name": "flink_cancel_job", "description": "Cancel a job.", "class": "DESTRUCTIVE",
                       "inputSchema": {"type": "object", "properties": {"jobId": {"type": "string"}}, "required": ["jobId"]},
                       "request": {"method": "PATCH", "path": "/jobs/{jobId}", "query": {"mode": "cancel"}},
                       "resources": [{"type": "flink:job", "pointer": "/jobId"}]}
                    ]}],
                  "host": {
                    "listen": {"host": "127.0.0.1", "port": 0},
                    "identity": {"type": "jwt", "jwt": {"issuer": "https://idp.test", "audience": "aegis",
                      "algorithms": ["HS256"], "hmacSecretEnv": "JWT_SECRET"}},
                    "stdio": {"actor": "local-dev", "groups": ["analysts"]}
                  }
                }""".formatted(restPort);
        Path p = dir.resolve("aegis.json");
        Files.writeString(p, json);
        return p;
    }

    private static HttpResponse<String> post(HttpClient http, int port, String path, String token, Map<String, Object> body,
                                             Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(Json.writeBytes(body)));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        headers.forEach(b::header);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, Object> modernCall(String tool, Map<String, Object> args, Map<String, Object> extraMeta) {
        Map<String, Object> meta = new LinkedHashMap<>(extraMeta);
        meta.put("io.modelcontextprotocol/protocolVersion", "2026-07-28");
        meta.put("io.modelcontextprotocol/clientCapabilities", Map.of());
        return Map.of("jsonrpc", "2.0", "id", 1L, "method", "tools/call",
                "params", Map.of("name", tool, "arguments", args, "_meta", meta));
    }

    private static Map<String, String> modernHeaders(String tool) {
        return Map.of("MCP-Protocol-Version", "2026-07-28", "Mcp-Method", "tools/call", "Mcp-Name", tool);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> decision(String responseBody) {
        Map<String, Object> result = JsonValues.object(Json.parseObject(responseBody), "result");
        return (Map<String, Object>) JsonValues.object(result, "_meta").get("io.github.vaquarkhan.aegis/decision");
    }

    @Test
    void jwtAuthenticatedValidateApprovePromoteOverHttp() throws Exception {
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        HttpServer backend = rest(seen);
        Path cfg = config(backend.getAddress().getPort());
        Gateway gw = GatewayFactory.build(cfg, BuildContext.defaults(dir).withEnv(ENV::get));
        OwnServerSecuritySettings st = OwnServerSecuritySettings.parse(gw.hostSection("host"), dir, ENV::get, Clock.systemUTC());
        try (AegisServer server = new AegisServer(gw, st)) {
            server.start();
            int port = server.port();
            HttpClient http = HttpClient.newHttpClient();
            String analyst = hs256(claims("alice", List.of("analysts")));
            String operator = hs256(claims("olga", List.of("analysts", "operators")));
            String approver = hs256(claims("bob", List.of("approvers")));

            // Unauthenticated and forged tokens get 401 before any MCP processing.
            assertEquals(401, post(http, port, "/mcp", null, modernCall("flink_list_jobs", Map.of(), Map.of()), modernHeaders("flink_list_jobs")).statusCode());
            String forged = analyst.substring(0, analyst.lastIndexOf('.') + 1) + "AAAA";
            assertEquals(401, post(http, port, "/mcp", forged, modernCall("flink_list_jobs", Map.of(), Map.of()), modernHeaders("flink_list_jobs")).statusCode());
            String none = B64.encodeToString(Json.writeBytes(Map.of("alg", "none"))) + "."
                    + B64.encodeToString(Json.writeBytes(claims("alice", List.of("analysts")))) + ".";
            assertEquals(401, post(http, port, "/mcp", none, modernCall("flink_list_jobs", Map.of(), Map.of()), modernHeaders("flink_list_jobs")).statusCode());

            HttpResponse<String> list = post(http, port, "/mcp", analyst, modernCall("flink_list_jobs", Map.of(), Map.of()), modernHeaders("flink_list_jobs"));
            assertEquals(200, list.statusCode(), list.body());
            assertTrue(list.body().contains("RUNNING"));
            assertEquals("GET /jobs/overview auth=null", seen.get(0), "caller's JWT is never forwarded to the backend");

            // Validate (dry run) -> approve via admin API -> promote with token + receipt.
            HttpResponse<String> dry = post(http, port, "/mcp", operator,
                    modernCall("flink_cancel_job", Map.of("jobId", "j1"), Map.of("io.github.vaquarkhan.aegis/dryRun", true)),
                    modernHeaders("flink_cancel_job"));
            Map<String, Object> d = decision(dry.body());
            String receipt = (String) d.get("dryRunReceipt");
            String rid = (String) d.get("approvalRequestId");
            assertNotNull(receipt, dry.body());
            assertEquals(1, seen.size(), "GATEWAY dry run never reaches the backend");

            HttpResponse<String> selfApprove = post(http, port, "/aegis/v1/approvals/" + rid + "/approve", operator, Map.of(), Map.of());
            assertEquals(403, selfApprove.statusCode());
            HttpResponse<String> approve = post(http, port, "/aegis/v1/approvals/" + rid + "/approve", approver, Map.of(), Map.of());
            assertEquals(200, approve.statusCode(), approve.body());
            HttpResponse<String> view = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/aegis/v1/approvals/" + rid))
                    .header("Authorization", "Bearer " + operator).GET().build(), HttpResponse.BodyHandlers.ofString());
            String token = (String) Json.parseObject(view.body()).get("token");
            assertNotNull(token, view.body());

            HttpResponse<String> promote = post(http, port, "/mcp", operator,
                    modernCall("flink_cancel_job", Map.of("jobId", "j1"), Map.of(
                            "io.github.vaquarkhan.aegis/approvalToken", token,
                            "io.github.vaquarkhan.aegis/dryRunReceipt", receipt)),
                    modernHeaders("flink_cancel_job"));
            assertEquals("ALLOWED", decision(promote.body()).get("outcome"), promote.body());
            assertEquals("PATCH /jobs/j1?mode=cancel auth=null", seen.get(1));

            HttpResponse<String> health = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/readyz")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode());
        } finally {
            backend.stop(0);
        }
    }

    @Test
    void auditVerificationEndpointUsesDedicatedGroupAndFixedJournal() throws Exception {
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        HttpServer backend = rest(seen);
        Path cfg = config(backend.getAddress().getPort());
        Gateway gw = GatewayFactory.build(cfg, BuildContext.defaults(dir).withEnv(ENV::get));
        OwnServerSecuritySettings st = OwnServerSecuritySettings.parse(gw.hostSection("host"), dir, ENV::get, Clock.systemUTC());
        try (AegisServer server = new AegisServer(gw, st)) {
            server.start();
            int port = server.port();
            HttpClient http = HttpClient.newHttpClient();
            URI endpoint = URI.create("http://127.0.0.1:" + port + "/aegis/v1/audit/verify");
            String approver = hs256(claims("bob", List.of("approvers")));
            String verifier = hs256(claims("vera", List.of("audit-verifiers")));

            assertEquals(401, http.send(HttpRequest.newBuilder(endpoint).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            HttpResponse<String> denied = http.send(HttpRequest.newBuilder(endpoint).header("Authorization", "Bearer " + approver)
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, denied.statusCode(), denied.body());
            HttpResponse<String> ok = http.send(HttpRequest.newBuilder(endpoint).header("Authorization", "Bearer " + verifier)
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ok.statusCode(), ok.body());
            assertEquals(Map.of("verified", true), Json.parseObject(ok.body()));
            assertEquals(404, http.send(HttpRequest.newBuilder(endpoint.resolve("/aegis/v1/audit/verify/other"))
                    .header("Authorization", "Bearer " + verifier).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        } finally {
            backend.stop(0);
        }
    }

    @Test
    void ownHostRouteGroupsAndCorsRunBeforeMcpDispatch() throws Exception {
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        HttpServer backend = rest(seen);
        Path cfg = config(backend.getAddress().getPort());
        Map<String, Object> raw = new LinkedHashMap<>(Json.parseObject(Files.readString(cfg)));
        Map<String, Object> host = new LinkedHashMap<>(JsonValues.asObject(raw.get("host")));
        host.put("routes", Map.of("mcpGroups", List.of("mcp-users"), "adminGroups", List.of("mcp-admins")));
        host.put("cors", Map.of(
                "allowedOrigins", List.of("https://console.test"),
                "allowedMethods", List.of("POST", "DELETE"),
                "allowedHeaders", List.of("Authorization", "Content-Type", "MCP-Protocol-Version", "Mcp-Method", "Mcp-Name"),
                "exposedHeaders", List.of("Mcp-Session-Id"),
                "maxAgeSeconds", 300L));
        raw.put("host", host);
        raw.put("server", Map.of("allowedOrigins", List.of("https://console.test")));
        Files.writeString(cfg, Json.write(raw));
        Gateway gw = GatewayFactory.build(cfg, BuildContext.defaults(dir).withEnv(ENV::get));
        OwnServerSecuritySettings st = OwnServerSecuritySettings.parse(gw.hostSection("host"), dir, ENV::get, Clock.systemUTC());
        try (AegisServer server = new AegisServer(gw, st)) {
            server.start();
            int port = server.port();
            HttpClient http = HttpClient.newHttpClient();
            String analyst = hs256(claims("alice", List.of("analysts")));
            String mcpUser = hs256(claims("maria", List.of("analysts", "mcp-users")));
            URI mcp = URI.create("http://127.0.0.1:" + port + "/mcp");

            HttpResponse<String> preflight = http.send(HttpRequest.newBuilder(mcp)
                    .header("Origin", "https://console.test")
                    .header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", "Authorization, Content-Type, Mcp-Method")
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(204, preflight.statusCode(), preflight.body());
            assertEquals("https://console.test", preflight.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
            assertTrue(preflight.headers().firstValue("Access-Control-Allow-Methods").orElse("").contains("POST"));

            HttpResponse<String> rejectedPreflight = http.send(HttpRequest.newBuilder(mcp)
                    .header("Origin", "https://attacker.test")
                    .header("Access-Control-Request-Method", "POST")
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, rejectedPreflight.statusCode());
            assertFalse(rejectedPreflight.headers().firstValue("Access-Control-Allow-Origin").isPresent());

            HttpResponse<String> routeDenied = post(http, port, "/mcp", analyst,
                    modernCall("flink_list_jobs", Map.of(), Map.of()), modernHeaders("flink_list_jobs"));
            assertEquals(403, routeDenied.statusCode());
            assertTrue(seen.isEmpty(), "route admission rejects before MCP dispatch and backend execution");

            Map<String, String> headers = new LinkedHashMap<>(modernHeaders("flink_list_jobs"));
            headers.put("Origin", "https://console.test");
            HttpResponse<String> allowed = post(http, port, "/mcp", mcpUser,
                    modernCall("flink_list_jobs", Map.of(), Map.of()), headers);
            assertEquals(200, allowed.statusCode(), allowed.body());
            assertEquals("https://console.test", allowed.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
            assertEquals(1, seen.size());

            HttpResponse<String> adminDenied = post(http, port, "/aegis/v1/approvals", mcpUser, Map.of(), Map.of());
            assertEquals(403, adminDenied.statusCode(), adminDenied.body());
        } finally {
            backend.stop(0);
        }
    }

    /** The server sends 413 before consuming an oversized upload; some JDK clients report their own aborted stream. */
    private static void assertLargeBodyRejected(HttpClient client, HttpRequest request) throws Exception {
        try {
            assertEquals(413, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        } catch (IOException expected) {
            String message = String.valueOf(expected.getMessage()).toLowerCase(Locale.ROOT);
            assertTrue(message.contains("content-length")
                            || message.contains("body")
                            || message.contains("header parser")
                            || message.contains("no bytes"),
                    "unexpected client error: " + expected);
        }
    }

    @Test
    void transportBindingRejectsUnexpectedPathsAndMethodsAndProtectsMetrics() throws Exception {
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        HttpServer backend = rest(seen);
        Path cfg = config(backend.getAddress().getPort());
        Map<String, Object> raw = new LinkedHashMap<>(Json.parseObject(Files.readString(cfg)));
        Map<String, Object> host = new LinkedHashMap<>(JsonValues.asObject(raw.get("host")));
        host.put("metrics", Map.of("path", "/metrics", "allowedGroups", List.of("metrics-users")));
        raw.put("host", host);
        Files.writeString(cfg, Json.write(raw));
        Gateway gw = GatewayFactory.build(cfg, BuildContext.defaults(dir).withEnv(ENV::get));
        OwnServerSecuritySettings st = OwnServerSecuritySettings.parse(gw.hostSection("host"), dir, ENV::get, Clock.systemUTC());
        try (AegisServer server = new AegisServer(gw, st)) {
            server.start();
            HttpClient http = HttpClient.newHttpClient();
            int port = server.port();
            String analyst = hs256(claims("alice", List.of("analysts")));
            URI base = URI.create("http://127.0.0.1:" + port);

            assertEquals(404, http.send(HttpRequest.newBuilder(base.resolve("/mcp/not-a-route")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(405, http.send(HttpRequest.newBuilder(base.resolve("/mcp"))
                    .header("Authorization", "Bearer " + analyst)
                    .PUT(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(405, http.send(HttpRequest.newBuilder(base.resolve("/mcp"))
                    .header("Authorization", "Bearer " + analyst)
                    .DELETE().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertLargeBodyRejected(http, HttpRequest.newBuilder(base.resolve("/mcp"))
                    .header("Authorization", "Bearer " + analyst)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("x".repeat((1 << 20) + 1))).build());
            assertLargeBodyRejected(http, HttpRequest.newBuilder(base.resolve("/mcp"))
                    .header("Authorization", "Bearer " + analyst)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(
                            "x".repeat((1 << 20) + 1).getBytes(StandardCharsets.UTF_8)))).build());
            assertEquals(403, http.send(HttpRequest.newBuilder(base.resolve("/metrics"))
                    .header("Authorization", "Bearer " + analyst).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(200, http.send(HttpRequest.newBuilder(base.resolve("/healthz")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            assertTrue(seen.isEmpty(), "transport rejection and metrics access never reach a backend");
        } finally {
            backend.stop(0);
        }
    }

    @Test
    void corsPreflightCannotAdvertiseAnOriginTheMcpHandlerRejects() throws Exception {
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        HttpServer backend = rest(seen);
        Path cfg = config(backend.getAddress().getPort());
        Map<String, Object> raw = new LinkedHashMap<>(Json.parseObject(Files.readString(cfg)));
        Map<String, Object> host = new LinkedHashMap<>(JsonValues.asObject(raw.get("host")));
        host.put("cors", Map.of("allowedOrigins", List.of("https://console.test")));
        raw.put("host", host);
        Files.writeString(cfg, Json.write(raw));
        Gateway gw = GatewayFactory.build(cfg, BuildContext.defaults(dir).withEnv(ENV::get));
        OwnServerSecuritySettings st = OwnServerSecuritySettings.parse(gw.hostSection("host"), dir, ENV::get, Clock.systemUTC());
        try {
            IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> new AegisServer(gw, st));
            assertTrue(e.getMessage().contains("subset"), e.getMessage());
        } finally {
            gw.close();
            backend.stop(0);
        }
    }

    @Test
    void derbyLocalStateProviderIsDiscoveredByTheOwnServer() throws Exception {
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        HttpServer backend = rest(seen);
        Path cfg = config(backend.getAddress().getPort());
        Map<String, Object> raw = new LinkedHashMap<>(Json.parseObject(Files.readString(cfg)));
        raw.put("state", Map.of("type", "derby-local", "settings", Map.of("path", "state/aegis")));
        Files.writeString(cfg, Json.write(raw));
        try (Gateway first = GatewayFactory.build(cfg, BuildContext.defaults(dir).withEnv(ENV::get))) {
            OwnServerSecuritySettings st = OwnServerSecuritySettings.parse(first.hostSection("host"), dir, ENV::get, Clock.systemUTC());
            try (AegisServer server = new AegisServer(first, st)) {
                server.start();
                String analyst = hs256(claims("alice", List.of("analysts")));
                assertEquals(200, post(HttpClient.newHttpClient(), server.port(), "/mcp", analyst,
                        modernCall("flink_list_jobs", Map.of(), Map.of()), modernHeaders("flink_list_jobs")).statusCode());
            }
        }
        assertTrue(Files.isDirectory(dir.resolve("state/aegis")), "embedded Derby state is durable on disk");
        try (Gateway second = GatewayFactory.build(cfg, BuildContext.defaults(dir).withEnv(ENV::get))) {
            assertNotNull(second.engine(), "the Derby database shut down cleanly and reopens for one owner");
        } finally {
            backend.stop(0);
        }
    }

    @Test
    void stdioHostServesTheConfiguredLocalIdentity() throws Exception {
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        HttpServer backend = rest(seen);
        try (Gateway gw = GatewayFactory.build(config(backend.getAddress().getPort()), BuildContext.defaults(dir).withEnv(ENV::get))) {
            OwnServerSecuritySettings st = OwnServerSecuritySettings.parse(gw.hostSection("host"), dir, ENV::get, Clock.systemUTC());
            String in = Json.write(Map.of("jsonrpc", "2.0", "id", 1L, "method", "initialize", "params", Map.of("protocolVersion", "2025-11-25"))) + "\n"
                    + Json.write(Map.of("jsonrpc", "2.0", "method", "notifications/initialized")) + "\n"
                    + Json.write(Map.of("jsonrpc", "2.0", "id", 2L, "method", "tools/list")) + "\n"
                    + Json.write(Map.of("jsonrpc", "2.0", "id", 3L, "method", "tools/call", "params", Map.of("name", "flink_list_jobs"))) + "\n";
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            StdioHost.run(gw, st.stdioIdentity(), new ByteArrayInputStream(in.getBytes(StandardCharsets.UTF_8)), out);
            String[] lines = out.toString(StandardCharsets.UTF_8).trim().split("\n");
            assertEquals(3, lines.length, out.toString(StandardCharsets.UTF_8));
            assertTrue(lines[1].contains("flink_list_jobs") && !lines[1].contains("flink_cancel_job"), "stdio identity is read-only");
            assertTrue(lines[2].contains("RUNNING"));
            ByteArrayOutputStream oversized = new ByteArrayOutputStream();
            StdioHost.run(gw, st.stdioIdentity(), new ByteArrayInputStream(("x".repeat((1 << 20) + 1) + "\n")
                    .getBytes(StandardCharsets.UTF_8)), oversized);
            assertTrue(oversized.toString(StandardCharsets.UTF_8).contains("message too large"));
        } finally {
            backend.stop(0);
        }
    }

    @Test
    void rs256ViaJwksWithKeyRotationAndDelegationChain() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair k1 = g.generateKeyPair();
        KeyPair k2 = g.generateKeyPair();
        AtomicReference<String> jwks = new AtomicReference<>(jwks("k1", (RSAPublicKey) k1.getPublic()));
        HttpServer idp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        idp.createContext("/jwks", ex -> {
            byte[] b = jwks.get().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        });
        idp.start();
        try {
            JwtVerifier v = new JwtVerifier(new JwtVerifier.Settings("https://idp.test", "aegis", Set.of("RS256"), null,
                    URI.create("http://127.0.0.1:" + idp.getAddress().getPort() + "/jwks"), "groups", "sub", 30, 3600, 0), Clock.systemUTC());
            Map<String, Object> c = claims("svc-user", List.of("analysts"));
            c.put("act", Map.of("sub", "agent-7"));
            CallerIdentity id = v.verify(rs256("k1", k1, c));
            assertEquals("agent-7", id.actor());
            assertEquals("svc-user", id.effectiveSubject());
            assertEquals(List.of("agent-7"), id.actorChain());

            jwks.set(jwks("k2", (RSAPublicKey) k2.getPublic())); // rotation
            assertEquals("svc-user", v.verify(rs256("k2", k2, claims("svc-user", List.of()))).effectiveSubject());

            // An HS256 token signed with the RSA public key bytes (key confusion) is rejected: HS256 not allowed.
            McpHttpRequest confused = new McpHttpRequest("POST", "", Map.of("Authorization",
                    List.of("Bearer " + hs256(claims("x", List.of("approvers"))))), null);
            assertNull(v.resolve(confused));
            Map<String, Object> wrongAud = claims("svc-user", List.of());
            wrongAud.put("aud", "other-service");
            assertNull(v.resolve(new McpHttpRequest("POST", "", Map.of("Authorization", List.of("Bearer " + rs256("k2", k2, wrongAud))), null)));
        } finally {
            idp.stop(0);
        }
    }

    static String jwks(String kid, RSAPublicKey pk) {
        return Json.write(Map.of("keys", List.of(Map.of("kty", "RSA", "kid", kid, "use", "sig",
                "n", B64.encodeToString(unsigned(pk.getModulus())), "e", B64.encodeToString(unsigned(pk.getPublicExponent()))))));
    }

    static byte[] unsigned(BigInteger v) {
        byte[] b = v.toByteArray();
        return b[0] == 0 ? java.util.Arrays.copyOfRange(b, 1, b.length) : b;
    }

    static String rs256(String kid, KeyPair kp, Map<String, Object> claims) throws Exception {
        String input = B64.encodeToString(Json.writeBytes(Map.of("alg", "RS256", "kid", kid)))
                + "." + B64.encodeToString(Json.writeBytes(claims));
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(kp.getPrivate());
        s.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + B64.encodeToString(s.sign());
    }

    @Test
    void metricsAreAuthenticatedAndTraceReachesTheRestBackend() throws Exception {
        List<String> traces = Collections.synchronizedList(new ArrayList<>());
        HttpServer backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", ex -> {
            traces.add(String.valueOf(ex.getRequestHeaders().getFirst("traceparent")));
            byte[] b = "{\"jobs\":[]}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        });
        backend.start();
        Path cfg = config(backend.getAddress().getPort());
        Gateway gw = GatewayFactory.build(cfg, BuildContext.defaults(dir).withEnv(ENV::get));
        OwnServerSecuritySettings st = OwnServerSecuritySettings.parse(gw.hostSection("host"), dir, ENV::get, Clock.systemUTC());
        try (AegisServer server = new AegisServer(gw, st)) {
            server.start();
            int port = server.port();
            HttpClient http = HttpClient.newHttpClient();
            String analyst = hs256(claims("alice", List.of("analysts")));
            String inbound = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
            Map<String, String> h = new LinkedHashMap<>(modernHeaders("flink_list_jobs"));
            h.put("traceparent", inbound);
            assertEquals(200, post(http, port, "/mcp", analyst, modernCall("flink_list_jobs", Map.of(), Map.of()), h).statusCode());
            assertEquals(1, traces.size());
            assertTrue(traces.get(0).startsWith("00-4bf92f3577b34da6a3ce929d0e0e4736-"), traces.get(0));
            post(http, port, "/mcp", analyst, modernCall("flink_cancel_job", Map.of("jobId", "j1"), Map.of()), modernHeaders("flink_cancel_job"));

            URI metrics = URI.create("http://127.0.0.1:" + port + "/metrics");
            assertEquals(401, http.send(HttpRequest.newBuilder(metrics).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            HttpResponse<String> m = http.send(HttpRequest.newBuilder(metrics).header("Authorization", "Bearer " + analyst).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, m.statusCode());
            assertTrue(m.headers().firstValue("Content-Type").orElse("").startsWith("text/plain; version=0.0.4"));
            assertTrue(m.body().contains("aegis_decisions_total{kind=\"tool\",method=\"flink_list_jobs\",source=\"flink\",outcome=\"ALLOWED\"} 1"), m.body());
            assertTrue(m.body().contains("method=\"flink_cancel_job\",source=\"flink\",outcome=\"READONLY_CALLER\"} 1"), m.body());
            assertTrue(m.body().contains("aegis_backend_calls_total{kind=\"tool\",source=\"flink\"} 1"), m.body());
            assertEquals(405, http.send(HttpRequest.newBuilder(metrics).header("Authorization", "Bearer " + analyst)
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        } finally {
            backend.stop(0);
        }
    }
}
