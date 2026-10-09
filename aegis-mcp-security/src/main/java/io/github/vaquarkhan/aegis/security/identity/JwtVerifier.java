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

import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.json.JsonException;
import io.github.vaquarkhan.aegis.engine.json.JsonLimits;
import io.github.vaquarkhan.aegis.engine.json.JsonValues;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.mcp.http.McpHttpRequest;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * JWT bearer validation for the gateway's own front door (JDK crypto only).
 *
 * <ul>
 *   <li>Algorithms are an explicit allow list (RS256, ES256, HS256); {@code none} and any algorithm
 *       not configured are rejected, and HS256 is only accepted when a shared secret is configured
 *       (no RS/HS key-confusion).</li>
 *   <li>{@code iss} must equal the configured issuer, {@code aud} must contain the configured
 *       audience (MCP authorization: tokens must be audience-bound to this server), {@code exp} is
 *       required, {@code nbf}/{@code iat} honoured with bounded skew.</li>
 *   <li>JWKS: fetched with a bounded body; keys are cached; an unknown {@code kid} triggers at most
 *       one refresh per cool-down; when refresh fails the cached set is used only until it expires,
 *       then validation fails closed (0.1 kept a stale key after a failed refresh and read JWKS unbounded).</li>
 *   <li>RFC 8693 {@code act} claims become the audit actor chain.</li>
 *   <li>Granted scopes ({@code scope}, else {@code scp}) are retained as immutable verified caller
 *       metadata for route admission and optional operator-configured action scopes. Raw token and
 *       claim material never enter {@link CallerIdentity}.</li>
 * </ul>
 */
public final class JwtVerifier implements IdentityResolver {

    private static final System.Logger LOG = System.getLogger(JwtVerifier.class.getName());
    private static final Base64.Decoder B64 = Base64.getUrlDecoder();
    private static final JsonLimits LIMITS = JsonLimits.DEFAULT.withMaxBytes(16 * 1024).withMaxDepth(8);

    /** Configuration. */
    public record Settings(String issuer, String audience, Set<String> algorithms, byte[] hmacSecret, URI jwksUri,
                           String groupsClaim, String subjectClaim, long clockSkewSeconds, long jwksTtlSeconds,
                           long jwksRefreshCooldownSeconds) {
        public Settings {
            if (issuer == null || issuer.isBlank() || audience == null || audience.isBlank()) {
                throw new IllegalArgumentException("jwt issuer and audience are required");
            }
            algorithms = Set.copyOf(algorithms);
            if (algorithms.contains("HS256") && (hmacSecret == null || hmacSecret.length < 32)) {
                throw new IllegalArgumentException("HS256 requires a shared secret of at least 32 bytes");
            }
            if ((algorithms.contains("RS256") || algorithms.contains("ES256")) && jwksUri == null) {
                throw new IllegalArgumentException("RS256/ES256 require a jwksUri");
            }
            groupsClaim = groupsClaim == null ? "groups" : groupsClaim;
            subjectClaim = subjectClaim == null ? "sub" : subjectClaim;
        }

        @Override
        public String toString() {
            return "Settings[issuer=" + issuer + ", audience=" + audience + ", algorithms=" + algorithms + ", jwks=" + jwksUri + "]";
        }
    }

    private final Settings s;
    private final Clock clock;
    private final HttpClient http;
    private volatile Map<String, PublicKey> keys = Map.of();
    private volatile long keysFetchedAt = -1;
    private volatile long lastRefreshAttempt = -1;
    private final ConcurrentHashMap<String, Boolean> warned = new ConcurrentHashMap<>();

    public JwtVerifier(Settings settings, Clock clock) {
        this.s = settings;
        this.clock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public CallerIdentity resolve(McpHttpRequest request) {
        Authenticated a = authenticate(request);
        return a == null ? null : a.caller();
    }

    @Override
    public Authenticated authenticate(McpHttpRequest request) {
        String token = IdentityResolver.bearer(request);
        if (token == null) {
            return null;
        }
        try {
            return verifyWithScopes(token);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "jwt rejected: " + e.getMessage());
            return null;
        }
    }

    @Override
    public boolean carriesScopes() {
        return true;
    }

    /** @throws IllegalArgumentException with a reason when the token is not acceptable */
    public CallerIdentity verify(String token) {
        return verifyWithScopes(token).caller();
    }

