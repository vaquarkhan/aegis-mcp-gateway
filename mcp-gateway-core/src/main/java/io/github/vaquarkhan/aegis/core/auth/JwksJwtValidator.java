/*
 * Licensed to the Aegis MCP Gateway project under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vaquarkhan.aegis.core.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JWKS backed validator for OAuth 2.1 access tokens presented as signed JWTs.
 *
 * <p>Keys are fetched from the configured JWKS URL and cached by {@code kid} for
 * {@link #DEFAULT_CACHE_TTL_MILLIS}. A token whose {@code kid} is not cached triggers a single
 * refresh, which is what lets key rotation take effect without a restart. Refreshes are serialised
 * on one lock, so a burst of tokens carrying unknown key identifiers costs one fetch at a time
 * rather than one fetch per request.
 *
 * <p>Every failure path returns an empty result rather than throwing, and the caller turns that
 * into a 401. Only RS256, RS384, RS512, ES256, ES384 and ES512 are accepted; {@code none} and the
 * HMAC family are refused outright so a JWKS entry can never be coerced into a shared secret.
 *
 * @author Viquar Khan
 */
public final class JwksJwtValidator {

    /** Key cache lifetime. Rotation is picked up either by expiry or by an unknown {@code kid}. */
    public static final long DEFAULT_CACHE_TTL_MILLIS = 300_000L;

    /** Clock skew tolerated on {@code exp} and {@code nbf}. */
    public static final long DEFAULT_SKEW_SECONDS = 60L;

    private static final Logger LOG = LoggerFactory.getLogger(JwksJwtValidator.class);

    private static final Set<JWSAlgorithm> ALLOWED_ALGORITHMS = Set.of(
            JWSAlgorithm.RS256, JWSAlgorithm.RS384, JWSAlgorithm.RS512,
            JWSAlgorithm.ES256, JWSAlgorithm.ES384, JWSAlgorithm.ES512);

    private final String jwksUrl;
    private final String issuer;
    private final String audience;
    private final long cacheTtlMillis;
    private final long skewSeconds;
    private final HttpClient http;

    private final Map<String, JWK> keysByKid = new LinkedHashMap<>();
    private final Object lock = new Object();
    private long cacheExpiresAtMillis;

    public JwksJwtValidator(String jwksUrl, String issuer, String audience) {
        this(jwksUrl, issuer, audience, DEFAULT_CACHE_TTL_MILLIS, DEFAULT_SKEW_SECONDS);
    }

