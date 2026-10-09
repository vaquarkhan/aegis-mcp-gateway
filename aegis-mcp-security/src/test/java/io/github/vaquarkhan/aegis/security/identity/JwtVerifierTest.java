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
package io.github.vaquarkhan.aegis.security.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.mcp.http.McpHttpRequest;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** Every rejection rule of the gateway's JWT verifier, one at a time. */
class JwtVerifierTest {

    static final byte[] SECRET = ("jwt-verifier-test-" + "secret-0123456789abcdef").getBytes(StandardCharsets.UTF_8);
    static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    static final long NOW = 1_800_000_000L;

    /** Clock that tests can move. */
    static final class MutableClock extends Clock {
        volatile Instant now = Instant.ofEpochSecond(NOW);

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static JwtVerifier hs(Clock clock) {
        return new JwtVerifier(new JwtVerifier.Settings("https://idp.test", "aegis", Set.of("HS256"), SECRET, null,
                null, null, 60, 3600, 30), clock);
    }

    static Map<String, Object> claims() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("iss", "https://idp.test");
        c.put("aud", "aegis");
        c.put("sub", "alice");
        c.put("groups", List.of("analysts"));
        c.put("iat", NOW);
        c.put("exp", NOW + 300);
        return c;
    }

    static String sign(Map<String, Object> header, Map<String, Object> claims, byte[] secret) throws Exception {
        String input = B64.encodeToString(Json.writeBytes(header)) + "." + B64.encodeToString(Json.writeBytes(claims));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return input + "." + B64.encodeToString(mac.doFinal(input.getBytes(StandardCharsets.US_ASCII)));
    }

    static String hs256(Map<String, Object> claims) throws Exception {
        return sign(Map.of("alg", "HS256", "typ", "JWT"), claims, SECRET);
    }

    static String reason(JwtVerifier v, String token) {
        return assertThrows(IllegalArgumentException.class, () -> v.verify(token)).getMessage();
    }

    @Test
    void validTokenMapsSubjectGroupsIssuerAndVerifiedScopes() throws Exception {
        Map<String, Object> withScopes = claims();
        withScopes.put("scope", "mcp.read data.read");
        CallerIdentity id = hs(new MutableClock()).verify(hs256(withScopes));
        assertEquals("alice", id.actor());
        assertEquals(Set.of("analysts"), id.groups());
        assertEquals("https://idp.test", id.issuer());
        assertEquals("jwt:HS256", id.authMethod());
        assertEquals(Set.of("mcp.read", "data.read"), id.scopes());
        assertThrows(UnsupportedOperationException.class, () -> id.scopes().add("other"));
    }

    @Test
    void issuerAudienceAndSubjectAreRequiredExactly() throws Exception {
        JwtVerifier v = hs(new MutableClock());
        Map<String, Object> c = claims();
        c.put("iss", "https://idp.test/");
        assertEquals("issuer mismatch", reason(v, hs256(c)));
        c = claims();
        c.put("aud", "other-service");
        assertEquals("audience mismatch", reason(v, hs256(c)));
        c = claims();
        c.remove("aud");
        assertEquals("audience mismatch", reason(v, hs256(c)));
        c = claims();
        c.put("aud", List.of("other", "aegis"));
        assertEquals("alice", v.verify(hs256(c)).actor(), "an audience array containing ours is accepted");
        c = claims();
        c.remove("sub");
        assertEquals("subject claim missing", reason(v, hs256(c)));
    }

    @Test
    void timeClaimsHonourBoundedSkew() throws Exception {
        MutableClock clock = new MutableClock();
        JwtVerifier v = hs(clock);
        Map<String, Object> c = claims();
        c.remove("exp");
        assertEquals("token expired or has no exp", reason(v, hs256(c)));
        c = claims();
        c.put("exp", NOW - 30);
        assertEquals("alice", v.verify(hs256(c)).actor(), "30 s past exp is inside the 60 s skew");
        c.put("exp", NOW - 61);
        assertEquals("token expired or has no exp", reason(v, hs256(c)));
        c = claims();
        c.put("nbf", NOW + 120);
        assertEquals("token not yet valid", reason(v, hs256(c)));
        c = claims();
        c.put("iat", NOW + 120);
        assertEquals("token issued in the future", reason(v, hs256(c)));
    }

