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
package io.github.vaquarkhan.aegis.mcp.http;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Host-neutral view of an HTTP request. The gateway's own server adapts the JDK HttpExchange to it
 * and the Knox extension adapts the JAX-RS request, so both front doors share one protocol handler.
 *
 * @param method  HTTP method, upper case
 * @param path    path relative to the MCP endpoint or admin root (e.g. "" or "/approvals/apr_..")
 * @param headers header values; lookups are case-insensitive
 * @param body    request body, already bounded by the host
 * @param peerCertificates the TLS client certificate chain the host verified during the handshake,
 *                leaf first; empty when the connection had no verified client certificate
 */
public record McpHttpRequest(String method, String path, Map<String, List<String>> headers, byte[] body,
                             List<java.security.cert.X509Certificate> peerCertificates) {

    /** A request without a verified TLS client certificate. */
    public McpHttpRequest(String method, String path, Map<String, List<String>> headers, byte[] body) {
        this(method, path, headers, body, List.of());
    }

    public McpHttpRequest {
        peerCertificates = peerCertificates == null ? List.of() : List.copyOf(peerCertificates);
        method = method == null ? "" : method.toUpperCase(Locale.ROOT);
        path = path == null ? "" : path;
        TreeMap<String, List<String>> h = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (headers != null) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey() != null) {
                    h.merge(e.getKey(), List.copyOf(e.getValue()), (a, b) -> {
                        java.util.ArrayList<String> all = new java.util.ArrayList<>(a);
                        all.addAll(b);
                        return List.copyOf(all);
                    });
                }
            }
        }
        headers = java.util.Collections.unmodifiableMap(h);
        body = body == null ? new byte[0] : body;
    }

    /** Single header value; null when absent. Multiple values for a single-valued MCP header are an error the caller detects via {@link #headerCount}. */
    public String header(String name) {
        List<String> v = headers.get(name);
        return v == null || v.isEmpty() ? null : v.get(0);
    }

    public int headerCount(String name) {
        List<String> v = headers.get(name);
        return v == null ? 0 : v.size();
    }
}
