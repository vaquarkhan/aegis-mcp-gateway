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

import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.json.JsonLimits;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.model.Digests;
import io.github.vaquarkhan.aegis.engine.spi.SubjectToken;
import io.github.vaquarkhan.aegis.mcp.http.BoundedHttp;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Per-caller backend credentials by OAuth 2.0 Token Exchange (RFC 8693). The caller's verified
 * inbound access token (see {@link SubjectToken}) is sent to the identity provider's token endpoint,
 * with the gateway's own client credentials, and the provider returns a token for the same user that
 * is valid for this backend's audience. The backend therefore sees the user, not a shared gateway
 * account, and the inbound token itself never leaves for the backend (no token passthrough).
 *
 * <p>Fail-closed: no bound subject token, a non-200 answer, a body without {@code access_token}, an
 * unexpected token type, or an issued JWT whose {@code sub} is not the caller's subject all throw, and
 * the engine denies the call with BACKEND_ERROR. Nothing falls back to a static credential.
 *
 * <p>Caching: exchanged tokens are cached per subject token (keyed by its SHA-256, never the token)
 * until 30 seconds before {@code expires_in}, at most one hour, in a bounded LRU map. Nothing here is
 * logged with a token in it.
 */
public final class TokenExchangeCredentials {

    public static final String GRANT = "urn:ietf:params:oauth:grant-type:token-exchange";
    public static final String ACCESS_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token";
    private static final System.Logger LOG = System.getLogger(TokenExchangeCredentials.class.getName());
    private static final int MAX_CACHE = 10_000;
    private static final long SKEW_SECONDS = 30;
    private static final long MAX_TTL_SECONDS = 3600;

    /** Why an exchange did not produce a credential; the engine reports only this class name. */
    public static final class TokenExchangeException extends IOException {
        private static final long serialVersionUID = 1L;

        public TokenExchangeException(String message) {
            super(message);
        }
    }

    /** Settings of one source's exchange. Secrets are values already resolved from {@code secrets.source}. */
    public record Settings(URI tokenEndpoint, String clientId, String clientSecret, String audience, String resource,
                           String scope, Duration timeout, boolean checkSubject) {
        @Override
        public String toString() {
            return "TokenExchange[" + tokenEndpoint + ", client=" + clientId + ", audience=" + audience + "]";
        }
    }

    private record Cached(String token, Instant until) {}

    private final String sourceId;
    private final Settings settings;
    private final Clock clock;
    private final HttpClient http;
    private final Map<String, Cached> cache = new LinkedHashMap<>(64, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > MAX_CACHE;
        }
    };

    public TokenExchangeCredentials(String sourceId, Settings settings, Clock clock) {
        this.sourceId = sourceId;
        this.settings = settings;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.http = HttpClient.newBuilder().connectTimeout(settings.timeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public URI tokenEndpoint() {
        return settings.tokenEndpoint();
    }

    /** Headers for one call by {@code caller}, exchanging the bound subject token when nothing valid is cached. */
    public Map<String, String> headers(CallerIdentity caller) throws IOException, InterruptedException {
        String subject = SubjectToken.current().orElseThrow(() -> fail(
                "no verified caller token is bound to this request (token exchange needs a JWT-authenticated caller on the own host)"));
        String key = Digests.sha256Hex(subject);
        Instant now = clock.instant();
        synchronized (cache) {
            Cached c = cache.get(key);
            if (c != null && c.until().isAfter(now)) {
                return Map.of("Authorization", "Bearer " + c.token());
            }
        }
        StringJoiner form = new StringJoiner("&");
        form.add("grant_type=" + enc(GRANT));
        form.add("subject_token=" + enc(subject));
        form.add("subject_token_type=" + enc(ACCESS_TOKEN_TYPE));
        form.add("requested_token_type=" + enc(ACCESS_TOKEN_TYPE));
        if (settings.audience() != null) {
            form.add("audience=" + enc(settings.audience()));
        }
        if (settings.resource() != null) {
            form.add("resource=" + enc(settings.resource()));
        }
        if (settings.scope() != null) {
            form.add("scope=" + enc(settings.scope()));
        }
        // RFC 6749 section 2.3.1: client id and secret are form-encoded before Basic encoding.
        String basic = Base64.getEncoder().encodeToString(
                (enc(settings.clientId()) + ":" + enc(settings.clientSecret())).getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(settings.tokenEndpoint()).timeout(settings.timeout())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .header("Authorization", "Basic " + basic)
                .POST(HttpRequest.BodyPublishers.ofString(form.toString(), StandardCharsets.UTF_8)).build();
        BoundedHttp.Result r = BoundedHttp.send(http, req, 64 * 1024, settings.timeout().toMillis());
        Map<String, Object> body;
        try {
            body = Json.parseObject(r.body(), JsonLimits.DEFAULT);
        } catch (RuntimeException e) {
            throw fail("the token endpoint answered HTTP " + r.status() + " without a JSON object");
        }
        if (r.status() != 200) {
            Object error = body.get("error");
            throw fail("the token endpoint refused the exchange: HTTP " + r.status()
                    + (error instanceof String s ? " " + safe(s) : ""));
        }
        if (!(body.get("access_token") instanceof String token) || token.isBlank()) {
            throw fail("the token endpoint answer has no access_token");
        }
        Object type = body.get("token_type");
        String tokenType = type instanceof String s ? s.toLowerCase(Locale.ROOT) : "";
        if (!tokenType.equals("bearer") && !(tokenType.equals("n_a") && ACCESS_TOKEN_TYPE.equals(body.get("issued_token_type")))) {
            throw fail("the token endpoint issued token_type " + safe(String.valueOf(type)) + ", not a bearer access token");
        }
        if (settings.checkSubject()) {
            checkSubject(token, caller);
        }
        long ttl = body.get("expires_in") instanceof Number n ? n.longValue() : 60;
        long keep = Math.min(ttl, MAX_TTL_SECONDS) - SKEW_SECONDS;
        if (keep > 0) {
            synchronized (cache) {
                cache.put(key, new Cached(token, now.plusSeconds(keep)));
            }
        }
        return Map.of("Authorization", "Bearer " + token);
    }

    /**
     * When the issued token is a JWT, its {@code sub} must be the caller's subject: an identity provider
     * misconfiguration that returns a service account token must not silently act as someone else.
     * An opaque token cannot be read, so this check is skipped for it.
     */
    private void checkSubject(String token, CallerIdentity caller) throws TokenExchangeException {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return;
        }
        Map<String, Object> claims;
        try {
            claims = Json.parseObject(Base64.getUrlDecoder().decode(parts[1]), JsonLimits.DEFAULT);
        } catch (RuntimeException e) {
            return; // not a JWT after all
        }
        Object sub = claims.get("sub");
        if (sub != null && caller != null && !sub.equals(caller.effectiveSubject())) {
            throw fail("the exchanged token names a different subject than the caller");
        }
    }

    private TokenExchangeException fail(String why) {
        LOG.log(System.Logger.Level.WARNING, "token exchange for source " + sourceId + " failed: " + why);
        return new TokenExchangeException(why);
    }

    private static String safe(String s) {
        String t = s.replaceAll("[^A-Za-z0-9_.:-]", "");
        return t.length() > 64 ? t.substring(0, 64) : t;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return "TokenExchangeCredentials[" + sourceId + ", " + settings + "]";
    }
}
