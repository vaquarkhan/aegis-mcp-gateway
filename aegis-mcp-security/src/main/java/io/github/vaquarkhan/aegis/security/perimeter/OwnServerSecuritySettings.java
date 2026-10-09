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

import io.github.vaquarkhan.aegis.config.ConfigException;
import io.github.vaquarkhan.aegis.config.ConfigNode;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.model.Digests;
import io.github.vaquarkhan.aegis.security.identity.IdentityResolver;
import io.github.vaquarkhan.aegis.security.identity.JwtVerifier;
import io.github.vaquarkhan.aegis.security.identity.SpiffeIdentity;
import io.github.vaquarkhan.aegis.security.identity.StaticTokenIdentity;
import io.github.vaquarkhan.aegis.security.oauth.ProtectedResourceMetadata;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The {@code host:} section: listener, TLS and the gateway's own identity. Parsed strictly.
 *
 * <pre>
 * host:
 *   listen: {host: 127.0.0.1, port: 8443, mcpPath: /mcp, adminPath: /aegis/v1, threads: 32}
 *   tls: {keystore: server.p12, keystorePasswordEnv: AEGIS_KEYSTORE_PASSWORD, keystoreType: PKCS12}
 *   #    clientAuth: require + clientCaFile: bundle.pem   mutual TLS; required by identity.type spiffe
 *   identity:
 *     # type: spiffe -> spiffe: {trustDomain: example.org, ids: [{id: spiffe://example.org/agent, actor: a, groups: [..]}]}
 *     type: jwt
 *     jwt: {issuer: https://idp.example.com, audience: aegis, algorithms: [RS256],
 *           jwksUri: https://idp.example.com/jwks, groupsClaim: groups}
 *   routes: {mcpGroups: [mcp-users], adminGroups: [mcp-admins]} # empty groups means any authenticated caller
 *   #       mcpScopes / adminScopes: [..]  optional OAuth scopes the token must carry (jwt only); a valid
 *   #       token without them gets 403 insufficient_scope with the required scope in WWW-Authenticate
 *   cors:                                                   # off unless allowedOrigins is non-empty
 *     allowedOrigins: [https://console.example.com]
 *     allowedMethods: [POST, DELETE, GET]
 *     allowedHeaders: [Authorization, Content-Type, MCP-Protocol-Version, Mcp-Method, Mcp-Name]
 *     exposedHeaders: [Mcp-Session-Id]
 *     maxAgeSeconds: 600
 *     allowCredentials: false
 *   stdio: {actor: local-dev, groups: [analysts]}
 *   metrics: {path: /metrics, allowedGroups: [operators]}   # Prometheus text; authenticated like /mcp; optional requiredScopes
 *   oauth:                                                 # RFC 9728, required by MCP authorization
 *     resource: https://aegis.example.com/mcp              # canonical URI; tokens must be bound to it
 *     authorizationServers: [https://idp.example.com]
 *     scopesSupported: [mcp.read, mcp.write]
 *     documentation: https://wiki.example.com/aegis
 * </pre>
 */
public record OwnServerSecuritySettings(String host, int port, String mcpPath, String adminPath, int threads,
                                 Path keystore, char[] keystorePassword, String keystoreType,
                                 IdentityResolver identity, CallerIdentity stdioIdentity, int maxRequestBytes,
                                 String metricsPath, Set<String> metricsGroups, Set<String> mcpGroups,
                                 Set<String> adminGroups, CorsPolicy cors, ProtectedResourceMetadata oauth,
                                 Map<OwnServerPerimeter.RouteGroup, Set<String>> requiredScopes,
                                 boolean requireClientCert, Path clientCaFile) {

    public OwnServerSecuritySettings {
        requiredScopes = requiredScopes == null ? Map.of() : Map.copyOf(requiredScopes);
    }

    /** OAuth scopes a token must carry to reach {@code route}; empty means no scope requirement. */
    public Set<String> requiredScopes(OwnServerPerimeter.RouteGroup route) {
        return requiredScopes.getOrDefault(route, Set.of());
    }

    /**
     * Explicit browser policy for the gateway's own MCP/admin listener. Origins are exact values, not
     * patterns: a browser credential must never be shared with an origin merely because it ends with a
     * familiar domain. An empty origin set disables CORS responses and preflight handling.
     */
    public record CorsPolicy(Set<String> allowedOrigins, Set<String> allowedMethods, Set<String> allowedHeaders,
                             Set<String> exposedHeaders, long maxAgeSeconds, boolean allowCredentials) {
        public boolean enabled() {
            return !allowedOrigins.isEmpty();
        }

        public boolean allowsOrigin(String origin) {
            return origin != null && allowedOrigins.contains(origin);
        }
    }

    /** The {@code WWW-Authenticate} value for a 401: with {@code resource_metadata} when OAuth metadata is configured. */
    public String challenge() {
        return oauth == null ? "Bearer realm=\"aegis\"" : oauth.challenge();
    }

    /**
     * The 401 challenge for one route. When the route requires scopes, {@code scope} tells the client
     * what to request (MCP authorization, scope selection; RFC 6750 section 3).
     */
    public String challenge(OwnServerPerimeter.RouteGroup route) {
        Set<String> scopes = requiredScopes(route);
        return scopes.isEmpty() ? challenge() : challenge() + ", scope=\"" + String.join(" ", scopes) + "\"";
    }

    /**
     * The 403 challenge for a valid token that lacks a required scope (RFC 6750 {@code insufficient_scope},
     * the MCP step-up authorization flow). Scope tokens were validated at parse time, so none can break
     * out of the quoted value.
     */
    public String insufficientScopeChallenge(OwnServerPerimeter.RouteGroup route) {
        String base = "Bearer realm=\"aegis\", error=\"insufficient_scope\", scope=\""
                + String.join(" ", requiredScopes(route)) + "\"";
        return oauth == null ? base : base + ", resource_metadata=\"" + oauth.metadataUrl() + "\"";
    }

    /**
     * The 403 step-up challenge for a tool call refused because the token lacks the tool's action
     * scopes. {@code scope} names the route's scopes and the tool's, so a client that requests them
     * keeps access to the route while gaining the tool (MCP authorization scope challenge). Only
     * RFC 6749 scope tokens (NQCHAR) are named, so no value can break out of the quoted string.
     */
    public String insufficientScopeChallenge(OwnServerPerimeter.RouteGroup route, java.util.Collection<String> actionScopes) {
        Set<String> scopes = new java.util.LinkedHashSet<>(requiredScopes(route));
        for (String s : actionScopes == null ? java.util.List.<String>of() : actionScopes) {
            if (s != null && SCOPE_TOKEN.matcher(s).matches()) {
                scopes.add(s);
            }
        }
        String base = "Bearer realm=\"aegis\", error=\"insufficient_scope\", scope=\"" + String.join(" ", scopes) + "\"";
        return oauth == null ? base : base + ", resource_metadata=\"" + oauth.metadataUrl() + "\"";
    }

    private static final java.util.regex.Pattern SCOPE_TOKEN = java.util.regex.Pattern.compile("[\\x21\\x23-\\x5B\\x5D-\\x7E]+");

    public static OwnServerSecuritySettings parse(Map<String, Object> section, Path baseDir, Function<String, String> env, Clock clock) {
        ConfigNode root = new ConfigNode("config.host", section);
        ConfigNode listen = root.child("listen");
        String host = listen.string("host", "127.0.0.1");
        int port = (int) listen.integer("port", 8080);
        String mcpPath = listen.string("mcpPath", "/mcp");
        String adminPath = listen.string("adminPath", "/aegis/v1");
        int threads = (int) listen.integer("threads", 32);
        int maxRequestBytes = (int) listen.integer("maxRequestBytes", 1 << 20);

        ConfigNode tls = root.child("tls");
        Path keystore = null;
        char[] pw = null;
        String ksType = tls.string("keystoreType", "PKCS12");
        if (tls.has("keystore")) {
            keystore = baseDir.resolve(tls.requiredString("keystore"));
            String pwEnv = tls.requiredString("keystorePasswordEnv");
            String v = env.apply(pwEnv);
            if (v == null) {
                throw new ConfigException("config.host.tls.keystorePasswordEnv names " + pwEnv + " which is not set");
            }
            pw = v.toCharArray();
        }
        // Mutual TLS: the handshake itself refuses a client without a certificate that chains to clientCaFile.
        String clientAuth = tls.string("clientAuth", "none").toLowerCase(Locale.ROOT);
        if (!"none".equals(clientAuth) && !"require".equals(clientAuth)) {
            throw new ConfigException("config.host.tls.clientAuth must be none or require");
        }
        boolean requireClientCert = "require".equals(clientAuth);
        Path clientCaFile = null;
        if (requireClientCert) {
            if (keystore == null) {
                throw new ConfigException("config.host.tls.clientAuth: require needs tls.keystore (mutual TLS needs TLS)");
            }
            clientCaFile = baseDir.resolve(tls.requiredString("clientCaFile"));
            if (!java.nio.file.Files.isRegularFile(clientCaFile)) {
                throw new ConfigException("config.host.tls.clientCaFile " + clientCaFile + " does not exist");
            }
        } else if (tls.has("clientCaFile")) {
            throw new ConfigException("config.host.tls.clientCaFile is only used with clientAuth: require");
        }

        ConfigNode id = root.child("identity");
        String type = id.string("type", "jwt").toLowerCase(Locale.ROOT);
        IdentityResolver resolver;
        String jwtAudience = null;
        if ("jwt".equals(type)) {
            ConfigNode j = id.child("jwt");
            String secretEnv = j.string("hmacSecretEnv", null);
            byte[] secret = null;
            if (secretEnv != null) {
                String v = env.apply(secretEnv);
                if (v == null) {
                    throw new ConfigException("config.host.identity.jwt.hmacSecretEnv names " + secretEnv + " which is not set");
                }
                secret = v.getBytes(StandardCharsets.UTF_8);
            }
            String jwks = j.string("jwksUri", null);
            List<String> algs = j.strings("algorithms");
            jwtAudience = j.string("audience", null);
            try {
                resolver = new JwtVerifier(new JwtVerifier.Settings(j.requiredString("issuer"), j.requiredString("audience"),
                        Set.copyOf(algs.isEmpty() ? List.of("RS256") : algs), secret, jwks == null ? null : URI.create(jwks),
                        j.string("groupsClaim", "groups"), j.string("subjectClaim", "sub"), j.integer("clockSkewSeconds", 60),
                        j.integer("jwksTtlSeconds", 3600), j.integer("jwksRefreshCooldownSeconds", 30)), clock);
            } catch (IllegalArgumentException e) {
                throw new ConfigException("config.host.identity.jwt: " + e.getMessage());
            }
            id.child("static");
        } else if ("static".equals(type)) {
            List<StaticTokenIdentity.Entry> entries = new ArrayList<>();
            for (ConfigNode t : id.child("static").children("tokens")) {
                String digest;
                if (t.has("tokenEnv")) {
                    String name = t.requiredString("tokenEnv");
                    String v = env.apply(name);
                    if (v == null || v.length() < 16) {
                        throw new ConfigException(t.path() + ".tokenEnv names " + name + " which is unset or shorter than 16 characters");
                    }
                    digest = Digests.sha256Hex(v);
                } else {
                    digest = t.requiredString("sha256").toLowerCase(Locale.ROOT);
                }
                String actor = t.requiredString("actor");
                entries.add(new StaticTokenIdentity.Entry(digest,
                        CallerIdentity.of(actor, t.stringSet("groups"), "aegis-static", "static-token")));
            }
            if (entries.isEmpty()) {
                throw new ConfigException("config.host.identity.static.tokens must not be empty");
            }
            resolver = new StaticTokenIdentity(entries);
            id.child("jwt");
        } else if ("spiffe".equals(type)) {
            if (!requireClientCert) {
                throw new ConfigException("config.host.identity.type spiffe needs tls.clientAuth: require and tls.clientCaFile"
                        + " (the SPIFFE trust bundle), so only verified X.509-SVIDs reach the resolver");
            }
            ConfigNode s = id.child("spiffe");
            String trustDomain = s.requiredString("trustDomain");
            Map<String, CallerIdentity> byId = new LinkedHashMap<>();
            for (ConfigNode e : s.children("ids")) {
                String spiffeId = e.requiredString("id");
                if (byId.put(spiffeId, CallerIdentity.of(e.requiredString("actor"), e.stringSet("groups"),
                        "spiffe://" + trustDomain, "x509-svid")) != null) {
                    throw new ConfigException(e.path() + ".id duplicate SPIFFE ID " + spiffeId);
                }
            }
            if (byId.isEmpty()) {
                throw new ConfigException("config.host.identity.spiffe.ids must map at least one SPIFFE ID to an actor");
            }
            try {
                resolver = new SpiffeIdentity(trustDomain, byId);
            } catch (IllegalArgumentException e) {
                throw new ConfigException("config.host.identity.spiffe: " + e.getMessage());
            }
        } else {
            throw new ConfigException("config.host.identity.type must be jwt, static or spiffe (anonymous access is not supported)");
        }

        ConfigNode oauthNode = root.child("oauth");
        ProtectedResourceMetadata oauth = null;
        if (oauthNode.has("resource")) {
            try {
                oauth = new ProtectedResourceMetadata(URI.create(oauthNode.requiredString("resource")),
                        oauthNode.strings("authorizationServers"), oauthNode.strings("scopesSupported"),
                        oauthNode.string("documentation", null));
            } catch (IllegalArgumentException e) {
                throw new ConfigException("config.host.oauth: " + e.getMessage());
            }
            // MCP requires tokens to be audience-bound to the resource identifier. If the IdP mints a
            // different audience value, the operator must say so explicitly rather than by accident.
            boolean allowAudienceMismatch = oauthNode.bool("allowAudienceMismatch", false);
            if (jwtAudience != null && !jwtAudience.equals(oauth.resource().toString()) && !allowAudienceMismatch) {
                throw new ConfigException("config.host.oauth.resource (" + oauth.resource()
                        + ") must equal config.host.identity.jwt.audience (" + jwtAudience
                        + "), because MCP requires audience-bound tokens; set oauth.allowAudienceMismatch: true"
                        + " only if your authorization server is configured to use a different audience value");
            }
        } else {
            // Without a resource nothing is published, so any other oauth key would be silently inert.
            // Refuse it, like every other unused key, so the operator sees the metadata is not served.
            for (String k : List.of("authorizationServers", "scopesSupported", "documentation", "allowAudienceMismatch")) {
                if (oauthNode.has(k)) {
                    throw oauthNode.error(k, "has no effect without config.host.oauth.resource; set resource or remove it");
                }
            }
        }

        ConfigNode routes = root.child("routes");
        Set<String> mcpGroups = routes.stringSet("mcpGroups");
        Set<String> adminGroups = routes.stringSet("adminGroups");
        Map<OwnServerPerimeter.RouteGroup, Set<String>> requiredScopes = new EnumMap<>(OwnServerPerimeter.RouteGroup.class);
        requiredScopes.put(OwnServerPerimeter.RouteGroup.MCP, scopeSet(routes, "mcpScopes"));
        requiredScopes.put(OwnServerPerimeter.RouteGroup.ADMIN, scopeSet(routes, "adminScopes"));

        ConfigNode cors = root.child("cors");
        Set<String> corsOrigins = cors.stringSet("allowedOrigins");
        Set<String> corsMethods = upper(cors.strings("allowedMethods"));
        Set<String> corsHeaders = lower(cors.strings("allowedHeaders"));
        Set<String> corsExposed = new LinkedHashSet<>(cors.strings("exposedHeaders"));
        long corsMaxAge = cors.integer("maxAgeSeconds", 600);
        boolean corsCredentials = cors.bool("allowCredentials", false);
        if (corsOrigins.contains("*")) {
            throw cors.error("allowedOrigins", "must list exact origins; '*' is never allowed");
        }
        if (corsCredentials && corsOrigins.isEmpty()) {
            throw cors.error("allowCredentials", "needs at least one exact allowed origin");
        }
        if (corsMaxAge < 0 || corsMaxAge > 86_400) {
            throw cors.error("maxAgeSeconds", "must be between 0 and 86400");
        }
        if (!corsOrigins.isEmpty() && corsMethods.isEmpty()) {
            corsMethods = Set.of("POST", "DELETE", "GET");
        }
        if (!corsOrigins.isEmpty() && corsHeaders.isEmpty()) {
            corsHeaders = Set.of("authorization", "content-type", "mcp-protocol-version", "mcp-method", "mcp-name");
        }
        CorsPolicy corsPolicy = new CorsPolicy(corsOrigins, corsMethods, corsHeaders, corsExposed, corsMaxAge, corsCredentials);

        ConfigNode stdio = root.child("stdio");
        CallerIdentity stdioId = stdio.has("actor")
                ? CallerIdentity.of(stdio.requiredString("actor"), stdio.stringSet("groups"), "aegis-stdio", "stdio")
                : null;
        ConfigNode metrics = root.child("metrics");
        String metricsPath = metrics.string("path", "/metrics");
        Set<String> metricsGroups = metrics.stringSet("allowedGroups");
        requiredScopes.put(OwnServerPerimeter.RouteGroup.METRICS, scopeSet(metrics, "requiredScopes"));
        if (!metricsPath.startsWith("/") || metricsPath.equals(mcpPath) || metricsPath.equals(adminPath)) {
            throw new ConfigException("config.host.metrics.path must be an absolute path distinct from mcpPath and adminPath");
        }
        boolean anyScopes = requiredScopes.values().stream().anyMatch(v -> !v.isEmpty());
        if (anyScopes && !resolver.carriesScopes()) {
            // Static tokens carry no scopes: requiring one would lock every caller out, so say so now.
            throw new ConfigException("config.host: required scopes (routes.mcpScopes, routes.adminScopes,"
                    + " metrics.requiredScopes) need identity.type jwt; static tokens carry no scopes");
        }
        if (anyScopes && oauth != null && !oauth.scopesSupported().isEmpty()) {
            for (Set<String> scopes : requiredScopes.values()) {
                for (String scope : scopes) {
                    if (!oauth.scopesSupported().contains(scope)) {
                        throw new ConfigException("config.host: required scope " + scope
                                + " is not published in config.host.oauth.scopesSupported, so a client could never discover it");
                    }
                }
            }
        }
        root.finish();
        return new OwnServerSecuritySettings(host, port, mcpPath, adminPath, threads, keystore, pw, ksType, resolver, stdioId, maxRequestBytes,
                metricsPath, metricsGroups, mcpGroups, adminGroups, corsPolicy, oauth, requiredScopes, requireClientCert, clientCaFile);
    }

    /** RFC 6749 section 3.3 scope-token: printable ASCII without space, double quote or backslash. */
    private static Set<String> scopeSet(ConfigNode node, String key) {
        Set<String> out = new LinkedHashSet<>();
        for (String scope : node.strings(key)) {
            if (scope.isEmpty() || !scope.chars().allMatch(c -> c == 0x21 || (c >= 0x23 && c <= 0x5B) || (c >= 0x5D && c <= 0x7E))) {
                throw node.error(key, "entries must be RFC 6749 scope tokens (no spaces, quotes or backslashes)");
            }
            out.add(scope);
        }
        return java.util.Collections.unmodifiableSet(out);
    }

    private static Set<String> upper(List<String> values) {
        Set<String> out = new LinkedHashSet<>();
        for (String value : values) {
            if (value.isBlank() || !value.matches("[A-Za-z]+")) {
                throw new ConfigException("config.host.cors.allowedMethods must contain HTTP method tokens");
            }
            out.add(value.toUpperCase(Locale.ROOT));
        }
        return Set.copyOf(out);
    }

    private static Set<String> lower(List<String> values) {
        Set<String> out = new LinkedHashSet<>();
        for (String value : values) {
            if (value.isBlank() || !value.matches("[A-Za-z0-9-]+")) {
                throw new ConfigException("config.host.cors.allowedHeaders must contain HTTP header names");
            }
            out.add(value.toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(out);
    }
}