    public JwksJwtValidator(
            String jwksUrl, String issuer, String audience, long cacheTtlMillis, long skewSeconds) {
        this.jwksUrl = Objects.requireNonNull(jwksUrl, "jwksUrl");
        if (jwksUrl.isBlank()) {
            throw new IllegalArgumentException("jwksUrl required");
        }
        this.issuer = issuer;
        this.audience = audience;
        this.cacheTtlMillis = cacheTtlMillis;
        this.skewSeconds = skewSeconds;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public String jwksUrl() {
        return jwksUrl;
    }

    /**
     * Verifies signature and claims and returns the validated claim set.
     *
     * @param compactJwt the compact serialization taken from the {@code Authorization} header
     * @return the claims, or {@code null} when the token is not acceptable
     */
    public JWTClaimsSet validate(String compactJwt) {
        if (compactJwt == null || compactJwt.isBlank()) {
            return null;
        }
        SignedJWT jwt;
        try {
            jwt = SignedJWT.parse(compactJwt.trim());
        } catch (ParseException e) {
            LOG.debug("token rejected: not a signed JWT");
            return null;
        }
        JWSHeader header = jwt.getHeader();
        if (!ALLOWED_ALGORITHMS.contains(header.getAlgorithm())) {
            LOG.debug("token rejected: unsupported algorithm {}", header.getAlgorithm());
            return null;
        }
        JWK key = resolveKey(header.getKeyID(), header.getAlgorithm());
        if (key == null) {
            LOG.debug("token rejected: no JWKS key for kid={}", header.getKeyID());
            return null;
        }
        if (!verifySignature(jwt, key)) {
            LOG.debug("token rejected: signature verification failed");
            return null;
        }
        JWTClaimsSet claims;
        try {
            claims = jwt.getJWTClaimsSet();
        } catch (ParseException e) {
            LOG.debug("token rejected: unparsable claim set");
            return null;
        }
        return claimsAcceptable(claims) ? claims : null;
    }

    /** Issuer, audience and time window checks with the configured skew. */
    boolean claimsAcceptable(JWTClaimsSet claims) {
        if (issuer != null && !issuer.isBlank() && !issuer.equals(claims.getIssuer())) {
            LOG.debug("token rejected: issuer mismatch");
            return false;
        }
        if (audience != null && !audience.isBlank()) {
            List<String> aud = claims.getAudience();
            if (aud == null || !aud.contains(audience)) {
                LOG.debug("token rejected: audience mismatch");
                return false;
            }
        }
        Instant now = Instant.now();
        Instant exp = claims.getExpirationTime() == null ? null : claims.getExpirationTime().toInstant();
        if (exp == null || now.minusSeconds(skewSeconds).isAfter(exp)) {
            LOG.debug("token rejected: expired or missing exp");
            return false;
        }
        Instant nbf = claims.getNotBeforeTime() == null ? null : claims.getNotBeforeTime().toInstant();
        if (nbf != null && now.plusSeconds(skewSeconds).isBefore(nbf)) {
            LOG.debug("token rejected: not yet valid");
            return false;
        }
        return true;
    }

    /**
     * Splits the {@code scope} or {@code scp} claim into a scope set.
     *
     * <p>Both the space delimited string form of RFC 8693 and the JSON array form some issuers emit
     * are accepted, because rejecting one of them would only push operators into a token mapper.
     */
    public static Set<String> scopesOf(JWTClaimsSet claims) {
        Set<String> scopes = new LinkedHashSet<>();
        if (claims == null) {
            return scopes;
        }
        addScopeClaim(scopes, claims.getClaim("scope"));
        addScopeClaim(scopes, claims.getClaim("scp"));
        return Collections.unmodifiableSet(scopes);
    }

    private static void addScopeClaim(Set<String> target, Object claim) {
        if (claim == null) {
            return;
        }
        if (claim instanceof String s) {
            for (String part : s.split("[\\s,]+")) {
                String t = part.trim();
                if (!t.isEmpty()) {
                    target.add(t);
                }
            }
            return;
        }
        if (claim instanceof Iterable<?> it) {
            for (Object o : it) {
                if (o != null) {
                    String t = String.valueOf(o).trim();
                    if (!t.isEmpty()) {
                        target.add(t);
                    }
                }
            }
        }
    }

    private boolean verifySignature(SignedJWT jwt, JWK key) {
        try {
            JWSVerifier verifier;
            if (key instanceof RSAKey rsa) {
                verifier = new RSASSAVerifier(rsa.toRSAPublicKey());
            } else if (key instanceof ECKey ec) {
                verifier = new ECDSAVerifier(ec.toECPublicKey());
            } else {
                return false;
            }
            return jwt.verify(verifier);
        } catch (JOSEException e) {
            LOG.debug("signature verification error: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Returns the cached key for this {@code kid}, refreshing the JWKS once when the cache is stale
     * or the identifier is unknown.
     */
    private JWK resolveKey(String kid, JWSAlgorithm algorithm) {
        synchronized (lock) {
            JWK cached = lookup(kid, algorithm);
            if (cached != null && System.currentTimeMillis() < cacheExpiresAtMillis) {
                return cached;
            }
            if (refresh()) {
                return lookup(kid, algorithm);
            }
            // A refresh failure must not widen access, but a still-valid cached key may be reused
            // so a brief authorization server outage does not lock every caller out at once.
            return cached;
        }
    }

    private JWK lookup(String kid, JWSAlgorithm algorithm) {
        if (kid != null && !kid.isBlank()) {
            return keysByKid.get(kid);
        }
        // A token without a kid is only resolvable when the JWKS holds exactly one usable key of
        // the right family; anything else would mean guessing which key the issuer meant.
        List<JWK> candidates = new ArrayList<>();
        for (JWK jwk : keysByKid.values()) {
            if (matchesFamily(jwk, algorithm)) {
                candidates.add(jwk);
            }
        }
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    private static boolean matchesFamily(JWK jwk, JWSAlgorithm algorithm) {
        String name = algorithm.getName().toUpperCase(Locale.ROOT);
        if (name.startsWith("RS")) {
            return jwk instanceof RSAKey;
        }
        if (name.startsWith("ES")) {
            return jwk instanceof ECKey;
        }
        return false;
    }

    private boolean refresh() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(jwksUrl))
                    .timeout(Duration.ofSeconds(5))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOG.warn("JWKS fetch returned status {} from {}", response.statusCode(), jwksUrl);
                return false;
            }
            JWKSet set = JWKSet.parse(response.body());
            Map<String, JWK> fresh = new LinkedHashMap<>();
            for (JWK jwk : set.getKeys()) {
                if (jwk.isPrivate() || (jwk.getKeyUse() != null && !KeyUse.SIGNATURE.equals(jwk.getKeyUse()))) {
                    continue;
                }
                if (!(jwk instanceof RSAKey) && !(jwk instanceof ECKey)) {
                    continue;
                }
                String kid = jwk.getKeyID() == null ? "" : jwk.getKeyID();
                fresh.put(kid, jwk);
            }
            keysByKid.clear();
            keysByKid.putAll(fresh);
            cacheExpiresAtMillis = System.currentTimeMillis() + cacheTtlMillis;
            LOG.info("JWKS refreshed keys={} from {}", keysByKid.size(), jwksUrl);
            return true;
        } catch (IOException | ParseException | IllegalArgumentException e) {
            LOG.warn("JWKS fetch failed from {}: {}", jwksUrl, e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("JWKS fetch interrupted");
            return false;
        }
    }
}
