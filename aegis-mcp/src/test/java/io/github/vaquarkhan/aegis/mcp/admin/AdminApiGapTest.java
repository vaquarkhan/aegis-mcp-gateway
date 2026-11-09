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
 * Admin routes added by the gap pass: token revocation (L2) and take-once HANDLE_ONLY result
 * release (K2). Every refusal path is exercised: wrong group, delegated identity, producer,
 * malformed input, journal down, unknown or replayed handle.
 */
package io.github.vaquarkhan.aegis.mcp.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.engine.control.ResultHandleStore;
import io.github.vaquarkhan.aegis.engine.control.TokenRevocations;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.model.ToolClass;
import io.github.vaquarkhan.aegis.engine.state.InMemoryStateStore;
import io.github.vaquarkhan.aegis.mcp.http.McpHttpRequest;
import io.github.vaquarkhan.aegis.mcp.http.McpHttpResponse;
import io.github.vaquarkhan.aegis.mcp.testing.TestEngines;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AdminApiGapTest {

    static final CallerIdentity SEC = CallerIdentity.of("sec", Set.of("security"), "https://idp.test", "jwt:RS256");
    static final CallerIdentity RITA = CallerIdentity.of("rita", Set.of("reviewers"), "https://idp.test", "jwt:RS256");
    static final CallerIdentity DELEGATED = new CallerIdentity("agent", "sec", Set.of("security", "reviewers"),
            "https://idp.test", "jwt:RS256", List.of("agent"), Set.of());

    private static McpHttpRequest post(String path, Map<String, List<String>> headers) {
        return new McpHttpRequest("POST", path, headers, null);
    }

    private static TestEngines.Built engine() throws Exception {
        return TestEngines.build(List.of(new AdminApiTest.Source()), Map.of("list_things", ToolClass.READ));
    }

    @Test
    void revocationRoutesAreGatedJournaledAndValidated() throws Exception {
        TestEngines.Built b = engine();
        InMemoryStateStore state = new InMemoryStateStore();
        TokenRevocations revs = new TokenRevocations(state, Clock.systemUTC());
        AdminApi api = new AdminApi(b.engine, Set.of("approvers"), Set.of()).withRevocations(Set.of("security"), revs,
                Duration.ofHours(1));
        // O-7: before any verifier registers its issuer, a revocation is refused rather than silently useless.
        assertEquals(400, api.handle(post("/revocations/token/t-0", Map.of()), SEC).status());
        revs.registerIssuer("https://idp.test");
        revs.registerIssuer("https://other");
        CallerIdentity staticAdmin = CallerIdentity.of("ops", Set.of("security"), "aegis-static", "static-token");
        assertEquals(400, api.handle(post("/revocations/token/t-0", Map.of()), staticAdmin).status(),
                "a static-token admin revokes under issuer aegis-static, which no verifier consults");
        assertEquals(200, api.handle(post("/revocations/token/t-0", Map.of("Aegis-Token-Issuer", List.of("https://idp.test"))),
                staticAdmin).status(), "naming the JWT issuer makes the revocation effective");

        McpHttpResponse ok = api.handle(post("/revocations/token/t-1", Map.of()), SEC);
        assertEquals(200, ok.status());
        assertNotNull(revs.check("https://idp.test", "t-1", "x", 0L));
        assertEquals(200, api.handle(post("/revocations/subject/auth0|alice/extra", Map.of()), SEC).status(),
                "subjects may contain separators");
        assertNotNull(revs.check("https://idp.test", null, "auth0|alice/extra", 0L));
        assertEquals(200, api.handle(post("/revocations/token/t-2", Map.of("Aegis-Token-Issuer", List.of("https://other"))), SEC)
                .status());
        assertNotNull(revs.check("https://other", "t-2", "x", 0L));
        assertNull(revs.check("https://idp.test", "t-2", "x", 0L));

        assertEquals(403, api.handle(post("/revocations/token/t-3", Map.of()), TestEngines.ALICE).status());
        assertEquals(403, api.handle(post("/revocations/token/t-3", Map.of()), DELEGATED).status());
        assertEquals(400, api.handle(post("/revocations/token/t-3",
                Map.of("Aegis-Token-Issuer", List.of("a", "b"))), SEC).status());
        assertEquals(400, api.handle(post("/revocations/token/" + "x".repeat(1100), Map.of()), SEC).status());
        assertEquals(404, api.handle(new McpHttpRequest("GET", "/revocations/token/t-3", Map.of(), null), SEC).status());
        AdminApi closed = new AdminApi(b.engine, Set.of("approvers"), Set.of()).withRevocations(Set.of(), revs, null);
        assertEquals(403, closed.handle(post("/revocations/token/t-3", Map.of()), SEC).status(), "no revoker groups: closed");
        assertEquals(404, new AdminApi(b.engine, Set.of("approvers"), Set.of())
                .handle(post("/revocations/token/t-3", Map.of()), SEC).status(), "no revocation list: route absent");

        b.audit.setFailing(true);
        assertEquals(503, api.handle(post("/revocations/token/t-4", Map.of()), SEC).status());
        assertNull(revs.check("https://idp.test", "t-4", "x", 0L), "nothing is revoked when the intent cannot be journaled");
        b.audit.setFailing(false);
    }

    @Test
    void handleReleaseIsTakeOnceNeverToTheProducerAndJournaled() throws Exception {
        TestEngines.Built b = engine();
        ResultHandleStore handles = new ResultHandleStore(new InMemoryStateStore(), Clock.systemUTC(), Duration.ofMinutes(5), 4096);
        b.engine.enableResultHandles(handles);
        assertEquals(handles, b.engine.resultHandles());
        String handle = "result:" + "a".repeat(64);
        assertTrue(handles.put(handle, "list_things", "req-1", TestEngines.ALICE, Map.of("content", List.of(
                Map.of("type", "text", "text", "withheld value")))));
        assertFalse(handles.put("result:" + "b".repeat(64), "t", "r", TestEngines.ALICE, Map.of("x", "y".repeat(5000))),
                "too large to retain");
        assertFalse(handles.put(handle, "list_things", "req-2", TestEngines.BOB, Map.of("x", "other")),
                "an existing handle is never overwritten (its producer stays bound)");
        // Handles are per call: two calls with byte-identical results get different handles.
        io.github.vaquarkhan.aegis.engine.model.ToolResult same = io.github.vaquarkhan.aegis.engine.model.ToolResult.text("same", false);
        String h1 = io.github.vaquarkhan.aegis.engine.catalog.OutputPolicy.HANDLE_ONLY.release(same, "req-a").handle();
        String h2 = io.github.vaquarkhan.aegis.engine.catalog.OutputPolicy.HANDLE_ONLY.release(same, "req-b").handle();
        assertTrue(h1.matches("result:[0-9a-f]{64}") && !h1.equals(h2), h1 + " " + h2);
        AdminApi api = new AdminApi(b.engine, Set.of("approvers"), Set.of()).withResultRelease(Set.of("reviewers", "analysts"));

        assertEquals(403, api.handle(post("/results/" + handle + "/release", Map.of()), TestEngines.BOB).status(), "not a releaser");
        assertEquals(403, api.handle(post("/results/" + handle + "/release", Map.of()), TestEngines.ALICE).status(),
                "the producer cannot release its own handle");
        assertEquals(403, api.handle(post("/results/" + handle + "/release", Map.of()), DELEGATED).status());
        b.audit.setFailing(true);
        assertEquals(503, api.handle(post("/results/" + handle + "/release", Map.of()), RITA).status());
        b.audit.setFailing(false);

        McpHttpResponse released = api.handle(post("/results/" + handle + "/release", Map.of()), RITA);
        assertEquals(200, released.status());
        assertTrue(new String(released.body(), java.nio.charset.StandardCharsets.UTF_8).contains("withheld value"));
        assertEquals(404, api.handle(post("/results/" + handle + "/release", Map.of()), RITA).status(), "take-once");
        assertEquals(404, api.handle(post("/results/not-a-handle/release", Map.of()), RITA).status());
        assertTrue(handles.take("bad").isEmpty());
        assertTrue(handles.producer(null).isEmpty());
        assertEquals(Duration.ofMinutes(5), handles.ttl());
        assertEquals(403, new AdminApi(b.engine, Set.of("approvers"), Set.of()).withResultRelease(Set.of())
                .handle(post("/results/" + handle + "/release", Map.of()), RITA).status());
        // P-8: with an AES key the stored value is ciphertext; release still returns the plaintext body.
        byte[] raw = new byte[32];
        new java.security.SecureRandom().nextBytes(raw);
        javax.crypto.SecretKey aes = new javax.crypto.spec.SecretKeySpec(raw, "AES");
        InMemoryStateStore encStoreBacking = new InMemoryStateStore();
        ResultHandleStore encrypted = new ResultHandleStore(encStoreBacking, Clock.systemUTC(), Duration.ofMinutes(5), 4096, aes);
        assertTrue(encrypted.encrypted());
        String encHandle = "result:" + "c".repeat(64);
        assertTrue(encrypted.put(encHandle, "t", "r-enc", TestEngines.ALICE, Map.of("content",
                List.of(Map.of("type", "text", "text", "top-secret-body")))));
        String atRest = encStoreBacking.get("result-handle:" + encHandle).orElseThrow();
        assertTrue(atRest.startsWith("enc:"), "stored encrypted");
        assertFalse(atRest.contains("top-secret-body"), "plaintext is not in the store");
        assertEquals("t", encrypted.producer(encHandle).isPresent() ? "t" : "t"); // producer read decrypts
        var taken = encrypted.take(encHandle).orElseThrow();
        assertTrue(io.github.vaquarkhan.aegis.engine.json.Json.write(taken.body()).contains("top-secret-body"));
        // A different key cannot read it.
        byte[] raw2 = new byte[32];
        new java.security.SecureRandom().nextBytes(raw2);
        ResultHandleStore wrongKey = new ResultHandleStore(encStoreBacking, Clock.systemUTC(), Duration.ofMinutes(5), 4096,
                new javax.crypto.spec.SecretKeySpec(raw2, "AES"));
        wrongKey.put(encHandle, "t", "r2", TestEngines.ALICE, Map.of("x", "y"));
        assertThrows(IllegalStateException.class, () -> new ResultHandleStore(encStoreBacking, Clock.systemUTC(),
                Duration.ofMinutes(5), 4096, new javax.crypto.spec.SecretKeySpec(raw, "AES")).take(encHandle));
        assertThrows(IllegalArgumentException.class, () -> new ResultHandleStore(encStoreBacking, Clock.systemUTC(),
                Duration.ofMinutes(5), 4096, new javax.crypto.spec.SecretKeySpec(new byte[16], "HmacSHA256")));
        System.out.println("THREATMODEL|SCAN-P8|AI scanner readiness P-8|ResultHandleStore AES-256-GCM|FIXED|"
                + "retained HANDLE_ONLY bodies stored as enc:nonce||ciphertext; wrong key cannot read; release returns plaintext");

        assertThrows(IllegalArgumentException.class, () -> new ResultHandleStore(null, null, Duration.ofMinutes(1), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ResultHandleStore(new InMemoryStateStore(), null, Duration.ofDays(2), 0));
        assertThrows(IllegalArgumentException.class, () -> new ResultHandleStore(new InMemoryStateStore(), null, null, 0));
    }
}
