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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.config.BuildContext;
import io.github.vaquarkhan.aegis.config.ConfigException;
import io.github.vaquarkhan.aegis.config.Gateway;
import io.github.vaquarkhan.aegis.config.GatewayFactory;
import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.security.oauth.ProtectedResourceMetadata;
import io.github.vaquarkhan.aegis.security.perimeter.OwnServerSecuritySettings;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * OAuth 2.0 Protected Resource Metadata (RFC 9728) as the MCP authorization specification requires:
 * a 401 tells the client where to look, and the document is discoverable without a token.
 */
class ProtectedResourceMetadataTest {

    @TempDir
    Path dir;

    static final Map<String, String> ENV = Map.of("AEGIS_SIGNING_KEY", "k".repeat(40), "JWT_SECRET", "s".repeat(40));

    @Test
    void metadataPathAndChallengeFollowRfc9728() {
        ProtectedResourceMetadata m = new ProtectedResourceMetadata(URI.create("https://aegis.example.com/mcp"),
                List.of("https://idp.example.com"), List.of("mcp.read"), "https://wiki.example.com/aegis");
        assertEquals("/.well-known/oauth-protected-resource/mcp", m.metadataPath());
        assertEquals("https://aegis.example.com/.well-known/oauth-protected-resource/mcp", m.metadataUrl());
        assertEquals("Bearer realm=\"aegis\", resource_metadata=\"https://aegis.example.com/.well-known/oauth-protected-resource/mcp\"",
                m.challenge());
        Map<String, Object> doc = Json.parseObject(m.json());
        assertEquals("https://aegis.example.com/mcp", doc.get("resource"));
        assertEquals(List.of("https://idp.example.com"), doc.get("authorization_servers"));
        assertEquals(List.of("mcp.read"), doc.get("scopes_supported"));
        assertEquals(List.of("header"), doc.get("bearer_methods_supported"));
        assertEquals("https://wiki.example.com/aegis", doc.get("resource_documentation"));

        // A resource at the authority root publishes at the bare well-known path.
        assertEquals("/.well-known/oauth-protected-resource",
                new ProtectedResourceMetadata(URI.create("https://a.example.com/"), List.of("https://i"), null, null).metadataPath());
        assertFalse(new ProtectedResourceMetadata(URI.create("https://a.example.com/mcp"), List.of("https://i"), null, null)
                .json().contains("scopes_supported"), "empty scopes are omitted, not published as []");

        assertThrows(IllegalArgumentException.class, () -> new ProtectedResourceMetadata(
                URI.create("http://aegis.example.com/mcp"), List.of("https://i"), null, null), "plain http is not a resource identifier");
        assertThrows(IllegalArgumentException.class, () -> new ProtectedResourceMetadata(
                URI.create("https://a.example.com/mcp#x"), List.of("https://i"), null, null));
        assertThrows(IllegalArgumentException.class, () -> new ProtectedResourceMetadata(
                URI.create("https://a.example.com/mcp?x=1"), List.of("https://i"), null, null));
        assertThrows(IllegalArgumentException.class, () -> new ProtectedResourceMetadata(
                URI.create("/mcp"), List.of("https://i"), null, null));
        assertThrows(IllegalArgumentException.class, () -> new ProtectedResourceMetadata(
                URI.create("https://a.example.com/mcp"), List.of(), null, null), "an authorization server is required");
        assertThrows(IllegalArgumentException.class, () -> new ProtectedResourceMetadata(
                URI.create("https://a.example.com/mcp"), List.of("not-absolute"), null, null));
        // Loopback http is allowed for local development only.
        assertEquals("/.well-known/oauth-protected-resource/mcp",
                new ProtectedResourceMetadata(URI.create("http://127.0.0.1:8080/mcp"), List.of("https://i"), null, null).metadataPath());
    }

    private Path config(String oauth, String audience) throws Exception {
        String json = """
                {"policy": {"rules": [{"id": "r", "groups": ["analysts"], "classes": ["READ"]}]},
                 "approval": {"keyEnv": "AEGIS_SIGNING_KEY"},
                 "audit": {"file": "audit.jsonl"},
                 "egress": {"allowCidrs": ["127.0.0.0/8"]},
                 "sources": [{"id": "s", "type": "http", "baseUrl": "http://127.0.0.1:1",
                   "tools": [{"name": "ping", "description": "Ping.", "class": "READ"}]}],
                 "host": {
                   "listen": {"host": "127.0.0.1", "port": 0},
                   "identity": {"type": "jwt", "jwt": {"issuer": "https://idp.test", "audience": "%s",
                     "algorithms": ["HS256"], "hmacSecretEnv": "JWT_SECRET"}}%s}}""".formatted(audience, oauth);
        Path p = dir.resolve("aegis.json");
        Files.writeString(p, json);
        return p;
    }

