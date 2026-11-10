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
 * Threat-model reproduction suite (authentication layer). Replays classic JWT bypass techniques used
 * against MCP front doors. Verdict lines: THREATMODEL|id|refs|class|verdict|detail
 */
package io.github.vaquarkhan.aegis.security.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.vaquarkhan.aegis.engine.json.Json;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ThreatModelCveAuthTest {

  static void verdict(String id, String refs, String cls, String verdict, String detail) {
    System.out.println("THREATMODEL|" + id + "|" + refs + "|" + cls + "|" + verdict + "|" + detail);
  }

  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

  private static String unsigned(Map<String, Object> header, Map<String, Object> claims) {
    return B64.encodeToString(Json.writeBytes(header))
        + "."
        + B64.encodeToString(Json.writeBytes(claims))
        + ".";
  }

  @Test
  void tm18_jwtAlgorithmConfusionAndClaimTampering_areRejected() throws Exception {
    JwtVerifierTest.MutableClock clock = new JwtVerifierTest.MutableClock();
    JwtVerifier v = JwtVerifierTest.hs(clock);
    Map<String, Object> admin = JwtVerifierTest.claims();
    admin.put("groups", List.of("approvers", "admins"));

    // 1. alg=none with an empty signature.
    assertThrows(
        IllegalArgumentException.class,
        () -> v.verify(unsigned(Map.of("alg", "none", "typ", "JWT"), admin)));
    // 2. Right algorithm, wrong key (attacker-signed).
    String forged =
        JwtVerifierTest.sign(
            Map.of("alg", "HS256"),
            admin,
            "attacker-key-0123456789abcdef0123456789".getBytes(StandardCharsets.UTF_8));
    assertThrows(IllegalArgumentException.class, () -> v.verify(forged));
    // 3. Algorithm not in the operator's allow list (RS256 against an HS256-only gateway), kid
    // pointing anywhere.
    assertThrows(
        IllegalArgumentException.class,
        () -> v.verify(unsigned(Map.of("alg", "RS256", "kid", "../../etc/passwd"), admin)));
    // 4. Valid signature but audience for a different resource (token replayed from another MCP
    // server).
    Map<String, Object> otherAud = JwtVerifierTest.claims();
    otherAud.put("aud", "some-other-mcp-server");
    assertThrows(IllegalArgumentException.class, () -> v.verify(JwtVerifierTest.hs256(otherAud)));
    // 5. Expired token.
    Map<String, Object> expired = JwtVerifierTest.claims();
    expired.put("exp", JwtVerifierTest.NOW - 3600);
    assertThrows(IllegalArgumentException.class, () -> v.verify(JwtVerifierTest.hs256(expired)));
    // 6. Key-confusion: an RSA-only gateway receiving HS256 signed with the public key bytes.
    var kp = KeyPairGenerator.getInstance("RSA").generateKeyPair();
    byte[] pub = ((RSAPublicKey) kp.getPublic()).getEncoded();
    JwtVerifier rsOnly =
        new JwtVerifier(
            new JwtVerifier.Settings(
                "https://idp.test",
                "aegis",
                Set.of("RS256"),
                null,
                java.net.URI.create("https://idp.test/jwks"),
                null,
                null,
                60,
                3600,
                30),
            clock);
    String confused = JwtVerifierTest.sign(Map.of("alg", "HS256"), admin, pub);
    assertThrows(IllegalArgumentException.class, () -> rsOnly.verify(confused));
    // Control: a correctly signed token is accepted with its groups.
    assertEquals(
        Set.of("analysts"), v.verify(JwtVerifierTest.hs256(JwtVerifierTest.claims())).groups());

    // 7. Hardening added by this review: a plaintext JWKS URL (key substitution by an on-path
    // attacker) is refused at config time.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new JwtVerifier.Settings(
                "https://idp.test",
                "aegis",
                Set.of("RS256"),
                null,
                java.net.URI.create("http://idp.internal/jwks"),
                null,
                null,
                60,
                3600,
                30));
    new JwtVerifier.Settings(
        "https://idp.test",
        "aegis",
        Set.of("RS256"),
        null,
        java.net.URI.create("http://127.0.0.1:8443/jwks"),
        null,
        null,
        60,
        3600,
        30); // loopback stays allowed for tests

    verdict(
        "TM-18",
        "OWASP MCP07 (insufficient authn); CVE-2025-6514 class (trusting attacker-controlled auth metadata); JWT alg-confusion CVE family",
        "JWT forgery: alg=none, wrong key, alg not allowed, aud replay, expiry, RSA/HMAC confusion",
        "BLOCKED",
        "algorithm allow-list from config, per-alg key namespace, iss/aud/exp/nbf enforced, signature checked before claims");
  }
}