    @Test
    void algorithmConfusionAndMalformedTokensAreRejected() throws Exception {
        JwtVerifier v = hs(new MutableClock());
        String none = B64.encodeToString(Json.writeBytes(Map.of("alg", "none"))) + "."
                + B64.encodeToString(Json.writeBytes(claims())) + ".";
        assertEquals("algorithm not allowed", reason(v, none));
        // RS256 is not configured here: a token claiming it is refused before any key lookup.
        assertEquals("algorithm not allowed", reason(v, sign(Map.of("alg", "RS256"), claims(), SECRET)));
        assertEquals("critical header extensions are not supported",
                reason(v, sign(Map.of("alg", "HS256", "crit", List.of("exp")), claims(), SECRET)));
        assertEquals("signature invalid", reason(v, sign(Map.of("alg", "HS256"), claims(),
                "a-different-secret-that-is-long-enough-000".getBytes(StandardCharsets.UTF_8))));
        assertEquals("not a compact JWS", reason(v, "a.b"));
        assertEquals("malformed token segment", reason(v, "!!!.e30.sig"));
        assertEquals("token too large", reason(v, "a".repeat(16 * 1024 + 1)));
        // A bearer header that is not "Bearer <token>" is simply unauthenticated.
        assertNull(v.resolve(new McpHttpRequest("POST", "", Map.of("Authorization", List.of("Basic abc")), null)));
        assertThrows(IllegalArgumentException.class, () -> new JwtVerifier.Settings("i", "a", Set.of("HS256"),
                "short".getBytes(StandardCharsets.UTF_8), null, null, null, 60, 3600, 30), "HS256 needs a 32-byte secret");
        assertThrows(IllegalArgumentException.class, () -> new JwtVerifier.Settings("i", "a", Set.of("RS256"),
                null, null, null, null, 60, 3600, 30), "RS256 needs a JWKS URI");
    }

    @Test
    void delegationAndSpaceSeparatedGroups() throws Exception {
        Map<String, Object> c = claims();
        c.put("groups", "analysts  operators");
        c.put("act", Map.of("sub", "agent-7", "act", Map.of("sub", "orchestrator")));
        CallerIdentity id = hs(new MutableClock()).verify(hs256(c));
        assertEquals("agent-7", id.actor(), "RFC 8693: the acting party is the actor");
        assertEquals("alice", id.effectiveSubject());
        assertEquals(List.of("agent-7", "orchestrator"), id.actorChain());
        assertEquals(Set.of("analysts", "operators"), id.groups());
    }

    @Test
    void es256ViaJwksWeakRsaIgnoredAndExpiredKeySetFailsClosed() throws Exception {
        KeyPairGenerator ecGen = KeyPairGenerator.getInstance("EC");
        ecGen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair ec = ecGen.generateKeyPair();
        KeyPairGenerator rsaGen = KeyPairGenerator.getInstance("RSA");
        rsaGen.initialize(1024);
        KeyPair weak = rsaGen.generateKeyPair();
        ECPublicKey ecPub = (ECPublicKey) ec.getPublic();
        RSAPublicKey weakPub = (RSAPublicKey) weak.getPublic();
        List<Object> keys = new ArrayList<>();
        keys.add(Map.of("kty", "EC", "crv", "P-256", "kid", "ec-1", "use", "sig",
                "x", B64.encodeToString(unsigned(ecPub.getW().getAffineX(), 32)),
                "y", B64.encodeToString(unsigned(ecPub.getW().getAffineY(), 32))));
        keys.add(Map.of("kty", "RSA", "kid", "weak", "n", B64.encodeToString(weakPub.getModulus().toByteArray()),
                "e", B64.encodeToString(weakPub.getPublicExponent().toByteArray())));
        byte[] jwks = Json.writeBytes(Map.of("keys", keys));
        AtomicInteger fetches = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", ex -> {
            fetches.incrementAndGet();
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, jwks.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(jwks);
            }
        });
        server.start();
        MutableClock clock = new MutableClock();
        try {
            JwtVerifier v = new JwtVerifier(new JwtVerifier.Settings("https://idp.test", "aegis", Set.of("ES256", "RS256"),
                    null, URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/jwks"), null, null, 60, 600, 30), clock);
            assertEquals("alice", v.verify(signAsym("ES256", "ec-1", ec.getPrivate(), "SHA256withECDSAinP1363Format")).actor());
            assertEquals("signature invalid", reason(v, signAsym("RS256", "weak", weak.getPrivate(), "SHA256withRSA")),
                    "RSA keys under 2048 bits are ignored");
            int before = fetches.get();
            assertEquals("signature invalid", reason(v, signAsym("ES256", "unknown", ec.getPrivate(), "SHA256withECDSAinP1363Format")));
            assertEquals(before, fetches.get(), "an unknown kid inside the cool-down does not refetch");

            server.stop(0);
            clock.now = clock.now.plusSeconds(601);
            assertEquals("signature invalid", reason(v, signAsym("ES256", "ec-1", ec.getPrivate(), "SHA256withECDSAinP1363Format")),
                    "IdP down and the cached set expired: fail closed");
        } finally {
            server.stop(0);
        }
    }

    private static String signAsym(String alg, String kid, PrivateKey key, String jca) throws Exception {
        Map<String, Object> claims = claims();
        claims.put("exp", NOW + 3600);
        String input = B64.encodeToString(Json.writeBytes(Map.of("alg", alg, "kid", kid))) + "."
                + B64.encodeToString(Json.writeBytes(claims));
        Signature s = Signature.getInstance(jca);
        s.initSign(key);
        s.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + B64.encodeToString(s.sign());
    }

    private static byte[] unsigned(BigInteger v, int len) {
        byte[] b = v.toByteArray();
        byte[] out = new byte[len];
        int copy = Math.min(len, b.length);
        System.arraycopy(b, b.length - copy, out, len - copy, copy);
        return out;
    }
}
