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
 * Gap L2 (from the gateway comparison): JWT token ids and gateway-side revocation. A stolen token
 * must be refusable at the gateway before it expires, per token (jti) or per subject.
 * Verdict lines: THREATMODEL|id|refs|class|verdict|detail
 */
package io.github.vaquarkhan.aegis.security.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.engine.control.TokenRevocations;
import io.github.vaquarkhan.aegis.engine.state.InMemoryStateStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ThreatModelGapJwtTest {

    static void verdict(String id, String refs, String cls, String verdict, String detail) {
        System.out.println("THREATMODEL|" + id + "|" + refs + "|" + cls + "|" + verdict + "|" + detail);
    }

    private static JwtVerifier verifier(JwtVerifierTest.MutableClock clock, JwtVerifier.TokenIdPolicy policy) {
        return new JwtVerifier(new JwtVerifier.Settings("https://idp.test", "aegis", Set.of("HS256"), JwtVerifierTest.SECRET,
                null, null, null, 60, 3600, 30), clock, policy);
    }

    @Test
    void jtiIsRequiredAndValidatedWhenConfigured() throws Exception {
        JwtVerifierTest.MutableClock clock = new JwtVerifierTest.MutableClock();
        JwtVerifier strict = verifier(clock, new JwtVerifier.TokenIdPolicy(true, false));
        Map<String, Object> noJti = JwtVerifierTest.claims();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> strict.verify(JwtVerifierTest.hs256(noJti)));
        assertTrue(e.getMessage().contains("jti"), e.getMessage());
        Map<String, Object> numeric = JwtVerifierTest.claims();
        numeric.put("jti", 42);
        assertThrows(IllegalArgumentException.class, () -> strict.verify(JwtVerifierTest.hs256(numeric)));
        Map<String, Object> huge = JwtVerifierTest.claims();
        huge.put("jti", "x".repeat(257));
        assertThrows(IllegalArgumentException.class, () -> strict.verify(JwtVerifierTest.hs256(huge)));
        Map<String, Object> ok = JwtVerifierTest.claims();
        ok.put("jti", "t-1");
        assertEquals("alice", strict.verify(JwtVerifierTest.hs256(ok)).actor());
        // Default policy keeps accepting tokens without jti (IdPs that do not issue one).
        assertEquals("alice", JwtVerifierTest.hs(clock).verify(JwtVerifierTest.hs256(noJti)).actor());
        verdict("GAP-L2a", "ContextForge JWT jti parity", "JwtVerifier.TokenIdPolicy.requireJti", "FIXED",
                "token without jti, non-string jti and oversized jti refused when requireJti is set");
    }

    @Test
    void revokedTokenAndRevokedSubjectAreRefusedBeforeExpiry() throws Exception {
        JwtVerifierTest.MutableClock clock = new JwtVerifierTest.MutableClock();
        TokenRevocations list = new TokenRevocations(new InMemoryStateStore(clock, 1000), clock);

        // Fail closed: revocation required but not bound yet.
        JwtVerifier unbound = verifier(clock, new JwtVerifier.TokenIdPolicy(false, true));
        Map<String, Object> t1 = JwtVerifierTest.claims();
        t1.put("jti", "stolen-1");
        String stolen = JwtVerifierTest.hs256(t1);
        assertThrows(IllegalArgumentException.class, () -> unbound.verify(stolen));
        unbound.bindRevocations(list);
        unbound.bindRevocations(list);
        assertThrows(IllegalStateException.class,
                () -> unbound.bindRevocations(new TokenRevocations(new InMemoryStateStore(clock, 10), clock)));
        assertEquals("alice", unbound.verify(stolen).actor());

        // One token by jti.
        list.revokeToken("https://idp.test", "stolen-1", Instant.ofEpochSecond(JwtVerifierTest.NOW + 300));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> unbound.verify(stolen));
        assertEquals("token revoked", e.getMessage());
        Map<String, Object> t2 = JwtVerifierTest.claims();
        t2.put("jti", "fresh-2");
        assertEquals("alice", unbound.verify(JwtVerifierTest.hs256(t2)).actor(), "other tokens unaffected");

        // A whole subject: every token issued at or before the cutoff is refused, newer ones work.
        clock.now = clock.now.plusSeconds(10);
        list.revokeSubject("https://idp.test", "alice", Duration.ofHours(1));
        assertThrows(IllegalArgumentException.class, () -> unbound.verify(JwtVerifierTest.hs256(t2)));
        Map<String, Object> noIat = JwtVerifierTest.claims();
        noIat.remove("iat");
        assertThrows(IllegalArgumentException.class, () -> unbound.verify(JwtVerifierTest.hs256(noIat)),
                "a token that cannot prove it is newer than the cutoff is refused");
        clock.now = clock.now.plusSeconds(5);
        Map<String, Object> reissued = JwtVerifierTest.claims();
        reissued.put("iat", clock.now.getEpochSecond());
        reissued.put("exp", clock.now.getEpochSecond() + 300);
        assertEquals("alice", unbound.verify(JwtVerifierTest.hs256(reissued)).actor(), "a token issued after sign-out works");
        assertNull(list.check("https://idp.test", null, "bob", JwtVerifierTest.NOW), "other subjects unaffected");
        assertTrue(list.check("https://other-idp", "stolen-1", "x", JwtVerifierTest.NOW) == null, "issuer-scoped");

        // O-5 A: revoking an AGENT also refuses tokens where the agent only acts for someone else.
        Map<String, Object> delegated = JwtVerifierTest.claims();
        delegated.put("iat", clock.now.getEpochSecond());
        delegated.put("exp", clock.now.getEpochSecond() + 300);
        delegated.put("sub", "carol");
        delegated.put("jti", "d-1");
        delegated.put("act", Map.of("sub", "agent-7", "act", Map.of("sub", "agent-root")));
        assertEquals("agent-7", unbound.verify(JwtVerifierTest.hs256(delegated)).actor());
        clock.now = clock.now.plusSeconds(1);
        list.revokeSubject("https://idp.test", "agent-root", Duration.ofHours(1));
        IllegalArgumentException agentRevoked = assertThrows(IllegalArgumentException.class,
                () -> unbound.verify(JwtVerifierTest.hs256(delegated)));
        assertTrue(agentRevoked.getMessage().startsWith("actor "), agentRevoked.getMessage());
        verdict("SCAN-O5", "AI scanner readiness O-5", "JwtVerifier actor-chain revocation", "FIXED",
                "a subject revocation of a delegated actor refuses every token in which it acts for someone else");

        // Entries expire with the retention bound (the token would have expired anyway).
        clock.now = clock.now.plus(Duration.ofHours(2));
        assertNull(list.check("https://idp.test", null, "alice", JwtVerifierTest.NOW));
        assertThrows(IllegalArgumentException.class, () -> list.revokeToken("https://idp.test", " ", null));
        assertThrows(IllegalArgumentException.class, () -> new TokenRevocations(null, clock));
        verdict("GAP-L2b", "ContextForge token revocation parity; stolen-token replay", "TokenRevocations + JwtVerifier.bindRevocations",
                "FIXED", "jti and subject revocation refuse tokens before exp; fail closed when required and unbound; shared StateStore");
    }
}
