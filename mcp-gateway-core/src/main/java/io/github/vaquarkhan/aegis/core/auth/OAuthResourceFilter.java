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

import com.nimbusds.jwt.JWTClaimsSet;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OAuth 2.1 protected resource filter.
 *
 * <p>Verifies the bearer JWT against the issuer JWKS, checks {@code iss}, {@code aud}, {@code exp}
 * and {@code nbf}, enforces the required scope, and publishes the resulting {@link CallerIdentity}
 * to the interceptor chain. Callers are read-only unless the token carries the configured write
 * scope, so a token minted for a broader audience cannot unlock destructive tools by accident.
 *
 * <p>Without a JWKS URL the filter cannot verify anything, so it denies every request rather than
 * admitting callers on the strength of an unverified token. That is the same fail-closed posture
 * the rest of the gateway takes: a missing security setting removes access, it does not grant it.
 *
 * @author Viquar Khan
 */
public final class OAuthResourceFilter implements Filter {

    /** Scope that grants write class tools when {@code MCP_GW_OAUTH_WRITE_SCOPE} is unset. */
    public static final String DEFAULT_WRITE_SCOPE = "aegis.write";

    private static final Logger LOG = LoggerFactory.getLogger(OAuthResourceFilter.class);

    private final String issuer;
    private final String audience;
    private final String jwksUrl;
    private final String requiredScope;
    private final String writeScope;
    private final JwksJwtValidator validator;

    public OAuthResourceFilter(String issuer, String audience, String jwksUrl, String requiredScope) {
        this(issuer, audience, jwksUrl, requiredScope, null, null);
    }

    public OAuthResourceFilter(
            String issuer, String audience, String jwksUrl, String requiredScope, String writeScope) {
        this(issuer, audience, jwksUrl, requiredScope, writeScope, null);
    }

    /**
     * Full constructor.
     *
     * @param validator pre-built validator, or {@code null} to build one from {@code jwksUrl}
     */
    public OAuthResourceFilter(
            String issuer,
            String audience,
            String jwksUrl,
            String requiredScope,
            String writeScope,
            JwksJwtValidator validator) {

        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("MCP_GW_AUTH_MODE=oauth requires MCP_GW_OAUTH_ISSUER");
        }
        if (audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("MCP_GW_AUTH_MODE=oauth requires MCP_GW_OAUTH_AUDIENCE");
        }
        this.issuer = issuer.trim();
        this.audience = audience.trim();
        this.jwksUrl = blankToNull(jwksUrl);
        this.requiredScope = blankToNull(requiredScope);
        this.writeScope = writeScope == null || writeScope.isBlank() ? DEFAULT_WRITE_SCOPE : writeScope.trim();
        if (validator != null) {
            this.validator = validator;
        } else if (this.jwksUrl != null) {
            this.validator = new JwksJwtValidator(this.jwksUrl, this.issuer, this.audience);
        } else {
            this.validator = null;
        }
        if (this.validator == null) {
            LOG.warn("OAuth resource filter has no MCP_GW_OAUTH_JWKS_URL and denies every request "
                    + "(issuer={}, audience={})", this.issuer, this.audience);
        } else {
            LOG.info("OAuth resource filter active (issuer={}, audience={}, jwks={}, requiredScope={}, "
                    + "writeScope={})",
                    this.issuer, this.audience,
                    this.jwksUrl == null ? "(injected)" : this.jwksUrl,
                    this.requiredScope == null ? "-" : this.requiredScope,
                    this.writeScope);
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;

        if (validator == null) {
            LOG.debug("oauth filter denying request: no JWKS URL configured");
            unauthorized(resp, "invalid_token");
            return;
        }
        String token = bearerToken(req.getHeader("Authorization"));
        if (token == null) {
            unauthorized(resp, "invalid_request");
            return;
        }
        Optional<CallerIdentity> identity = authenticate(token);
        if (identity.isEmpty()) {
            unauthorized(resp, "invalid_token");
            return;
        }
        CallerIdentity caller = identity.get();
        req.setAttribute(BearerAuthFilter.ATTR_CALLER, caller);
        CallerContext.set(caller);
        try {
            chain.doFilter(request, response);
        } finally {
            CallerContext.clear();
        }
    }

    /**
     * Verifies a compact JWT and maps it to a caller.
     *
     * @return the caller, or empty when the token fails verification or lacks the required scope
     */
    public Optional<CallerIdentity> authenticate(String compactJwt) {
        if (validator == null) {
            return Optional.empty();
        }
        JWTClaimsSet claims = validator.validate(compactJwt);
        if (claims == null) {
            return Optional.empty();
        }
        Set<String> scopes = JwksJwtValidator.scopesOf(claims);
        if (requiredScope != null && !scopes.contains(requiredScope)) {
            LOG.debug("token rejected: required scope {} absent", requiredScope);
            return Optional.empty();
        }
        String subject = subjectOf(claims);
        if (subject == null) {
            LOG.debug("token rejected: no sub or preferred_username claim");
            return Optional.empty();
        }
        // Token scopes become resource scopes verbatim. A token with no scope claim therefore
        // reaches only tools that bind no resource, which is the fail-closed reading of an
        // authorization server that told us nothing about what this caller may touch.
        boolean readonly = !scopes.contains(writeScope);
        return Optional.of(new CallerIdentity(subject, new LinkedHashSet<>(scopes), readonly));
    }

    /**
     * Verifies a token and returns the caller, for callers that want a failure to be loud.
     *
     * @throws IllegalArgumentException when the token is not acceptable
     */
    public CallerIdentity verifyClaims(String compactJwt) {
        return authenticate(compactJwt).orElseThrow(
                () -> new IllegalArgumentException("invalid_token"));
    }

    public String issuer() {
        return issuer;
    }

    public String audience() {
        return audience;
    }

    public String jwksUrl() {
        return jwksUrl;
    }

    /** True when the filter has a key source and can admit anyone at all. */
    public boolean verificationEnabled() {
        return validator != null;
    }

    public String writeScope() {
        return writeScope;
    }

    public Set<String> requiredScopes() {
        return requiredScope == null ? Set.of() : Set.of(requiredScope);
    }

    /**
     * Maps the token subject. {@code sub} is preferred because it is stable; {@code
     * preferred_username} is the fallback some issuers use for human callers.
     */
    private static String subjectOf(JWTClaimsSet claims) {
        String sub = trimToNull(claims.getSubject());
        if (sub != null) {
            return sub;
        }
        Object preferred = claims.getClaim("preferred_username");
        return preferred == null ? null : trimToNull(String.valueOf(preferred));
    }

    private static String bearerToken(String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            return null;
        }
        if (!authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        return trimToNull(authHeader.substring(7));
    }

    private void unauthorized(HttpServletResponse resp, String error) throws IOException {
        resp.setStatus(401);
        resp.setHeader("WWW-Authenticate",
                "Bearer realm=\"aegis\", error=\"" + error + "\", resource_metadata=\"" + issuer + "\"");
        resp.setContentType("application/json");
        resp.getOutputStream().write(("{\"error\":\"" + error + "\"}").getBytes(StandardCharsets.UTF_8));
    }

    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    private static String trimToNull(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }
}
