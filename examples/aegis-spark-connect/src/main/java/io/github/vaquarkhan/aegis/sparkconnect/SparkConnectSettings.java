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
package io.github.vaquarkhan.aegis.sparkconnect;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * Connection settings for one Spark Connect server.
 *
 * @param host     Spark Connect server host
 * @param port     Spark Connect gRPC port (Spark's default is 15002)
 * @param useSsl   TLS to the server ({@code use_ssl=true}); required whenever a token is sent
 * @param token    bearer token for the server (from an environment variable), or null
 * @param userId   Spark Connect {@code user_id} the gateway presents (the gateway's service identity)
 * @param maxRows  upper bound on rows returned by any tool
 * @param threads  concurrent Spark Connect operations
 */
public record SparkConnectSettings(String host, int port, boolean useSsl, String token, String userId, int maxRows, int threads) {

    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.-]{1,253}|\\[[0-9A-Fa-f:.]{2,45}\\]");
    private static final Pattern USER = Pattern.compile("[A-Za-z0-9._@-]{1,128}");

    public SparkConnectSettings {
        if (host == null || !HOST.matcher(host).matches()) {
            throw new IllegalArgumentException("host must be a hostname or bracketed IPv6 literal");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be 1..65535");
        }
        if (token != null && (token.isEmpty() || token.indexOf(';') >= 0 || token.indexOf('/') >= 0)) {
            // ';' and '/' would change the meaning of the sc:// connection string.
            throw new IllegalArgumentException("token must be non-empty and must not contain ';' or '/'");
        }
        if (token != null && !useSsl) {
            throw new IllegalArgumentException("a token is only sent over TLS: set useSsl: true");
        }
        userId = userId == null ? "aegis-gateway" : userId;
        if (!USER.matcher(userId).matches()) {
            throw new IllegalArgumentException("userId must match " + USER.pattern());
        }
        if (maxRows < 1 || maxRows > 10_000) {
            throw new IllegalArgumentException("maxRows must be 1..10000");
        }
        if (threads < 1 || threads > 64) {
            throw new IllegalArgumentException("threads must be 1..64");
        }
    }

    /** The {@code sc://} connection string, token included. Never log it. */
    String connectionString() {
        StringBuilder sb = new StringBuilder("sc://").append(host).append(':').append(port).append("/;user_id=").append(userId);
        if (useSsl) {
            sb.append(";use_ssl=true");
        }
        if (token != null) {
            sb.append(";token=").append(token);
        }
        return sb.toString();
    }

    /**
     * The endpoint as the gateway's egress guard sees it. Spark Connect is gRPC, which is HTTP/2, so
     * the check is on {@code http(s)://host:port}: the host must be in {@code egress.allowHosts} (or
     * its address in {@code allowCidrs}) like every other backend.
     */
    public URI egressUri() {
        return URI.create((useSsl ? "https" : "http") + "://" + host + ":" + port);
    }

    @Override
    public String toString() {
        return "SparkConnectSettings[" + egressUri() + ", userId=" + userId + ", token=" + (token == null ? "none" : "***")
                + ", maxRows=" + maxRows + "]";
    }
}
