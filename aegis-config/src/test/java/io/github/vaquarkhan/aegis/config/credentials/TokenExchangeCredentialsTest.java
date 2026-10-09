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
package io.github.vaquarkhan.aegis.config.credentials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.vaquarkhan.aegis.config.BuildContext;
import io.github.vaquarkhan.aegis.config.ConfigException;
import io.github.vaquarkhan.aegis.config.Gateway;
import io.github.vaquarkhan.aegis.config.GatewayFactory;
import io.github.vaquarkhan.aegis.engine.CallOutcome;
import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.model.DenyCodes;
import io.github.vaquarkhan.aegis.engine.model.ToolCall;
import io.github.vaquarkhan.aegis.engine.spi.SubjectToken;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Request shape, caching and every fail-closed branch, against a local listener that answers like a
 * token endpoint. The exchange itself is proven against Keycloak's standard token exchange in
 * docs/evidence/TOKEN-EXCHANGE-KEYCLOAK.md; this class covers the answers a correct server never gives.
 */
class TokenExchangeCredentialsTest {

    @TempDir
    Path dir;

    private HttpServer server;
    private String base;
    private volatile int status = 200;
    private volatile String answer;
    private final List<Map<String, String>> forms = new CopyOnWriteArrayList<>();
    private final List<String> auth = new CopyOnWriteArrayList<>();
    private final List<String> backendAuth = new CopyOnWriteArrayList<>();

    static final CallerIdentity ALICE = new CallerIdentity("alice", "user-1", Set.of("analysts"), "idp", "jwt:RS256", List.of());