    /**
     * Verifies the token and returns the caller with its granted scopes. Scopes come from the
     * RFC 9068 {@code scope} claim (space-delimited). Only when {@code scope} is absent is the
     * {@code scp} claim (array or space-delimited string, used by some identity providers) read; the
     * two are never merged, so a token cannot widen its grant by carrying both.
     *
     * @throws IllegalArgumentException with a reason when the token is not acceptable
     */
    public Authenticated verifyWithScopes(String token) {
        if (token.length() > 16 * 1024) {
            throw new IllegalArgumentException("token too large");
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("not a compact JWS");
        }
        Map<String, Object> header = decode(parts[0]);
        String alg = JsonValues.string(header, "alg");
        if (alg == null || !s.algorithms().contains(alg)) {
            throw new IllegalArgumentException("algorithm not allowed");
        }
        if (header.containsKey("crit")) {
            throw new IllegalArgumentException("critical header extensions are not supported");
        }
        byte[] signingInput = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
        byte[] sig;
        try {
            sig = B64.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("bad signature encoding");
        }
        if (!signatureValid(alg, JsonValues.string(header, "kid"), signingInput, sig)) {
            throw new IllegalArgumentException("signature invalid");
        }
        Map<String, Object> claims = decode(parts[1]);
        long now = clock.instant().getEpochSecond();
        if (!s.issuer().equals(claims.get("iss"))) {
            throw new IllegalArgumentException("issuer mismatch");
        }
        Object aud = claims.get("aud");
        boolean audOk = s.audience().equals(aud) || (aud instanceof List<?> l && l.contains(s.audience()));
        if (!audOk) {
            throw new IllegalArgumentException("audience mismatch");
        }
        Long exp = JsonValues.integral(claims.get("exp"));
        if (exp == null || now > exp + s.clockSkewSeconds()) {
            throw new IllegalArgumentException("token expired or has no exp");
        }
        Long nbf = JsonValues.integral(claims.get("nbf"));
        if (nbf != null && now + s.clockSkewSeconds() < nbf) {
            throw new IllegalArgumentException("token not yet valid");
        }
        Long iat = JsonValues.integral(claims.get("iat"));
        if (iat != null && iat > now + s.clockSkewSeconds()) {
            throw new IllegalArgumentException("token issued in the future");
        }
        String sub = JsonValues.string(claims, s.subjectClaim());
        if (sub == null || sub.isBlank()) {
            throw new IllegalArgumentException("subject claim missing");
        }
        Set<String> groups = new LinkedHashSet<>();
        Object g = claims.get(s.groupsClaim());
        if (g instanceof String gs) {
            for (String x : gs.trim().split("\\s+")) {
                if (!x.isEmpty()) {
                    groups.add(x);
                }
            }
        } else if (g instanceof List<?> gl) {
            for (Object o : gl) {
                if (o instanceof String x) {
                    groups.add(x);
                }
            }
        }
        List<String> chain = new ArrayList<>();
        Map<String, Object> act = JsonValues.object(claims, "act");
        for (int depth = 0; act != null && depth < 8; depth++) {
            String a = JsonValues.string(act, "sub");
            if (a != null) {
                chain.add(a);
            }
            act = JsonValues.object(act, "act");
        }
        // With delegation (RFC 8693), sub is the subject acted for and act.sub is the acting party.
        String actor = chain.isEmpty() ? sub : chain.get(0);
        Set<String> verifiedScopes = scopes(claims.containsKey("scope") ? claims.get("scope") : claims.get("scp"));
        return new Authenticated(new CallerIdentity(actor, sub, groups, s.issuer(), "jwt:" + alg, chain, verifiedScopes),
                verifiedScopes);
    }

