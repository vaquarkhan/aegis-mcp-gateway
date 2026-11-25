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
package io.github.vaquarkhan.aegis.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.engine.control.TokenRevocations;
import io.github.vaquarkhan.aegis.engine.state.InMemoryStateStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TokenRevocationsTest {

  @Test
  void registerAndCheckTokenAndSubjectRevocations() {
    Instant now = Instant.now();
    Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    InMemoryStateStore store = new InMemoryStateStore();
    TokenRevocations revocations = new TokenRevocations(store, clock);

    assertThrows(IllegalArgumentException.class, () -> new TokenRevocations(null, clock));

    assertFalse(revocations.acceptsIssuer("https://issuer.example"));
    assertFalse(revocations.acceptsIssuer(null));
    revocations.registerIssuer("https://issuer.example");
    assertTrue(revocations.acceptsIssuer("https://issuer.example"));

    assertNull(revocations.check("https://issuer.example", "jti-1", "alice", now.getEpochSecond()));

    revocations.revokeToken("https://issuer.example", "jti-1", now.plusSeconds(300));
    assertEquals(
        "token revoked",
        revocations.check("https://issuer.example", "jti-1", "alice", now.getEpochSecond()));

    // Covers bounded() null/past/max branches
    revocations.revokeToken("https://issuer.example", "jti-past", now.minusSeconds(10));
    revocations.revokeToken("https://issuer.example", "jti-max", null);
    assertEquals(
        "token revoked", revocations.check("https://issuer.example", "jti-past", null, null));
    assertEquals(
        "token revoked", revocations.check("https://issuer.example", "jti-max", null, null));

    Instant cutoff = revocations.revokeSubject("https://issuer.example", "bob", Duration.ZERO);
    assertEquals(now, cutoff);
    assertNotNull(revocations.check("https://issuer.example", "jti-other", "bob", null));
    assertNotNull(
        revocations.check("https://issuer.example", "jti-other", "bob", now.getEpochSecond()));
    assertNull(
        revocations.check(
            "https://issuer.example", "jti-other", "bob", now.plusSeconds(10).getEpochSecond()));

    assertThrows(
        IllegalArgumentException.class,
        () -> revocations.revokeToken(" ", "jti", now.plusSeconds(60)));
    assertThrows(
        IllegalArgumentException.class,
        () -> revocations.revokeToken("https://issuer.example", "x".repeat(1025), null));
  }
}