    static String jwt(String sub) {
        Base64.Encoder e = Base64.getUrlEncoder().withoutPadding();
        return e.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + e.encodeToString(("{\"sub\":\"" + sub + "\",\"aud\":\"orders-api\"}").getBytes(StandardCharsets.UTF_8)) + ".c2ln";
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", ex -> {
            Map<String, String> form = new LinkedHashMap<>();
            for (String kv : new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("&")) {
                String[] p = kv.split("=", 2);
                form.put(URLDecoder.decode(p[0], StandardCharsets.UTF_8), URLDecoder.decode(p[1], StandardCharsets.UTF_8));
            }
            forms.add(form);
            auth.add(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] b = answer.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.createContext("/orders", ex -> {
            backendAuth.add(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] b = "{\"orders\":[1]}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        answer = "{\"access_token\":\"" + jwt("user-1") + "\",\"token_type\":\"Bearer\",\"expires_in\":300,"
                + "\"issued_token_type\":\"urn:ietf:params:oauth:token-type:access_token\"}";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private TokenExchangeCredentials exchange(boolean checkSubject) {
        return new TokenExchangeCredentials("orders", new TokenExchangeCredentials.Settings(URI.create(base + "/token"),
                "aegis gateway", "s3cret&=", "orders-api", null, "orders.read", Duration.ofSeconds(5), checkSubject), null);
    }

    @Test
    void exchangesTheBoundTokenAndCachesPerSubject() throws Exception {
        TokenExchangeCredentials x = exchange(true);
        try (SubjectToken.Scope s = SubjectToken.bind("inbound-token-of-alice")) {
            assertEquals(Map.of("Authorization", "Bearer " + jwt("user-1")), x.headers(ALICE));
            assertEquals(Map.of("Authorization", "Bearer " + jwt("user-1")), x.headers(ALICE));
        }
        assertEquals(1, forms.size(), "the second call used the cache");
        Map<String, String> f = forms.get(0);
        assertEquals(TokenExchangeCredentials.GRANT, f.get("grant_type"));
        assertEquals("inbound-token-of-alice", f.get("subject_token"));
        assertEquals(TokenExchangeCredentials.ACCESS_TOKEN_TYPE, f.get("subject_token_type"));
        assertEquals("orders-api", f.get("audience"));
        assertEquals("orders.read", f.get("scope"));
        assertEquals("Basic " + Base64.getEncoder().encodeToString("aegis+gateway:s3cret%26%3D".getBytes(StandardCharsets.UTF_8)),
                auth.get(0), "RFC 6749 2.3.1: form-encode, then Basic");

        try (SubjectToken.Scope s = SubjectToken.bind("another-token")) {
            x.headers(ALICE);
        }
        assertEquals(2, forms.size(), "a different subject token is a different cache entry");
        assertFalse(SubjectToken.current().isPresent(), "the scope restores the empty state");
        assertFalse(x.toString().contains("s3cret"));
    }

    @Test
    void everyUntrustworthyAnswerFailsClosed() throws Exception {
        TokenExchangeCredentials x = exchange(true);
        assertThrows(TokenExchangeCredentials.TokenExchangeException.class, () -> x.headers(ALICE), "nothing bound");
        try (SubjectToken.Scope s = SubjectToken.bind("t1")) {
            status = 400;
            answer = "{\"error\":\"invalid_request\",\"error_description\":\"echo t1\"}";
            Exception e = assertThrows(TokenExchangeCredentials.TokenExchangeException.class, () -> x.headers(ALICE));
            assertTrue(e.getMessage().contains("HTTP 400 invalid_request") && !e.getMessage().contains("echo"), e.getMessage());
            status = 200;
            answer = "{\"token_type\":\"Bearer\"}";
            assertThrows(TokenExchangeCredentials.TokenExchangeException.class, () -> x.headers(ALICE));
            answer = "{\"access_token\":\"abc\",\"token_type\":\"DPoP\"}";
            assertThrows(TokenExchangeCredentials.TokenExchangeException.class, () -> x.headers(ALICE));
            answer = "not json";
            assertThrows(TokenExchangeCredentials.TokenExchangeException.class, () -> x.headers(ALICE));
            answer = "{\"access_token\":\"" + jwt("someone-else") + "\",\"token_type\":\"Bearer\"}";
            Exception other = assertThrows(TokenExchangeCredentials.TokenExchangeException.class, () -> x.headers(ALICE));
            assertTrue(other.getMessage().contains("different subject"));
            // A readable JWT without sub (or with a blank or non-string sub) cannot be tied to the caller.
            Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
            for (String claims : List.of("{\"aud\":\"orders-api\"}", "{\"sub\":\"\"}", "{\"sub\":42}")) {
                String noSub = b64.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                        + b64.encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + ".c2ln";
                answer = "{\"access_token\":\"" + noSub + "\",\"token_type\":\"Bearer\"}";
                Exception none = assertThrows(TokenExchangeCredentials.TokenExchangeException.class, () -> x.headers(ALICE), claims);
                assertTrue(none.getMessage().contains("no subject"), none.getMessage());
            }
            // A five-part compact JWE is a JWT with encrypted claims. checkSubject cannot prove
            // whose token it is, so it fails closed rather than treating it as opaque.
            answer = "{\"access_token\":\"eyJhbGciOiJkaXIifQ.eyJlbmMiOiJBMTI4R0NNIn0.iv.ciphertext.tag\",\"token_type\":\"Bearer\"}";
            Exception jwe = assertThrows(TokenExchangeCredentials.TokenExchangeException.class, () -> x.headers(ALICE));
            assertTrue(jwe.getMessage().contains("encrypted JWT"), jwe.getMessage());
        }
    }

    @Test
    void opaqueAndNotApplicableTypesAndShortLifetimes() throws Exception {
        TokenExchangeCredentials x = exchange(true);
        try (SubjectToken.Scope s = SubjectToken.bind("t1")) {
            answer = "{\"access_token\":\"opaque-value\",\"token_type\":\"N_A\",\"expires_in\":10,"
                    + "\"issued_token_type\":\"urn:ietf:params:oauth:token-type:access_token\"}";
            assertEquals(Map.of("Authorization", "Bearer opaque-value"), x.headers(ALICE), "opaque tokens skip the sub check");
            x.headers(ALICE);
            assertEquals(2, forms.size(), "a token that expires within the skew is not cached");
        }
        TokenExchangeCredentials unchecked = exchange(false);
        try (SubjectToken.Scope s = SubjectToken.bind("t2")) {
            answer = "{\"access_token\":\"" + jwt("someone-else") + "\",\"token_type\":\"bearer\"}";
            assertTrue(unchecked.headers(ALICE).get("Authorization").startsWith("Bearer "), "checkSubject: false is the operator's choice");
        }
    }

    private Map<String, Object> config(String credentials, String egress) {
        return Json.parseObject("""
                {"audit":{"file":"audit.jsonl"},
                 "policy":{"rules":[{"id":"r","groups":["analysts"],"classes":["READ"]}]},
                 "egress":%s,
                 "sources":[{"id":"orders","type":"http","baseUrl":"%s","credentials":%s,
                   "tools":[{"name":"list_orders","description":"List orders.","class":"READ",
                     "request":{"method":"GET","path":"/orders"}}]}]}""".formatted(egress, base, credentials));
    }

    @Test
    void throughTheGatewayTheBackendSeesTheExchangedTokenOnly() throws Exception {
        String creds = "{\"type\":\"token-exchange\",\"tokenEndpoint\":\"" + base + "/token\",\"clientIdEnv\":\"CID\","
                + "\"clientSecretEnv\":\"CSEC\",\"audience\":\"orders-api\"}";
        String egress = "{\"allowCidrs\":[\"127.0.0.0/8\"],\"schemes\":[\"http\"]}";
        BuildContext ctx = BuildContext.defaults(dir).withEnv(Map.of("CID", "aegis", "CSEC", "secret")::get);
        try (Gateway g = GatewayFactory.build(config(creds, egress), ctx)) {
            CallOutcome none = g.engine().call(ALICE, new ToolCall("1", "list_orders", Map.of(), Map.of(), ""));
            assertEquals(DenyCodes.BACKEND_ERROR, none.decision().code(), "no caller token: no backend call, no fallback");
            assertTrue(backendAuth.isEmpty());
            try (SubjectToken.Scope s = SubjectToken.bind("inbound-token-of-alice")) {
                CallOutcome ok = g.engine().call(ALICE, new ToolCall("2", "list_orders", Map.of(), Map.of(), ""));
                assertTrue(ok.allowed(), ok.decision().reason());
            }
            assertEquals(List.of("Bearer " + jwt("user-1")), backendAuth, "the backend got the exchanged token, never the inbound one");
        }
        String journal = java.nio.file.Files.readString(dir.resolve("audit.jsonl"));
        assertFalse(journal.contains("inbound-token-of-alice") || journal.contains(jwt("user-1")), "no token in the audit journal");
    }

    @Test
    void configurationIsCheckedAtStartup() {
        BuildContext ctx = BuildContext.defaults(dir).withEnv(Map.of("CID", "aegis", "CSEC", "secret")::get);
        String egress = "{\"allowCidrs\":[\"127.0.0.0/8\"],\"schemes\":[\"http\"]}";
        String noAudience = "{\"type\":\"token-exchange\",\"tokenEndpoint\":\"" + base + "/token\",\"clientIdEnv\":\"CID\",\"clientSecretEnv\":\"CSEC\"}";
        assertTrue(assertThrows(ConfigException.class, () -> GatewayFactory.build(config(noAudience, egress), ctx))
                .getMessage().contains("audience"));
        String offEgress = "{\"type\":\"token-exchange\",\"tokenEndpoint\":\"https://idp.elsewhere.example/token\","
                + "\"clientIdEnv\":\"CID\",\"clientSecretEnv\":\"CSEC\",\"audience\":\"a\"}";
        assertTrue(assertThrows(ConfigException.class, () -> GatewayFactory.build(config(offEgress, egress), ctx))
                .getMessage().contains("tokenEndpoint is not allowed"));
        String missingSecret = "{\"type\":\"token-exchange\",\"tokenEndpoint\":\"" + base + "/token\",\"clientIdEnv\":\"CID\","
                + "\"clientSecretEnv\":\"NOT_SET\",\"audience\":\"a\"}";
        assertTrue(assertThrows(ConfigException.class, () -> GatewayFactory.build(config(missingSecret, egress), ctx))
                .getMessage().contains("NOT_SET"));
        String strayDiscovery = "{\"type\":\"bearer\",\"tokenEnv\":\"CID\",\"discovery\":{\"type\":\"none\"}}";
        assertThrows(ConfigException.class, () -> GatewayFactory.build(config(strayDiscovery, egress), ctx),
                "discovery belongs to token-exchange only");
    }
}
