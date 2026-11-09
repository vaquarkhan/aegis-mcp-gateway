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
/*
 * Gaps L2 to L4 from the gateway comparison, over real HTTP on the gateway's own listener:
 * gateway-side token revocation, pre-authentication per-address limits and lockout, trusted-proxy
 * handling, a separate admin listener and the complete-control perimeter rules.
 * Verdict lines: THREATMODEL|id|refs|class|verdict|detail
 */
package io.github.vaquarkhan.aegis.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.vaquarkhan.aegis.config.BuildContext;
import io.github.vaquarkhan.aegis.config.ConfigException;
import io.github.vaquarkhan.aegis.config.Gateway;
import io.github.vaquarkhan.aegis.config.GatewayFactory;
import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.security.perimeter.OwnServerSecuritySettings;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ThreatModelGapServerTest {

    @TempDir
    Path dir;

    static void verdict(String id, String refs, String cls, String verdict, String detail) {
        System.out.println("THREATMODEL|" + id + "|" + refs + "|" + cls + "|" + verdict + "|" + detail);
    }

    private Path config(int restPort, String accessExtra, String jwtExtra, String hostExtra) throws Exception {
        String json = """
                {
                  "gateway": {"name": "gap-test"},
                  "access": {"approverGroups": ["approvers"]%s},
                  "policy": {"rules": [{"id": "read", "groups": ["analysts"], "classes": ["READ"]}]},
                  "egress": {"allowCidrs": ["127.0.0.0/8"]},
                  "audit": {"file": "audit.jsonl"},
                  "sources": [{"id": "rest", "type": "http", "baseUrl": "http://127.0.0.1:%d",
                    "tools": [{"name": "list_jobs", "description": "List jobs.", "class": "READ",
                               "request": {"method": "GET", "path": "/jobs"}}]}],
                  "host": {
                    "listen": {"host": "127.0.0.1", "port": 0},
                    "identity": {"type": "jwt", "jwt": {"issuer": "https://idp.test", "audience": "aegis",
                      "algorithms": ["HS256"], "hmacSecretEnv": "JWT_SECRET"%s}}%s
                  }
                }""".formatted(accessExtra, restPort, jwtExtra, hostExtra);
        Path p = dir.resolve("aegis-" + System.nanoTime() + ".json");
        Files.writeString(p, json);
        return p;
    }

    private static HttpResponse<String> call(HttpClient http, int port, String token, Map<String, String> extra) throws Exception {
        Map<String, Object> body = Map.of("jsonrpc", "2.0", "id", 1L, "method", "tools/call", "params", Map.of(
                "name", "list_jobs", "arguments", Map.of(), "_meta", Map.of(
                        "io.modelcontextprotocol/protocolVersion", "2026-07-28",
                        "io.modelcontextprotocol/clientCapabilities", Map.of())));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", "application/json").header("MCP-Protocol-Version", "2026-07-28")
                .header("Mcp-Method", "tools/call").header("Mcp-Name", "list_jobs")
                .POST(HttpRequest.BodyPublishers.ofByteArray(Json.writeBytes(body)));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        extra.forEach(b::header);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> admin(HttpClient http, int port, String method, String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/aegis/v1" + path))
                .header("Authorization", "Bearer " + token);
        b = "POST".equals(method) ? b.POST(HttpRequest.BodyPublishers.noBody()) : b.GET();
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String token(String sub, List<String> groups, String jti) throws Exception {
        Map<String, Object> c = GatewayHostTest.claims(sub, groups);
        if (jti != null) {
            c.put("jti", jti);
        }
        return GatewayHostTest.hs256(c);
    }

    @Test
    void revokedTokensAreRefusedAtTheFrontDoor() throws Exception {
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        HttpServer backend = GatewayHostTest.rest(seen);
        Path cfg = config(backend.getAddress().getPort(), ", \"revokerGroups\": [\"security\"]",
                ", \"requireJti\": true, \"requireRevocation\": true", "");
        // O-3 A: requireRevocation with the built-in memory store is refused at the perimeter.
        Gateway memoryOnly = GatewayFactory.build(cfg, BuildContext.defaults(dir).withEnv(GatewayHostTest.ENV::get));
        OwnServerSecuritySettings st = OwnServerSecuritySettings.parse(memoryOnly.hostSection("host"), dir, GatewayHostTest.ENV::get,
                Clock.systemUTC());
        IllegalArgumentException volatileState = assertThrows(IllegalArgumentException.class, () -> new AegisServer(memoryOnly, st));
        assertTrue(volatileState.getMessage().contains("durable"), volatileState.getMessage());
        memoryOnly.close();
        BuildContext hostStore = new BuildContext(GatewayHostTest.ENV::get, dir, null, null, null, null, null,
                new io.github.vaquarkhan.aegis.engine.state.InMemoryStateStore());
        Gateway gw = GatewayFactory.build(cfg, hostStore);
        OwnServerSecuritySettings st2 = OwnServerSecuritySettings.parse(gw.hostSection("host"), dir, GatewayHostTest.ENV::get,
                Clock.systemUTC());
        try (AegisServer server = new AegisServer(gw, st2)) {
            server.start();
            int port = server.port();
            HttpClient http = HttpClient.newHttpClient();
            String alice = token("alice", List.of("analysts"), "alice-token-1");
            String security = token("sec", List.of("security"), "sec-token-1");

            assertEquals(401, call(http, port, token("alice", List.of("analysts"), null), Map.of()).statusCode(),
                    "requireJti: a token without jti is refused");
            assertEquals(200, call(http, port, alice, Map.of()).statusCode());
            assertEquals(403, admin(http, port, "POST", "/revocations/token/alice-token-1", alice).statusCode(),
                    "only revoker groups may revoke");
            HttpResponse<String> revoked = admin(http, port, "POST", "/revocations/token/alice-token-1", security);
            assertEquals(200, revoked.statusCode(), revoked.body());
            assertEquals(401, call(http, port, alice, Map.of()).statusCode(), "the revoked token is refused before exp");
            String alice2 = token("alice", List.of("analysts"), "alice-token-2");
            assertEquals(200, call(http, port, alice2, Map.of()).statusCode(), "a different token still works");

            HttpResponse<String> subject = admin(http, port, "POST", "/revocations/subject/alice", security);
            assertEquals(200, subject.statusCode(), subject.body());
            assertEquals(401, call(http, port, alice2, Map.of()).statusCode(), "every earlier token for the subject is refused");
            int calls = seen.size();
            assertEquals(2, calls, "only the two admitted calls reached the backend");
            String journal = Files.readString(dir.resolve("audit.jsonl"));
            assertTrue(journal.contains("TOKEN_REVOKE_INTENT") && journal.contains("TOKEN_REVOKE_RESULT"), "revocations are journaled");
        } finally {
            backend.stop(0);
        }
        verdict("GAP-L2c", "ContextForge token revocation parity; stolen bearer replay", "AdminApi revocations + AegisServer bind",
                "FIXED", "jti required; POST /revocations/token and /subject (revoker group, journaled) refuse tokens before exp");
    }

    @Test
    void preAuthenticationLimitsAndLockoutRunBeforeTokenVerification() throws Exception {
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        HttpServer backend = GatewayHostTest.rest(seen);
        Path cfg = config(backend.getAddress().getPort(), "", "",
                ", \"limits\": {\"requestsPerMinute\": 1000, \"authFailuresPerMinute\": 3, \"lockoutSeconds\": 60,"
                        + " \"trustedProxies\": [\"127.0.0.1/32\"]}");
        Gateway gw = GatewayFactory.build(cfg, BuildContext.defaults(dir).withEnv(GatewayHostTest.ENV::get));
        OwnServerSecuritySettings st = OwnServerSecuritySettings.parse(gw.hostSection("host"), dir, GatewayHostTest.ENV::get,
                Clock.systemUTC());
        try (AegisServer server = new AegisServer(gw, st)) {
            server.start();
            int port = server.port();
            HttpClient http = HttpClient.newHttpClient();
            String good = token("alice", List.of("analysts"), null);
            Map<String, String> attacker = Map.of("X-Forwarded-For", "203.0.113.9");
            Map<String, String> victim = Map.of("X-Forwarded-For", "198.51.100.7");
            // Unauthenticated requests (MCP OAuth discovery starts with one) are answered 401 but never
            // count towards lockout: only a presented and rejected credential does.
            Map<String, String> discovering = Map.of("X-Forwarded-For", "192.0.2.50");
            for (int i = 0; i < 6; i++) {
                assertEquals(401, call(http, port, null, discovering).statusCode());
            }
            assertEquals(200, call(http, port, good, discovering).statusCode(), "discovery did not lock the client out");
            for (int i = 0; i < 4; i++) {
                assertEquals(401, call(http, port, "bad.token." + i, attacker).statusCode());
            }
            HttpResponse<String> locked = call(http, port, good, attacker);
            assertEquals(429, locked.statusCode(), "locked out even with a valid token: " + locked.body());
            assertNotNull(locked.headers().firstValue("Retry-After").orElse(null));
            assertEquals(200, call(http, port, good, victim).statusCode(), "another client behind the same proxy is unaffected");
            // A spoofed X-Forwarded-For from an untrusted peer is ignored: covered by the unit test below.
        } finally {
            backend.stop(0);
        }

        HttpServer backend2 = GatewayHostTest.rest(seen);
        Path rateCfg = config(backend2.getAddress().getPort(), "", "", ", \"limits\": {\"requestsPerMinute\": 3}");
        Gateway gw2 = GatewayFactory.build(rateCfg, BuildContext.defaults(dir).withEnv(GatewayHostTest.ENV::get));
        OwnServerSecuritySettings st2 = OwnServerSecuritySettings.parse(gw2.hostSection("host"), dir, GatewayHostTest.ENV::get,
                Clock.systemUTC());
        try (AegisServer server = new AegisServer(gw2, st2)) {
            server.start();
            HttpClient http = HttpClient.newHttpClient();
            String good = token("alice", List.of("analysts"), null);
            int ok = 0;
            int limited = 0;
            for (int i = 0; i < 5; i++) {
                int code = call(http, server.port(), good, Map.of()).statusCode();
                ok += code == 200 ? 1 : 0;
                limited += code == 429 ? 1 : 0;
            }
            assertTrue(ok <= 3 && limited >= 2, "ok=" + ok + " limited=" + limited);
        } finally {
            backend2.stop(0);
        }

        assertThrows(ConfigException.class, () -> OwnServerSecuritySettings.parse(Json.parseObject(
                "{\"identity\":{\"type\":\"jwt\",\"jwt\":{\"issuer\":\"i\",\"audience\":\"a\",\"algorithms\":[\"HS256\"],"
                        + "\"hmacSecretEnv\":\"JWT_SECRET\"}},\"limits\":{\"trustedProxies\":[\"proxy.example\"]}}"),
                dir, GatewayHostTest.ENV::get, Clock.systemUTC()), "trustedProxies must be IP literals or CIDRs");
        verdict("GAP-L3a", "ContextForge IP rate limit + lockout parity; pre-auth flood", "ClientAddressLimiter + AegisServer.preAuth",
                "FIXED", "4 bad tokens lock the address (429 + Retry-After, before verification); per-address budget; XFF only from trusted proxies");
    }

    @Test
    void adminApiCanLiveOnItsOwnListenerAndCompleteControlRequiresIt() throws Exception {
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        HttpServer backend = GatewayHostTest.rest(seen);
        Path cfg = config(backend.getAddress().getPort(), "", "",
                ", \"adminListen\": {\"host\": \"127.0.0.1\", \"port\": 0}, \"routes\": {\"adminGroups\": [\"approvers\"]}");
        Gateway gw = GatewayFactory.build(cfg, BuildContext.defaults(dir).withEnv(GatewayHostTest.ENV::get));
        OwnServerSecuritySettings st = OwnServerSecuritySettings.parse(gw.hostSection("host"), dir, GatewayHostTest.ENV::get,
                Clock.systemUTC());
        try (AegisServer server = new AegisServer(gw, st)) {
            server.start();
            HttpClient http = HttpClient.newHttpClient();
            String approver = token("bob", List.of("approvers"), null);
            assertTrue(server.adminPort() > 0 && server.adminPort() != server.port());
            assertEquals(404, admin(http, server.port(), "GET", "/approvals", approver).statusCode(),
                    "the admin API is not mounted on the MCP listener");
            assertEquals(200, admin(http, server.adminPort(), "GET", "/approvals", approver).statusCode());
            assertEquals(403, admin(http, server.adminPort(), "GET", "/approvals",
                    token("alice", List.of("analysts"), null)).statusCode(), "adminGroups gate the admin listener");
        } finally {
            backend.stop(0);
        }

        // Complete-control perimeter rules (checked by the same method the constructor calls).
        OwnServerSecuritySettings shared = OwnServerSecuritySettings.parse(Json.parseObject(
                "{\"identity\":{\"type\":\"jwt\",\"jwt\":{\"issuer\":\"i\",\"audience\":\"a\",\"algorithms\":[\"HS256\"],"
                        + "\"hmacSecretEnv\":\"JWT_SECRET\"}}}"), dir, GatewayHostTest.ENV::get, Clock.systemUTC());
        AegisServer.checkCompleteControlPerimeter(false, shared);
        IllegalArgumentException noGroups = assertThrows(IllegalArgumentException.class,
                () -> AegisServer.checkCompleteControlPerimeter(true, shared));
        assertTrue(noGroups.getMessage().contains("adminGroups"));
        OwnServerSecuritySettings grouped = OwnServerSecuritySettings.parse(Json.parseObject(
                "{\"identity\":{\"type\":\"jwt\",\"jwt\":{\"issuer\":\"i\",\"audience\":\"a\",\"algorithms\":[\"HS256\"],"
                        + "\"hmacSecretEnv\":\"JWT_SECRET\"}},\"routes\":{\"adminGroups\":[\"ops\"]}}"), dir,
                GatewayHostTest.ENV::get, Clock.systemUTC());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AegisServer.checkCompleteControlPerimeter(true, grouped))
                .getMessage().contains("adminListen"));
        OwnServerSecuritySettings separate = OwnServerSecuritySettings.parse(Json.parseObject(
                "{\"identity\":{\"type\":\"jwt\",\"jwt\":{\"issuer\":\"i\",\"audience\":\"a\",\"algorithms\":[\"HS256\"],"
                        + "\"hmacSecretEnv\":\"JWT_SECRET\"}},\"routes\":{\"adminGroups\":[\"ops\"]},"
                        + "\"adminListen\":{\"port\":0}}"), dir, GatewayHostTest.ENV::get, Clock.systemUTC());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AegisServer.checkCompleteControlPerimeter(true, separate))
                .getMessage().contains("requireJti"));
        OwnServerSecuritySettings full = OwnServerSecuritySettings.parse(Json.parseObject(
                "{\"identity\":{\"type\":\"jwt\",\"jwt\":{\"issuer\":\"i\",\"audience\":\"a\",\"algorithms\":[\"HS256\"],"
                        + "\"hmacSecretEnv\":\"JWT_SECRET\",\"requireJti\":true,\"requireRevocation\":true}},"
                        + "\"routes\":{\"adminGroups\":[\"ops\"]},\"adminListen\":{\"port\":0}}"), dir, GatewayHostTest.ENV::get,
                Clock.systemUTC());
        AegisServer.checkCompleteControlPerimeter(true, full);
        verdict("GAP-L4a", "shared MCP/admin listener; admin open to any authenticated caller", "host.adminListen + checkCompleteControlPerimeter",
                "FIXED", "admin API 404 on MCP port when separated; complete control requires adminGroups, adminListen, requireJti, requireRevocation");
    }
}
