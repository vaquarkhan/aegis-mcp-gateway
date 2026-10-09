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
package io.github.vaquarkhan.aegis.mcp.client;

import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.json.JsonException;
import io.github.vaquarkhan.aegis.engine.json.JsonLimits;
import io.github.vaquarkhan.aegis.engine.json.JsonValues;
import io.github.vaquarkhan.aegis.mcp.http.BoundedHttp;
import io.github.vaquarkhan.aegis.mcp.protocol.HeaderValues;
import io.github.vaquarkhan.aegis.mcp.protocol.JsonRpc;
import io.github.vaquarkhan.aegis.mcp.protocol.McpVersions;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Streamable HTTP client for an existing MCP server, dual-era:
 * <ul>
 *   <li>MODERN: every request carries {@code _meta} protocol metadata plus the mirrored
 *       {@code MCP-Protocol-Version}, {@code Mcp-Method} and {@code Mcp-Name} headers.</li>
 *   <li>LEGACY: {@code initialize}, then {@code notifications/initialized}; the server-assigned
 *       {@code Mcp-Session-Id} is sent on every request and the session is re-established once on 404.</li>
 *   <li>AUTO: probes with a modern {@code server/discover}; falls back to LEGACY when the answer is
 *       not a recognised modern result or error (the 2026-07-28 backward-compatibility procedure).</li>
 * </ul>
 * Responses may be JSON or a request-scoped SSE stream. Server-to-client requests on a legacy SSE
 * stream (sampling, elicitation, roots) are refused: they would reach the model outside governance.
 * Response bodies are bounded; redirects are not followed (a redirect could bypass the egress check).
 */
public final class HttpMcpClient implements McpClient {

    public enum Era { AUTO, MODERN, LEGACY }

    private static final Set<String> RESTRICTED = Set.of("host", "content-length", "connection", "upgrade", "expect",
            "mcp-protocol-version", "mcp-method", "mcp-name", "mcp-session-id", "content-type", "accept");

    private final URI endpoint;
    private final Era configured;
    private final String legacyVersion;
    private final int maxResponseBytes;
    private final HttpClient http;
    private final AtomicLong ids = new AtomicLong();
    private volatile Era resolved;
    private volatile String sessionId;
    private volatile String negotiated;

