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
package io.github.vaquarkhan.aegis.security.perimeter;

import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.mcp.http.McpHttpRequest;
import io.github.vaquarkhan.aegis.security.identity.IdentityResolver;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Transport-neutral admission control for an own Aegis listener.
 *
 * <p>It deliberately stops at route admission. An {@link Admission#allowed()} result only says that a
 * verified identity may reach the MCP, admin or metrics route. The shared MCP handler and governance
 * engine still apply origin admission, tool visibility, typed-resource policy, approval, receipt and
 * every other action control. This separation prevents a route group from becoming an alternate policy
 * engine.
 */
public final class OwnServerPerimeter {

    /** Routes that have distinct pre-dispatch group admission. */
    public enum RouteGroup { MCP, ADMIN, METRICS }

    /** Result with a safe public HTTP response shape. Authentication reasons never reach callers. */
    public record Admission(CallerIdentity caller, int status, String publicMessage, Map<String, String> headers) {
        public boolean allowed() {
            return caller != null;
        }

        static Admission allowed(CallerIdentity caller) {
            return new Admission(caller, 0, null, Map.of());
        }

        static Admission unauthorized(String challenge) {
            return new Admission(null, 401, "authentication required", Map.of("WWW-Authenticate", challenge));
        }

        static Admission forbidden() {
            return new Admission(null, 403, "caller is not allowed to reach this route", Map.of());
        }

        static Admission insufficientScope(String challenge) {
            return new Admission(null, 403, "token lacks a required scope", Map.of("WWW-Authenticate", challenge));
        }
    }

    /** Result of CORS preflight evaluation, ready for an HTTP binding to serialize. */
    public record Preflight(boolean allowed, Map<String, String> headers) {
        static Preflight rejected() {
            return new Preflight(false, Map.of());
        }
    }

    private final OwnServerSecuritySettings settings;

    public OwnServerPerimeter(OwnServerSecuritySettings settings) {
        this.settings = settings;
    }

    /**
     * Authenticates and checks route membership before a binding reads a request body. Callers must pass
     * a header-only {@link McpHttpRequest}; the identity implementations need no request body.
     */
    public Admission admit(RouteGroup route, McpHttpRequest headerOnlyRequest) {
        IdentityResolver.Authenticated authenticated = settings.identity().authenticate(headerOnlyRequest);
        if (authenticated == null) {
            return Admission.unauthorized(settings.challenge(route));
        }
        CallerIdentity caller = authenticated.caller();
        // Scope minimization (MCP authorization): a token granted less than the route needs is refused
        // with a step-up challenge. Every required scope must be present; none is implied by another.
        if (!authenticated.scopes().containsAll(settings.requiredScopes(route))) {
            return Admission.insufficientScope(settings.insufficientScopeChallenge(route));
        }
        Set<String> groups = switch (route) {
            case MCP -> settings.mcpGroups();
            case ADMIN -> settings.adminGroups();
            case METRICS -> settings.metricsGroups();
        };
        return groups.isEmpty() || caller.inAnyGroup(groups) ? Admission.allowed(caller) : Admission.forbidden();
    }

    /** Exact-origin CORS preflight. It never authenticates, buffers a body or loosens route admission. */
    public Preflight preflight(String origin, String requestedMethod, String requestedHeaders) {
        OwnServerSecuritySettings.CorsPolicy cors = settings.cors();
        if (!cors.enabled() || !cors.allowsOrigin(origin) || requestedMethod == null
                || !cors.allowedMethods().contains(requestedMethod.toUpperCase(Locale.ROOT))
                || !requestedHeadersAllowed(requestedHeaders, cors.allowedHeaders())) {
            return Preflight.rejected();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Access-Control-Allow-Origin", origin);
        headers.put("Access-Control-Allow-Methods", String.join(", ", cors.allowedMethods()));
        headers.put("Access-Control-Allow-Headers", String.join(", ", cors.allowedHeaders()));
        headers.put("Access-Control-Max-Age", Long.toString(cors.maxAgeSeconds()));
        headers.put("Vary", "Origin");
        if (cors.allowCredentials()) {
            headers.put("Access-Control-Allow-Credentials", "true");
        }
        return new Preflight(true, Map.copyOf(headers));
    }

    /** Response CORS headers for an allowed browser origin, empty for non-browser or rejected origins. */
    public Map<String, String> responseCorsHeaders(String origin) {
        OwnServerSecuritySettings.CorsPolicy cors = settings.cors();
        if (!cors.enabled() || !cors.allowsOrigin(origin)) {
            return Map.of();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Access-Control-Allow-Origin", origin);
        headers.put("Vary", "Origin");
        if (cors.allowCredentials()) {
            headers.put("Access-Control-Allow-Credentials", "true");
        }
        if (!cors.exposedHeaders().isEmpty()) {
            headers.put("Access-Control-Expose-Headers", String.join(", ", cors.exposedHeaders()));
        }
        return Map.copyOf(headers);
    }

    private static boolean requestedHeadersAllowed(String requested, Set<String> allowed) {
        if (requested == null || requested.isBlank()) {
            return true;
        }
        for (String header : requested.split(",")) {
            if (!allowed.contains(header.trim().toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }
}
