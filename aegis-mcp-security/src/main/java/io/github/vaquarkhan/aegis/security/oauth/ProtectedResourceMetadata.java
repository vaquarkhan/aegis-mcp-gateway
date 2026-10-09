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
package io.github.vaquarkhan.aegis.security.oauth;

import io.github.vaquarkhan.aegis.engine.json.Json;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OAuth 2.0 Protected Resource Metadata (RFC 9728), which the MCP authorization specification
 * requires of an HTTP MCP server: a client that gets a 401 must be able to discover which
 * authorization server to use, and must bind its token to this server's canonical URI.
 *
 * <p>Two things are published:
 * <ul>
 *   <li>the metadata document at {@link #metadataPath()} (RFC 9728 inserts
 *       {@code /.well-known/oauth-protected-resource} between the authority and the resource path,
 *       so a resource at {@code https://host/mcp} publishes at
 *       {@code https://host/.well-known/oauth-protected-resource/mcp});</li>
 *   <li>the {@code WWW-Authenticate} challenge value with {@code resource_metadata}, returned with
 *       every 401 (see {@link #challenge()}).</li>
 * </ul>
 *
 * <p>The document is discovery data only: it is served unauthenticated, contains no secret, and is
 * built once at startup from configuration. Aegis is a resource server; it never issues tokens.
 *
 * @param resource             canonical URI of this MCP server, which tokens must be audience-bound to
 * @param authorizationServers issuer identifiers of the authorization servers that may issue those tokens
 * @param scopesSupported      scopes the operator publishes (may be empty)
 * @param documentation        optional human-readable documentation URI
 */
public record ProtectedResourceMetadata(URI resource, List<String> authorizationServers, List<String> scopesSupported,
                                        String documentation) {

    public static final String WELL_KNOWN = "/.well-known/oauth-protected-resource";

    public ProtectedResourceMetadata {
        if (resource == null || !resource.isAbsolute() || resource.getHost() == null || resource.getFragment() != null
                || resource.getRawQuery() != null) {
            throw new IllegalArgumentException("resource must be an absolute URI without query or fragment");
        }
        String scheme = resource.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!("https".equals(scheme) || ("http".equals(scheme) && isLoopback(resource.getHost())))) {
            // RFC 8707 / MCP: the resource identifier is an https URI; plain http only for local development.
            throw new IllegalArgumentException("resource must use https (http is allowed only for 127.0.0.1, ::1 or localhost)");
        }
        authorizationServers = List.copyOf(authorizationServers == null ? List.of() : authorizationServers);
        if (authorizationServers.isEmpty()) {
            throw new IllegalArgumentException("at least one authorizationServers entry is required");
        }
        for (String as : authorizationServers) {
            URI u;
            try {
                u = URI.create(as);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("authorizationServers entries must be absolute issuer URIs: " + as);
            }
            if (!u.isAbsolute() || u.getFragment() != null || u.getRawQuery() != null) {
                throw new IllegalArgumentException("authorizationServers entries must be absolute issuer URIs: " + as);
            }
            // Clients open or fetch these URLs. Publishing javascript:, data:, file: or a plain-http
            // remote URL would hand every MCP client a dangerous target (MCP security best practices,
            // OAuth authorization URL validation and SSRF; RFC 8414 issuers are https).
            String asScheme = u.getScheme().toLowerCase(java.util.Locale.ROOT);
            if (u.getHost() == null || !("https".equals(asScheme) || ("http".equals(asScheme) && isLoopback(u.getHost())))) {
                throw new IllegalArgumentException("authorizationServers entries must be https issuer URIs"
                        + " (http is allowed only for 127.0.0.1, ::1 or localhost): " + as);
            }
        }
        scopesSupported = List.copyOf(scopesSupported == null ? List.of() : scopesSupported);
        if (documentation != null) {
            URI d;
            try {
                d = URI.create(documentation);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("documentation must be an https URI");
            }
            String ds = d.getScheme() == null ? "" : d.getScheme().toLowerCase(java.util.Locale.ROOT);
            if (d.getHost() == null || !("https".equals(ds) || ("http".equals(ds) && isLoopback(d.getHost())))) {
                throw new IllegalArgumentException("documentation must be an https URI (a human opens it in a browser)");
            }
        }
    }

    private static boolean isLoopback(String host) {
        return "localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host) || "::1".equals(host);
    }

    /** Path this gateway serves the document on, with the resource path inserted per RFC 9728 section 3. */
    public String metadataPath() {
        String path = resource.getPath() == null ? "" : resource.getPath();
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return WELL_KNOWN + path;
    }

    /** Absolute URL of the document, used in the {@code WWW-Authenticate} challenge. */
    public String metadataUrl() {
        String authority = resource.getScheme() + "://" + resource.getRawAuthority();
        return authority + metadataPath();
    }

    /** The 401 challenge value MCP clients parse to find this document. */
    public String challenge() {
        return "Bearer realm=\"aegis\", resource_metadata=\"" + metadataUrl() + "\"";
    }

    /** The RFC 9728 document. */
    public String json() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("resource", resource.toString());
        m.put("authorization_servers", authorizationServers);
        if (!scopesSupported.isEmpty()) {
            m.put("scopes_supported", scopesSupported);
        }
        m.put("bearer_methods_supported", List.of("header"));
        if (documentation != null) {
            m.put("resource_documentation", documentation);
        }
        return Json.write(m);
    }
}
