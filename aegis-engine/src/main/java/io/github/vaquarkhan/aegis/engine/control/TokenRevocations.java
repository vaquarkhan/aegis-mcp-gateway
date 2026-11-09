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
package io.github.vaquarkhan.aegis.engine.control;

import io.github.vaquarkhan.aegis.engine.model.Digests;
import io.github.vaquarkhan.aegis.engine.spi.StateStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Gateway-side bearer token revocation, kept in the governance {@link StateStore} so every replica
 * sees it. Two kinds of entry:
 * <ul>
 *   <li><b>One token</b> by issuer and {@code jti}, kept until the token would have expired anyway.</li>
 *   <li><b>One subject</b> by issuer and {@code sub}: every token for that subject issued at or before
 *       the cutoff ({@code iat}) is refused, so "sign this user out everywhere" works without
 *       knowing their token ids. A token without {@code iat} cannot prove it is newer, so it is
 *       refused while a subject entry exists.</li>
 * </ul>
 * Keys hold only SHA-256 digests of issuer, id and subject, never the token itself.
 */
public final class TokenRevocations {

    /** Upper bound on how long an entry is kept (an access token should never live this long). */
    public static final Duration MAX_RETENTION = Duration.ofDays(30);

    private static final String TOKEN = "revoked:jti:";
    private static final String SUBJECT = "revoked:sub:";

    private final StateStore store;
    private final Clock clock;
    private final java.util.Set<String> issuers = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public TokenRevocations(StateStore store, Clock clock) {
        if (store == null) {
            throw new IllegalArgumentException("a state store is required");
        }
        this.store = store;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * A verifier that consults this list registers the issuer it accepts. Revocation requests for any
     * other issuer are refused (O-7): otherwise a revocation could succeed and revoke nothing that any
     * verifier would ever check.
     */
    public void registerIssuer(String issuer) {
        require(issuer, "issuer");
        issuers.add(issuer);
    }

    /** True when some bound verifier consults entries for {@code issuer}. */
    public boolean acceptsIssuer(String issuer) {
        return issuer != null && issuers.contains(issuer);
    }

    private static String key(String prefix, String issuer, String value) {
        return prefix + Digests.sha256Hex(issuer + "\n" + value);
    }

    private Instant bounded(Instant until) {
        Instant now = clock.instant();
        Instant max = now.plus(MAX_RETENTION);
        if (until == null || until.isAfter(max)) {
            return max;
        }
        return until.isBefore(now) ? now.plusSeconds(60) : until;
    }

    /** Revokes one token until {@code until} (normally its {@code exp}; bounded to {@link #MAX_RETENTION}). */
    public void revokeToken(String issuer, String jti, Instant until) {
        require(issuer, "issuer");
        require(jti, "jti");
        store.put(key(TOKEN, issuer, jti), "1", bounded(until));
    }

    /**
     * Refuses every token for {@code subject} issued at or before now, for {@code retention}
     * (set it to at least the identity provider's maximum access token lifetime).
     */
    public Instant revokeSubject(String issuer, String subject, Duration retention) {
        require(issuer, "issuer");
        require(subject, "subject");
        Instant cutoff = clock.instant();
        Duration keep = retention == null || retention.isNegative() || retention.isZero() ? Duration.ofHours(24) : retention;
        store.put(key(SUBJECT, issuer, subject), Long.toString(cutoff.getEpochSecond()), bounded(cutoff.plus(keep)));
        return cutoff;
    }

    /** @return null when the token is not revoked, else the reason */
    public String check(String issuer, String jti, String subject, Long issuedAt) {
        if (jti != null && !jti.isEmpty() && store.get(key(TOKEN, issuer, jti)).isPresent()) {
            return "token revoked";
        }
        if (subject != null) {
            Optional<String> cutoff = store.get(key(SUBJECT, issuer, subject));
            if (cutoff.isPresent()) {
                long c;
                try {
                    c = Long.parseLong(cutoff.get());
                } catch (NumberFormatException e) {
                    return "subject revoked";
                }
                if (issuedAt == null || issuedAt <= c) {
                    return "subject revoked for tokens issued before " + Instant.ofEpochSecond(c);
                }
            }
        }
        return null;
    }

    private static void require(String v, String what) {
        if (v == null || v.isBlank() || v.length() > 1024) {
            throw new IllegalArgumentException(what + " is required (1 to 1024 characters)");
        }
    }
}
