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

import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.mcp.http.McpHttpRequest;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Workload identity from an X.509-SVID (SPIFFE X.509-SVID specification): the TLS client certificate
 * the host already verified against the configured trust bundle during the handshake. This class
 * only reads the verified leaf; it never accepts a certificate the TLS layer did not verify.
 *
 * <p>The leaf must carry exactly one URI SAN, a {@code spiffe://} ID in the configured trust domain
 * with a non-empty path and no port, user-info, query or fragment; it must not be a CA certificate and,
 * when key usage is present, must allow digital signatures and not certificate signing. The ID is then
 * looked up in an explicit operator mapping: an ID that is valid but not listed is not authenticated.
 */
public final class SpiffeIdentity implements IdentityResolver {

    private static final System.Logger LOG = System.getLogger(SpiffeIdentity.class.getName());
    /** {@code GeneralName} tag for uniformResourceIdentifier (RFC 5280 section 4.2.1.6). */
    private static final int SAN_URI = 6;

    private final String trustDomain;
    private final Map<String, CallerIdentity> byId;

    /**
     * @param trustDomain the SPIFFE trust domain, for example {@code example.org}
     * @param byId        SPIFFE ID to the caller it authenticates
     */
    public SpiffeIdentity(String trustDomain, Map<String, CallerIdentity> byId) {
        if (trustDomain == null || !trustDomain.matches("[a-z0-9.\\-_]+")) {
            throw new IllegalArgumentException("trustDomain must be lower-case letters, digits, '.', '-' or '_'");
        }
        this.trustDomain = trustDomain;
        for (String id : byId.keySet()) {
            String why = problem(id, trustDomain);
            if (why != null) {
                throw new IllegalArgumentException("'" + id + "' " + why);
            }
        }
        this.byId = Map.copyOf(byId);
    }

    @Override
    public CallerIdentity resolve(McpHttpRequest request) {
        List<X509Certificate> chain = request.peerCertificates();
        if (chain.isEmpty()) {
            LOG.log(System.Logger.Level.INFO, "spiffe: no verified client certificate on the connection");
            return null;
        }
        X509Certificate leaf = chain.get(0);
        String id;
        try {
            id = spiffeId(leaf);
        } catch (IllegalArgumentException e) {
            LOG.log(System.Logger.Level.INFO, "spiffe: client certificate is not an X.509-SVID: " + e.getMessage());
            return null;
        }
        String why = problem(id, trustDomain);
        if (why != null) {
            LOG.log(System.Logger.Level.INFO, "spiffe: " + id + " " + why);
            return null;
        }
        CallerIdentity caller = byId.get(id);
        if (caller == null) {
            LOG.log(System.Logger.Level.INFO, "spiffe: " + id + " is not mapped to a caller");
        }
        return caller;
    }

    /** @return the one URI SAN of an SVID leaf; IllegalArgumentException when the certificate is not an SVID leaf */
    static String spiffeId(X509Certificate leaf) {
        if (leaf.getBasicConstraints() != -1) {
            throw new IllegalArgumentException("a CA certificate cannot be a leaf SVID");
        }
        boolean[] ku = leaf.getKeyUsage();
        if (ku != null && (!ku[0] || (ku.length > 5 && ku[5]))) {
            throw new IllegalArgumentException("key usage must include digitalSignature and exclude keyCertSign");
        }
        Collection<List<?>> sans;
        try {
            sans = leaf.getSubjectAlternativeNames();
        } catch (CertificateParsingException e) {
            throw new IllegalArgumentException("unreadable subject alternative names");
        }
        List<String> uris = new ArrayList<>();
        if (sans != null) {
            for (List<?> san : sans) {
                if (san.size() >= 2 && Integer.valueOf(SAN_URI).equals(san.get(0))) {
                    uris.add(String.valueOf(san.get(1)));
                }
            }
        }
        if (uris.size() != 1) {
            throw new IllegalArgumentException("an SVID has exactly one URI SAN, this one has " + uris.size());
        }
        return uris.get(0);
    }

    /** @return null when {@code id} is a well-formed SPIFFE ID in {@code trustDomain}, otherwise why not */
    static String problem(String id, String trustDomain) {
        URI u;
        try {
            u = new URI(id);
        } catch (URISyntaxException e) {
            return "is not a URI";
        }
        if (!"spiffe".equals(u.getScheme())) {
            return "is not a spiffe:// ID";
        }
        if (u.getRawUserInfo() != null || u.getPort() != -1 || u.getRawQuery() != null || u.getRawFragment() != null) {
            return "must not carry user-info, a port, a query or a fragment";
        }
        String host = u.getHost();
        if (host == null || !host.equals(host.toLowerCase(Locale.ROOT)) || !host.equals(trustDomain)) {
            return "is not in trust domain " + trustDomain;
        }
        String path = u.getRawPath();
        if (path == null || path.length() < 2 || !path.startsWith("/")) {
            return "needs a non-empty path";
        }
        for (String segment : path.substring(1).split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)
                    || !segment.matches("[A-Za-z0-9.\\-_]+")) {
                return "has a path segment that is empty, '.', '..' or outside [A-Za-z0-9.-_]";
            }
        }
        return null;
    }
}