    static final String OAUTH = """
            ,
                   "oauth": {"resource": "http://127.0.0.1:8080/mcp", "authorizationServers": ["https://idp.test"],
                             "scopesSupported": ["mcp.read"]}""";

    @Test
    void theEndpointIsServedAndEvery401PointsAtIt() throws Exception {
        Path cfg = config(OAUTH, "http://127.0.0.1:8080/mcp");
        Gateway gw = GatewayFactory.build(cfg, BuildContext.defaults(dir).withEnv(ENV::get));
        OwnServerSecuritySettings st = OwnServerSecuritySettings.parse(gw.hostSection("host"), dir, ENV::get, Clock.systemUTC());
        try (AegisServer server = new AegisServer(gw, st)) {
            server.start();
            HttpClient http = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();

            HttpResponse<String> doc = http.send(HttpRequest.newBuilder(
                    URI.create(base + "/.well-known/oauth-protected-resource/mcp")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, doc.statusCode(), "discovery must work without a token");
            assertEquals("application/json", doc.headers().firstValue("Content-Type").orElse(""));
            assertEquals("http://127.0.0.1:8080/mcp", Json.parseObject(doc.body()).get("resource"));

            // Every authenticated route, with the method that route accepts (/metrics is GET only).
            for (String path : List.of("POST /mcp", "GET /aegis/v1/approvals", "GET /metrics")) {
                String[] parts = path.split(" ");
                HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(base + parts[1]));
                HttpResponse<String> unauth = http.send("POST".equals(parts[0])
                        ? rb.POST(HttpRequest.BodyPublishers.ofString("{}")).build() : rb.GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(401, unauth.statusCode(), path);
                String challenge = unauth.headers().firstValue("WWW-Authenticate").orElse("");
                assertTrue(challenge.contains("resource_metadata=\"http://127.0.0.1:8080/.well-known/oauth-protected-resource/mcp\""),
                        path + " -> " + challenge);
            }

            assertEquals(404, http.send(HttpRequest.newBuilder(URI.create(base + "/.well-known/oauth-protected-resource/mcp/other"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(405, http.send(HttpRequest.newBuilder(URI.create(base + "/.well-known/oauth-protected-resource/mcp"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    private static String hs256(Map<String, Object> claims) throws Exception {
        java.util.Base64.Encoder b64 = java.util.Base64.getUrlEncoder().withoutPadding();
        String input = b64.encodeToString(Json.writeBytes(Map.of("alg", "HS256", "typ", "JWT"))) + "."
                + b64.encodeToString(Json.writeBytes(claims));
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(ENV.get("JWT_SECRET").getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "HmacSHA256"));
        return input + "." + b64.encodeToString(mac.doFinal(input.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
    }

    @Test
    void overTheWireAValidTokenWithoutTheRequiredScopeGets403InsufficientScope() throws Exception {
        String oauth = OAUTH.replace("\"scopesSupported\": [\"mcp.read\"]}", "\"scopesSupported\": [\"mcp.read\"]},"
                + " \"routes\": {\"mcpScopes\": [\"mcp.read\"]}");
        Path cfg = config(oauth, "http://127.0.0.1:8080/mcp");
        Gateway gw = GatewayFactory.build(cfg, BuildContext.defaults(dir).withEnv(ENV::get));
        OwnServerSecuritySettings st = OwnServerSecuritySettings.parse(gw.hostSection("host"), dir, ENV::get, Clock.systemUTC());
        try (AegisServer server = new AegisServer(gw, st)) {
            server.start();
            HttpClient http = HttpClient.newHttpClient();
            long now = Clock.systemUTC().instant().getEpochSecond();
            Map<String, Object> claims = new java.util.LinkedHashMap<>(Map.of("iss", "https://idp.test",
                    "aud", "http://127.0.0.1:8080/mcp", "sub", "alice", "groups", List.of("analysts"), "exp", now + 300));
            HttpResponse<String> noScope = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/mcp"))
                    .header("Authorization", "Bearer " + hs256(claims))
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, noScope.statusCode());
            String challenge = noScope.headers().firstValue("WWW-Authenticate").orElse("");
            assertTrue(challenge.contains("error=\"insufficient_scope\"") && challenge.contains("scope=\"mcp.read\"")
                    && challenge.contains("resource_metadata="), challenge);

            claims.put("scope", "mcp.read");
            HttpResponse<String> scoped = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/mcp"))
                    .header("Authorization", "Bearer " + hs256(claims))
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertFalse(scoped.statusCode() == 401 || scoped.statusCode() == 403,
                    "a token with the scope passes the perimeter (the MCP handler then judges the body): " + scoped.statusCode());
        }
    }

    @Test
    void aToolCallWithoutTheToolsActionScopesGetsAStepUpChallengeNamingThem() throws Exception {
        String oauth = OAUTH.replace("\"scopesSupported\": [\"mcp.read\"]}", "\"scopesSupported\": [\"mcp.read\"]},"
                + " \"routes\": {\"mcpScopes\": [\"mcp.read\"]}");
        Path cfg = config(oauth, "http://127.0.0.1:8080/mcp");
        Files.writeString(cfg, Files.readString(cfg).replace("\"class\": \"READ\"}",
                "\"class\": \"READ\", \"actionScopes\": [\"reports:read\"]}"));
        Gateway gw = GatewayFactory.build(cfg, BuildContext.defaults(dir).withEnv(ENV::get));
        OwnServerSecuritySettings st = OwnServerSecuritySettings.parse(gw.hostSection("host"), dir, ENV::get, Clock.systemUTC());
        try (AegisServer server = new AegisServer(gw, st)) {
            server.start();
            HttpClient http = HttpClient.newHttpClient();
            long now = Clock.systemUTC().instant().getEpochSecond();
            Map<String, Object> claims = new java.util.LinkedHashMap<>(Map.of("iss", "https://idp.test",
                    "aud", "http://127.0.0.1:8080/mcp", "sub", "alice", "groups", List.of("analysts"), "exp", now + 300,
                    "scope", "mcp.read"));
            String call = Json.write(Map.of("jsonrpc", "2.0", "id", 1L, "method", "tools/call", "params", Map.of("name", "ping",
                    "arguments", Map.of(), "_meta", Map.of("io.modelcontextprotocol/protocolVersion", "2026-07-28",
                            "io.modelcontextprotocol/clientCapabilities", Map.of()))));
            java.util.function.Function<String, HttpRequest> request = token -> HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + server.port() + "/mcp"))
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .header("MCP-Protocol-Version", "2026-07-28").header("Mcp-Method", "tools/call").header("Mcp-Name", "ping")
                    .POST(HttpRequest.BodyPublishers.ofString(call)).build();

            // The route scope is present, the tool's is not: 403 with both scopes named, and the
            // JSON-RPC result still reports SCOPE_DENIED.
            HttpResponse<String> missing = http.send(request.apply(hs256(claims)), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, missing.statusCode(), missing.body());
            String challenge = missing.headers().firstValue("WWW-Authenticate").orElse("");
            assertTrue(challenge.contains("error=\"insufficient_scope\"") && challenge.contains("scope=\"mcp.read reports:read\"")
                    && challenge.contains("resource_metadata="), challenge);
            assertTrue(missing.body().contains("SCOPE_DENIED"), missing.body());

            // With the tool scope the call passes governance; the backend at port 1 is down, which is
            // an ordinary tool error, not a challenge.
            claims.put("scope", "mcp.read reports:read");
            HttpResponse<String> granted = http.send(request.apply(hs256(claims)), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, granted.statusCode(), granted.body());
            assertFalse(granted.headers().firstValue("WWW-Authenticate").isPresent());
            assertFalse(granted.body().contains("SCOPE_DENIED"), granted.body());
        }
    }

    @Test
    void withoutTheSectionNothingIsPublishedAndTheChallengeStaysPlain() throws Exception {
        Path cfg = config("", "aegis");
        Gateway gw = GatewayFactory.build(cfg, BuildContext.defaults(dir).withEnv(ENV::get));
        OwnServerSecuritySettings st = OwnServerSecuritySettings.parse(gw.hostSection("host"), dir, ENV::get, Clock.systemUTC());
        assertEquals("Bearer realm=\"aegis\"", st.challenge());
        try (AegisServer server = new AegisServer(gw, st)) {
            server.start();
            HttpClient http = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();
            assertEquals(404, http.send(HttpRequest.newBuilder(URI.create(base + "/.well-known/oauth-protected-resource"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            HttpResponse<String> unauth = http.send(HttpRequest.newBuilder(URI.create(base + "/mcp"))
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, unauth.statusCode());
            assertFalse(unauth.headers().firstValue("WWW-Authenticate").orElse("").contains("resource_metadata"));
        }
    }

    @Test
    void anAudienceThatIsNotTheResourceMustBeDeclared() throws Exception {
        Path mismatch = config(OAUTH, "some-other-audience");
        Gateway gw = GatewayFactory.build(mismatch, BuildContext.defaults(dir).withEnv(ENV::get));
        try (gw) {
            ConfigException e = assertThrows(ConfigException.class,
                    () -> OwnServerSecuritySettings.parse(gw.hostSection("host"), dir, ENV::get, Clock.systemUTC()));
            assertTrue(e.getMessage().contains("audience-bound"), e.getMessage());
        }
        String allowed = OAUTH.substring(0, OAUTH.length() - 1) + ", \"allowAudienceMismatch\": true}";
        Path opt = config(allowed, "some-other-audience");
        Gateway gw2 = GatewayFactory.build(opt, BuildContext.defaults(dir).withEnv(ENV::get));
        try (gw2) {
            assertTrue(OwnServerSecuritySettings.parse(gw2.hostSection("host"), dir, ENV::get, Clock.systemUTC())
                    .challenge().contains("resource_metadata"));
        }
    }
}