    public HttpMcpClient(URI endpoint, Era era, String legacyVersion, int maxResponseBytes, Duration connectTimeout) {
        this.endpoint = endpoint;
        this.configured = era == null ? Era.AUTO : era;
        this.legacyVersion = legacyVersion == null ? McpVersions.V2025_11_25 : legacyVersion;
        if (!McpVersions.isLegacy(this.legacyVersion)) {
            throw new IllegalArgumentException("legacy protocol version must be one of " + McpVersions.LEGACY);
        }
        this.maxResponseBytes = maxResponseBytes <= 0 ? 4 << 20 : maxResponseBytes;
        this.http = HttpClient.newBuilder()
                .connectTimeout(connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public URI endpoint() {
        return endpoint;
    }

    /** Era in use after the first exchange (for tests and diagnostics). */
    public Era resolvedEra() {
        return resolved;
    }

    @Override
    public List<Map<String, Object>> listTools(Map<String, String> headers, long timeoutMillis) throws McpCallException {
        List<Map<String, Object>> out = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 50; page++) {
            Map<String, Object> params = new LinkedHashMap<>();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            Map<String, Object> r = rpc("tools/list", params, null, headers, timeoutMillis);
            for (Object t : ClientSupport.list(r, "tools")) {
                if (ClientSupport.validTool(t)) {
                    out.add(JsonValues.asObject(t));
                }
            }
            cursor = JsonValues.string(r, "nextCursor");
            if (cursor == null || cursor.isEmpty()) {
                return out;
            }
        }
        throw new McpCallException("downstream tools/list pagination exceeded 50 pages");
    }

    @Override
    public Map<String, Object> callTool(String name, Map<String, Object> arguments, Map<String, String> headers,
                                        long timeoutMillis) throws McpCallException {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", name);
        params.put("arguments", arguments == null ? Map.of() : arguments);
        return rpc("tools/call", params, name, headers, timeoutMillis);
    }

    @Override
    public Map<String, Object> call(String method, Map<String, Object> params, String mcpName, Map<String, String> headers,
                                    long timeoutMillis) throws McpCallException {
        return rpc(method, params == null ? Map.of() : params, mcpName, headers, timeoutMillis);
    }

    private Map<String, Object> rpc(String method, Map<String, Object> params, String mcpName,
                                    Map<String, String> headers, long timeoutMillis) throws McpCallException {
        ensureEra(headers, timeoutMillis);
        try {
            return ClientSupport.resultOf(send(method, params, mcpName, headers, timeoutMillis, true));
        } catch (SessionExpired e) {
            synchronized (this) {
                sessionId = null;
                initializeLegacy(headers, timeoutMillis);
            }
            try {
                return ClientSupport.resultOf(send(method, params, mcpName, headers, timeoutMillis, true));
            } catch (SessionExpired again) {
                throw new McpCallException("downstream MCP session could not be re-established");
            }
        }
    }

    private synchronized void ensureEra(Map<String, String> headers, long timeoutMillis) throws McpCallException {
        if (resolved != null) {
            return;
        }
        switch (configured) {
            case MODERN -> resolved = Era.MODERN;
            case LEGACY -> initializeLegacy(headers, timeoutMillis);
            default -> {
                if (probeModern(headers, timeoutMillis) == Probe.MODERN) {
                    resolved = Era.MODERN;
                } else {
                    initializeLegacy(headers, timeoutMillis);
                }
            }
        }
    }

    private enum Probe { MODERN, LEGACY }

    private Probe probeModern(Map<String, String> headers, long timeoutMillis) throws McpCallException {
        Raw raw = exchange(buildBody("server/discover", Map.of(), true), "server/discover", null, headers, timeoutMillis, true);
        if (raw.status == 200) {
            Map<String, Object> msg = raw.json(null);
            Map<String, Object> result = msg == null ? null : JsonValues.object(msg, "result");
            List<Object> versions = result == null ? null : JsonValues.asList(result.get("supportedVersions"));
            if (versions != null && versions.contains(McpVersions.V2026_07_28)) {
                return Probe.MODERN;
            }
            return Probe.LEGACY;
        }
        if (raw.status >= 400 && raw.status < 500) {
            Map<String, Object> msg = raw.jsonQuietly();
            Map<String, Object> err = msg == null ? null : JsonValues.object(msg, "error");
            Long code = err == null ? null : JsonValues.integral(err.get("code"));
            if (code != null && ClientSupport.MODERN_ERROR_CODES.contains(code)) {
                if (code == JsonRpc.UNSUPPORTED_PROTOCOL_VERSION) {
                    // Modern server that does not speak our modern version: no mutually supported version.
                    throw new McpCallException("downstream MCP server does not support " + McpVersions.V2026_07_28);
                }
                return Probe.MODERN;
            }
            return Probe.LEGACY;
        }
        throw new McpCallException("downstream MCP probe failed with HTTP " + raw.status);
    }

    private void initializeLegacy(Map<String, String> headers, long timeoutMillis) throws McpCallException {
        resolved = Era.LEGACY;
        negotiated = null;
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", legacyVersion);
        params.put("capabilities", Map.of());
        params.put("clientInfo", ClientSupport.clientInfo());
        Raw raw = exchange(buildBody("initialize", params, false), "initialize", null, headers, timeoutMillis, false);
        if (raw.status != 200) {
            resolved = null;
            throw new McpCallException("downstream MCP initialize failed with HTTP " + raw.status);
        }
        Map<String, Object> result;
        try {
            result = ClientSupport.resultOf(raw.json(null));
        } catch (McpCallException e) {
            resolved = null;
            throw e;
        }
        String v = JsonValues.string(result, "protocolVersion");
        if (v == null || !McpVersions.isLegacy(v)) {
            resolved = null;
            throw new McpCallException("downstream MCP server negotiated an unsupported version");
        }
        negotiated = v;
        sessionId = raw.sessionId;
        Raw ack = exchange(Json.writeBytes(JsonRpc.notification("notifications/initialized", null)),
                "notifications/initialized", null, headers, timeoutMillis, false);
        if (ack.status >= 300) {
            throw new McpCallException("downstream MCP rejected notifications/initialized (HTTP " + ack.status + ")");
        }
    }

    private static final class SessionExpired extends Exception {
        private static final long serialVersionUID = 1L;
    }

    private Map<String, Object> send(String method, Map<String, Object> params, String mcpName, Map<String, String> headers,
                                     long timeoutMillis, boolean allowSessionRetry) throws McpCallException, SessionExpired {
        boolean modern = resolved == Era.MODERN;
        long id = ids.incrementAndGet();
        Map<String, Object> req = JsonRpc.request(id, method, modern ? ClientSupport.withModernMeta(params) : params);
        Raw raw = exchange(Json.writeBytes(req), method, mcpName, headers, timeoutMillis, modern);
        if (!modern && raw.status == 404 && sessionId != null && allowSessionRetry) {
            throw new SessionExpired();
        }
        if (raw.status != 200) {
            Map<String, Object> maybe = raw.jsonQuietly();
            if (maybe != null && maybe.containsKey("error")) {
                return maybe;
            }
            throw new McpCallException("downstream MCP returned HTTP " + raw.status);
        }
        return raw.json(id);
    }

    private byte[] buildBody(String method, Map<String, Object> params, boolean modern) {
        return Json.writeBytes(JsonRpc.request(ids.incrementAndGet(), method, modern ? ClientSupport.withModernMeta(params) : params));
    }

    private Raw exchange(byte[] body, String method, String mcpName, Map<String, String> headers, long timeoutMillis,
                         boolean modern) throws McpCallException {
        HttpRequest.Builder b = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMillis(Math.max(1, timeoutMillis)))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (modern) {
            b.header(McpVersions.HEADER_PROTOCOL_VERSION, McpVersions.V2026_07_28);
            b.header(McpVersions.HEADER_METHOD, HeaderValues.encode(method));
            if (mcpName != null) {
                b.header(McpVersions.HEADER_NAME, HeaderValues.encode(mcpName));
            }
        } else {
            if (negotiated != null && !McpVersions.V2025_03_26.equals(negotiated)) {
                b.header(McpVersions.HEADER_PROTOCOL_VERSION, negotiated);
            }
            if (sessionId != null) {
                b.header(McpVersions.HEADER_SESSION_ID, sessionId);
            }
        }
        if (headers != null) {
            for (Map.Entry<String, String> h : headers.entrySet()) {
                if (RESTRICTED.contains(h.getKey().toLowerCase(Locale.ROOT))) {
                    throw new McpCallException("credential header '" + h.getKey() + "' is not allowed");
                }
                b.header(h.getKey(), h.getValue());
            }
        }
        try {
            BoundedHttp.Result resp = BoundedHttp.send(http, b.build(), maxResponseBytes, Math.max(1, timeoutMillis));
            String ct = resp.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
            return new Raw(resp.status(), ct, resp.body(), resp.headers().firstValue(McpVersions.HEADER_SESSION_ID).orElse(null));
        } catch (HttpTimeoutException e) {
            throw new McpCallException("downstream MCP call timed out", null, true, e);
        } catch (IOException e) {
            throw new McpCallException("downstream MCP call failed: " + e.getClass().getSimpleName(), null, false, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpCallException("interrupted", null, false, e);
        }
    }

    /** Raw HTTP exchange result. */
    private final class Raw {
        final int status;
        final String contentType;
        final byte[] body;
        final String sessionId;

        Raw(int status, String contentType, byte[] body, String sessionId) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
            this.sessionId = sessionId;
        }

        Map<String, Object> jsonQuietly() {
            try {
                return contentType.startsWith("application/json") && body.length > 0
                        ? Json.parseObject(body, JsonLimits.DEFAULT.withMaxBytes(maxResponseBytes)) : null;
            } catch (JsonException e) {
                return null;
            }
        }

        /** Parses a JSON or SSE response; for SSE, returns the response whose id matches. */
        Map<String, Object> json(Long expectedId) throws McpCallException {
            JsonLimits limits = JsonLimits.DEFAULT.withMaxBytes(maxResponseBytes);
            try {
                if (contentType.startsWith("text/event-stream")) {
                    return fromSse(limits, expectedId);
                }
                return Json.parseObject(body, limits);
            } catch (JsonException e) {
                throw new McpCallException("downstream MCP response is not valid JSON-RPC: " + e.getMessage());
            }
        }

        private Map<String, Object> fromSse(JsonLimits limits, Long expectedId) throws McpCallException {
            String text = new String(body, StandardCharsets.UTF_8);
            StringBuilder data = new StringBuilder();
            for (String line : (text + "\n\n").split("\r?\n", -1)) {
                if (line.isEmpty()) {
                    if (data.length() > 0) {
                        Map<String, Object> msg = Json.parseObject(data.toString().getBytes(StandardCharsets.UTF_8), limits);
                        data.setLength(0);
                        if (msg.containsKey("method") && msg.containsKey("id")) {
                            throw new McpCallException("downstream MCP server sent a server-to-client request ("
                                    + msg.get("method") + "); Aegis does not relay these outside governance");
                        }
                        if (msg.containsKey("method")) {
                            continue; // notification (progress/log): not relayed
                        }
                        if (expectedId == null || expectedId.equals(JsonValues.integral(msg.get("id")))) {
                            return msg;
                        }
                    }
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) {
                        data.append('\n');
                    }
                    data.append(line.substring(line.startsWith("data: ") ? 6 : 5));
                }
            }
            throw new McpCallException("downstream MCP SSE stream ended without a response");
        }
    }

    @Override
    public void close() {
        // java.net.http.HttpClient in Java 17 has no close(); connections are released when idle.
    }
}