    private static Set<String> scopes(Object claim) {
        Set<String> out = new LinkedHashSet<>();
        if (claim instanceof String str) {
            for (String x : str.trim().split(" +")) {
                if (!x.isEmpty()) {
                    out.add(x);
                }
            }
        } else if (claim instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String x && !x.isEmpty() && x.indexOf(' ') < 0) {
                    out.add(x);
                }
            }
        }
        return out;
    }

    private boolean signatureValid(String alg, String kid, byte[] input, byte[] sig) {
        try {
            switch (alg) {
                case "HS256": {
                    Mac mac = Mac.getInstance("HmacSHA256");
                    mac.init(new SecretKeySpec(s.hmacSecret(), "HmacSHA256"));
                    return java.security.MessageDigest.isEqual(mac.doFinal(input), sig);
                }
                case "RS256":
                case "ES256": {
                    PublicKey k = key(kid, alg);
                    if (k == null) {
                        return false;
                    }
                    Signature v = Signature.getInstance("RS256".equals(alg) ? "SHA256withRSA" : "SHA256withECDSAinP1363Format");
                    v.initVerify(k);
                    v.update(input);
                    return v.verify(sig);
                }
                default:
                    return false;
            }
        } catch (Exception e) {
            return false;
        }
    }

    private boolean keysExpired(long now) {
        return keysFetchedAt < 0 || now - keysFetchedAt > s.jwksTtlSeconds() * 1000;
    }

    private boolean cooldownOver(long now) {
        return lastRefreshAttempt < 0 || now - lastRefreshAttempt >= s.jwksRefreshCooldownSeconds() * 1000;
    }

    private PublicKey key(String kid, String alg) {
        long now = clock.millis();
        boolean expired = keysExpired(now);
        PublicKey k = kid == null ? null : keys.get(kid + "|" + alg);
        if (k != null && !expired) {
            return k;
        }
        // The cool-down applies even when the set has expired, so an IdP outage cannot turn every
        // token check into a blocking fetch (those checks fail closed quickly instead).
        if (cooldownOver(now)) {
            refresh(now);
            k = kid == null ? null : keys.get(kid + "|" + alg);
        }
        if (keysExpired(now)) {
            if (warned.putIfAbsent("stale", Boolean.TRUE) == null) {
                LOG.log(System.Logger.Level.WARNING, "JWKS could not be refreshed and the cached set expired: rejecting tokens");
            }
            return null; // fail closed: never validate with an expired key set
        }
        return k;
    }

    private synchronized void refresh(long now) {
        if (!cooldownOver(now)) {
            return; // another thread refreshed while we waited for the lock
        }
        lastRefreshAttempt = now;
        try {
            io.github.vaquarkhan.aegis.mcp.http.BoundedHttp.Result r = io.github.vaquarkhan.aegis.mcp.http.BoundedHttp.send(
                    http, HttpRequest.newBuilder(s.jwksUri()).timeout(Duration.ofSeconds(5)).GET().build(), 256 * 1024, 5_000);
            byte[] body = r.body();
            if (r.status() != 200) {
                throw new IllegalStateException("JWKS HTTP " + r.status());
            }
            Map<String, PublicKey> fresh = new ConcurrentHashMap<>();
            for (Object o : JsonValues.asList(Json.parseObject(body, JsonLimits.DEFAULT.withMaxBytes(256 * 1024)).get("keys"))) {
                Map<String, Object> jwk = JsonValues.asObject(o);
                String kid = JsonValues.string(jwk, "kid");
                String use = JsonValues.string(jwk, "use");
                if (kid == null || (use != null && !"sig".equals(use))) {
                    continue;
                }
                String kty = JsonValues.string(jwk, "kty");
                if ("RSA".equals(kty)) {
                    BigInteger n = new BigInteger(1, B64.decode(JsonValues.string(jwk, "n")));
                    if (n.bitLength() < 2048) {
                        continue; // weak RSA keys are ignored (NIST SP 800-57 minimum)
                    }
                    PublicKey pk = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                            n, new BigInteger(1, B64.decode(JsonValues.string(jwk, "e")))));
                    fresh.put(kid + "|RS256", pk);
                } else if ("EC".equals(kty) && "P-256".equals(JsonValues.string(jwk, "crv"))) {
                    AlgorithmParameters p = AlgorithmParameters.getInstance("EC");
                    p.init(new ECGenParameterSpec("secp256r1"));
                    ECParameterSpec spec = p.getParameterSpec(ECParameterSpec.class);
                    ECPoint point = new ECPoint(new BigInteger(1, B64.decode(JsonValues.string(jwk, "x"))),
                            new BigInteger(1, B64.decode(JsonValues.string(jwk, "y"))));
                    fresh.put(kid + "|ES256", KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, spec)));
                }
            }
            keys = Map.copyOf(fresh);
            keysFetchedAt = now;
            warned.clear();
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "JWKS refresh failed: " + e.getClass().getSimpleName());
        }
    }

    private static Map<String, Object> decode(String part) {
        try {
            return Json.parseObject(B64.decode(part), LIMITS);
        } catch (IllegalArgumentException | JsonException e) {
            throw new IllegalArgumentException("malformed token segment");
        }
    }
}
