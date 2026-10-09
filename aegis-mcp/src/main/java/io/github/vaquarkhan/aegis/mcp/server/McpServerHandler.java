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
package io.github.vaquarkhan.aegis.mcp.server;

import io.github.vaquarkhan.aegis.engine.CallOutcome;
import io.github.vaquarkhan.aegis.engine.GovernanceEngine;
import io.github.vaquarkhan.aegis.engine.content.ContentGovernor;
import io.github.vaquarkhan.aegis.engine.model.Decision;
import io.github.vaquarkhan.aegis.engine.model.TraceContext;
import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.json.JsonException;
import io.github.vaquarkhan.aegis.engine.json.JsonValues;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.model.ToolCall;
import io.github.vaquarkhan.aegis.mcp.http.McpHttpRequest;
import io.github.vaquarkhan.aegis.mcp.http.McpHttpResponse;
import io.github.vaquarkhan.aegis.mcp.protocol.HeaderValues;
import io.github.vaquarkhan.aegis.mcp.protocol.JsonRpc;
import io.github.vaquarkhan.aegis.mcp.protocol.McpVersions;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The gateway's MCP server: one handler for both eras and both transports.
 *
 * <ul>
 *   <li>Modern (2026-07-28), selected by {@code params._meta["io.modelcontextprotocol/protocolVersion"]}:
 *       stateless; {@code MCP-Protocol-Version}, {@code Mcp-Method} and {@code Mcp-Name} headers are
 *       REQUIRED and must match the body (else 400 / -32020). The spec states the reason: a load
 *       balancer routing on a header while the server executes the body is exactly the kind of
 *       disagreement a governance gateway must refuse. {@code Mcp-Session-Id} is ignored.</li>
 *   <li>Legacy (2025-03-26 .. 2025-11-25), selected by {@code initialize}: optional caller-bound
 *       sessions, {@code ping}, and the classic handshake.</li>
 * </ul>
 * Served: {@code tools/*} always; {@code resources/*}, {@code prompts/*} and
 * {@code completion/complete} only when a source enables them (governed by {@link ContentGovernor}),
 * and only then advertised in capabilities. Everything else (subscriptions, {@code logging/setLevel},
 * sampling or elicitation towards the client) is method-not-found and not advertised, so nothing
 * ungoverned is proxied. Governance denials of content requests are JSON-RPC errors
 * {@link JsonRpc#GOVERNANCE_DENIED} with the stable deny code in {@code data}.
 */
public final class McpServerHandler {

    private final GovernanceEngine engine;
    private final ContentGovernor content;
    private final ServerOptions options;
    private final SessionRegistry sessions;

    public McpServerHandler(GovernanceEngine engine, ServerOptions options, SessionRegistry sessions) {
        this(engine, null, options, sessions);
    }

    /** @param content resources/prompts/completion governor; null serves tools only */
    public McpServerHandler(GovernanceEngine engine, ContentGovernor content, ServerOptions options, SessionRegistry sessions) {
        this.engine = engine;
        this.content = content;
        this.options = options;
        this.sessions = sessions;
    }

    public ServerOptions options() {
        return options;
    }

    /** Protocol error carrying the HTTP status mandated for it. */
    static final class RpcError extends Exception {
        private static final long serialVersionUID = 1L;
        final int code;
        final int httpStatus;
        final transient Object data;

        RpcError(int code, String message, int httpStatus, Object data) {
            super(message);
            this.code = code;
            this.httpStatus = httpStatus;
            this.data = data;
        }
    }

    // ================================================================= HTTP (Streamable HTTP)

    /**
     * @param caller identity established by the host; null means the host did not authenticate the
     *               request, which is answered 401 (the host should normally have rejected it already)
     */
    public McpHttpResponse handleHttp(McpHttpRequest req, CallerIdentity caller) {
        String origin = req.header("Origin");
        if (origin != null && !options.allowedOrigins().contains(origin)) {
            return McpHttpResponse.json(403, JsonRpc.error(null, JsonRpc.INVALID_REQUEST, "origin not allowed", null));
        }
        if (caller == null) {
            return McpHttpResponse.json(401, JsonRpc.error(null, JsonRpc.UNAUTHENTICATED, "authentication required", null));
        }
        switch (req.method()) {
            case "POST":
                return post(req, caller);
            case "DELETE": {
                String sid = req.header(McpVersions.HEADER_SESSION_ID);
                if (sid != null && options.legacySessions() && sessions.delete(sid, caller)) {
                    return McpHttpResponse.empty(200);
                }
                return McpHttpResponse.empty(sid == null ? 405 : 404, Map.of("Allow", "POST"));
            }
            default:
                // No separate GET SSE stream: 2026-07-28 removed it; 2025 servers MAY answer 405.
                return McpHttpResponse.empty(405, Map.of("Allow", "POST"));
        }
    }

    private McpHttpResponse post(McpHttpRequest req, CallerIdentity caller) {
        Map<String, Object> msg;
        try {
            Object parsed = Json.parse(req.body(), options.limits());
            if (!(parsed instanceof Map)) {
                return McpHttpResponse.json(400, JsonRpc.error(null, JsonRpc.INVALID_REQUEST,
                        "body must be a single JSON-RPC object (batches are not supported)", null));
            }
            msg = JsonValues.asObject(parsed);
        } catch (JsonException e) {
            return McpHttpResponse.json(e.limitExceeded() ? 413 : 400, JsonRpc.error(null, JsonRpc.PARSE_ERROR, e.getMessage(), null));
        }
        if (!"2.0".equals(msg.get("jsonrpc"))) {
            return McpHttpResponse.json(400, JsonRpc.error(null, JsonRpc.INVALID_REQUEST, "jsonrpc must be \"2.0\"", null));
        }
        Object id = msg.get("id");
        Object methodObj = msg.get("method");
        if (!(methodObj instanceof String method)) {
            // Clients MUST NOT send responses: this gateway never issues server-to-client requests.
            return McpHttpResponse.json(400, JsonRpc.error(null, JsonRpc.INVALID_REQUEST, "expected a JSON-RPC request or notification", null));
        }
        boolean notification = !msg.containsKey("id");
        if (!notification && !JsonRpc.validId(id)) {
            return McpHttpResponse.json(400, JsonRpc.error(null, JsonRpc.INVALID_REQUEST, "invalid request id", null));
        }
        Object paramsObj = msg.get("params");
        if (paramsObj != null && !(paramsObj instanceof Map)) {
            return McpHttpResponse.json(400, JsonRpc.error(id, JsonRpc.INVALID_PARAMS, "params must be an object", null));
        }
        Map<String, Object> params = paramsObj == null ? Map.of() : JsonValues.asObject(paramsObj);
        Map<String, Object> meta = JsonValues.object(params, "_meta");
        // W3C trace context: _meta.traceparent wins; the HTTP header is the fallback carrier.
        String headerTrace = req.header(TraceContext.HEADER);
        if (headerTrace != null && req.headerCount(TraceContext.HEADER) == 1
                && (meta == null || !meta.containsKey(TraceContext.META_KEY))) {
            Map<String, Object> m = new LinkedHashMap<>(meta == null ? Map.of() : meta);
            m.put(TraceContext.META_KEY, headerTrace);
            Map<String, Object> p = new LinkedHashMap<>(params);
            p.put("_meta", m);
            params = p;
            meta = m;
        }
        String modernVersion = meta == null ? null : JsonValues.string(meta, McpVersions.META_PROTOCOL_VERSION);

        try {
            if (modernVersion != null || (meta != null && meta.containsKey(McpVersions.META_PROTOCOL_VERSION))) {
                return modernHttp(req, caller, id, method, params, meta, notification);
            }
            return legacyHttp(req, caller, id, method, params, notification);
        } catch (RpcError e) {
            return McpHttpResponse.json(e.httpStatus, JsonRpc.error(notification ? null : id, e.code, e.getMessage(), e.data));
        }
    }

    private McpHttpResponse modernHttp(McpHttpRequest req, CallerIdentity caller, Object id, String method,
                                       Map<String, Object> params, Map<String, Object> meta, boolean notification) throws RpcError {
        Object v = meta.get(McpVersions.META_PROTOCOL_VERSION);
        if (!(v instanceof String version)) {
            throw new RpcError(JsonRpc.INVALID_PARAMS, "protocolVersion in _meta must be a string", 400, null);
        }
        // Header validation (Streamable HTTP 2026-07-28 "Server Validation").
        requireSingleHeader(req, McpVersions.HEADER_PROTOCOL_VERSION, version);
        requireSingleHeader(req, McpVersions.HEADER_METHOD, method);
        String nameSource = switch (method) {
            case "tools/call", "prompts/get" -> JsonValues.string(params, "name");
            case "resources/read" -> JsonValues.string(params, "uri");
            default -> null;
        };
        if (nameSource != null) {
            String h = req.header(McpVersions.HEADER_NAME);
            if (h == null || req.headerCount(McpVersions.HEADER_NAME) != 1) {
                throw new RpcError(JsonRpc.HEADER_MISMATCH, "missing or repeated Mcp-Name header", 400, null);
            }
            String decoded = HeaderValues.decode(h);
            if (decoded == null || !decoded.equals(nameSource)) {
                throw new RpcError(JsonRpc.HEADER_MISMATCH, "Mcp-Name header does not match the request body", 400, null);
            }
        }
        if (!McpVersions.isModern(version) || !options.enabledVersions().contains(version)) {
            throw new RpcError(JsonRpc.UNSUPPORTED_PROTOCOL_VERSION, "unsupported protocol version", 400,
                    Map.of("supported", supportedModern(), "requested", version));
        }
        if (notification) {
            return McpHttpResponse.empty(202);
        }
        Object result = dispatchModern(caller, version, method, params);
        return McpHttpResponse.json(200, JsonRpc.result(id, result));
    }

    private static void requireSingleHeader(McpHttpRequest req, String name, String expected) throws RpcError {
        String h = req.header(name);
        if (h == null || req.headerCount(name) != 1) {
            throw new RpcError(JsonRpc.HEADER_MISMATCH, "missing or repeated " + name + " header", 400, null);
        }
        String decoded = HeaderValues.decode(h);
        if (decoded == null || !decoded.equals(expected)) {
            throw new RpcError(JsonRpc.HEADER_MISMATCH, name + " header does not match the request body", 400, null);
        }
    }

    private McpHttpResponse legacyHttp(McpHttpRequest req, CallerIdentity caller, Object id, String method,
                                       Map<String, Object> params, boolean notification) throws RpcError {
        // 2025 revisions do not define Mcp-Method/Mcp-Name, but an intermediary may still route on
        // them. When present they must agree with the body in every era, or they could be used to
        // steer something in front of the gateway differently from what the gateway governs.
        if (req.header(McpVersions.HEADER_METHOD) != null) {
            requireSingleHeader(req, McpVersions.HEADER_METHOD, method);
        }
        if (req.header(McpVersions.HEADER_NAME) != null) {
            String nameSource = "resources/read".equals(method) ? JsonValues.string(params, "uri") : JsonValues.string(params, "name");
            requireSingleHeader(req, McpVersions.HEADER_NAME, nameSource == null ? "" : nameSource);
        }
        if (supportedLegacy().isEmpty()) {
            throw new RpcError(JsonRpc.UNSUPPORTED_PROTOCOL_VERSION,
                    "this gateway serves only " + supportedModern() + "; send per-request _meta", 400,
                    Map.of("supported", supportedModern(), "requested", "legacy"));
        }
        if ("initialize".equals(method)) {
            if (notification) {
                throw new RpcError(JsonRpc.INVALID_REQUEST, "initialize must be a request", 400, null);
            }
            String version = negotiateLegacy(JsonValues.string(params, "protocolVersion"));
            Map<String, String> headers = new LinkedHashMap<>();
            if (options.legacySessions()) {
                Optional<SessionRegistry.Session> s = sessions.create(caller, version);
                if (s.isEmpty()) {
                    throw new RpcError(JsonRpc.INTERNAL_ERROR, "session capacity reached", 503, null);
                }
                headers.put(McpVersions.HEADER_SESSION_ID, s.get().id());
            }
            return McpHttpResponse.json(200, JsonRpc.result(id, initializeResult(version)), headers);
        }
        String version;
        String headerVersion = req.header(McpVersions.HEADER_PROTOCOL_VERSION);
        if (options.legacySessions()) {
            String sid = req.header(McpVersions.HEADER_SESSION_ID);
            if (sid == null) {
                throw new RpcError(JsonRpc.INVALID_REQUEST, "Mcp-Session-Id header is required; call initialize first", 400, null);
            }
            SessionRegistry.Session s = sessions.find(sid, caller)
                    .orElseThrow(() -> new RpcError(JsonRpc.INVALID_REQUEST, "session not found", 404, null));
            version = s.protocolVersion();
            if (headerVersion != null && !headerVersion.equals(version)) {
                throw new RpcError(JsonRpc.INVALID_REQUEST, "MCP-Protocol-Version does not match the negotiated version", 400, null);
            }
        } else {
            version = headerVersion == null ? McpVersions.ASSUMED_WHEN_HEADER_MISSING : headerVersion;
            if (!supportedLegacy().contains(version)) {
                throw new RpcError(JsonRpc.INVALID_REQUEST, "unsupported MCP-Protocol-Version", 400, null);
            }
        }
        if (notification) {
            return McpHttpResponse.empty(202);
        }
        return McpHttpResponse.json(200, JsonRpc.result(id, dispatchLegacy(caller, version, method, params)));
    }

    // ================================================================= stdio

    /** One stdio connection (one client process). Not thread-safe: stdio is a single ordered stream. */
    public final class StdioConnection {
        private final CallerIdentity caller;
        private String legacyVersion;

        StdioConnection(CallerIdentity caller) {
            this.caller = caller;
        }

        /** @return the response line, or null for notifications */
        public String handleLine(String line) {
            Map<String, Object> msg;
            try {
                Object parsed = Json.parse(line.getBytes(StandardCharsets.UTF_8), options.limits());
                if (!(parsed instanceof Map)) {
                    return Json.write(JsonRpc.error(null, JsonRpc.INVALID_REQUEST, "expected a single JSON-RPC object", null));
                }
                msg = JsonValues.asObject(parsed);
            } catch (JsonException e) {
                return Json.write(JsonRpc.error(null, JsonRpc.PARSE_ERROR, e.getMessage(), null));
            }
            Object id = msg.get("id");
            boolean notification = !msg.containsKey("id");
            if (!(msg.get("method") instanceof String method) || !"2.0".equals(msg.get("jsonrpc"))) {
                return notification ? null : Json.write(JsonRpc.error(id, JsonRpc.INVALID_REQUEST, "invalid JSON-RPC request", null));
            }
            Object po = msg.get("params");
            if (po != null && !(po instanceof Map)) {
                return notification ? null : Json.write(JsonRpc.error(id, JsonRpc.INVALID_PARAMS, "params must be an object", null));
            }
            Map<String, Object> params = po == null ? Map.of() : JsonValues.asObject(po);
            Map<String, Object> meta = JsonValues.object(params, "_meta");
            try {
                Object result;
                if (meta != null && meta.containsKey(McpVersions.META_PROTOCOL_VERSION)) {
                    Object v = meta.get(McpVersions.META_PROTOCOL_VERSION);
                    if (!(v instanceof String version) || !McpVersions.isModern(version) || !options.enabledVersions().contains(version)) {
                        throw new RpcError(JsonRpc.UNSUPPORTED_PROTOCOL_VERSION, "unsupported protocol version", 400,
                                Map.of("supported", supportedModern(), "requested", String.valueOf(v)));
                    }
                    if (notification) {
                        return null;
                    }
                    result = dispatchModern(caller, version, method, params);
                } else if ("initialize".equals(method)) {
                    if (supportedLegacy().isEmpty()) {
                        throw new RpcError(JsonRpc.UNSUPPORTED_PROTOCOL_VERSION,
                                "this gateway serves only " + supportedModern(), 400,
                                Map.of("supported", supportedModern(), "requested", "legacy"));
                    }
                    legacyVersion = negotiateLegacy(JsonValues.string(params, "protocolVersion"));
                    result = initializeResult(legacyVersion);
                } else {
                    if (notification) {
                        return null;
                    }
                    if (legacyVersion == null) {
                        throw new RpcError(JsonRpc.INVALID_REQUEST, "call initialize first (or send per-request _meta)", 400, null);
                    }
                    result = dispatchLegacy(caller, legacyVersion, method, params);
                }
                return notification ? null : Json.write(JsonRpc.result(id, result));
            } catch (RpcError e) {
                return notification ? null : Json.write(JsonRpc.error(id, e.code, e.getMessage(), e.data));
            }
        }
    }

    public StdioConnection stdio(CallerIdentity caller) {
        if (caller == null) {
            throw new IllegalArgumentException("stdio connections need a configured identity");
        }
        return new StdioConnection(caller);
    }

    // ================================================================= dispatch

    private Object dispatchModern(CallerIdentity caller, String version, String method, Map<String, Object> params) throws RpcError {
        Map<String, Object> result;
        switch (method) {
            case "server/discover": {
                result = new LinkedHashMap<>();
                List<String> versions = new ArrayList<>(supportedModern());
                versions.addAll(supportedLegacy());
                result.put("supportedVersions", versions);
                result.put("capabilities", capabilities());
                if (options.instructions() != null) {
                    result.put("instructions", options.instructions());
                }
                result.put("ttlMs", options.toolsListTtlMs());
                result.put("cacheScope", "private");
                break;
            }
            case "tools/list": {
                result = new LinkedHashMap<>();
                result.put("tools", engine.listTools(caller));
                result.put("ttlMs", options.toolsListTtlMs());
                result.put("cacheScope", "private");
                break;
            }
            case "tools/call":
                result = callTool(caller, version, params, true);
                break;
            default: {
                result = contentRequest(caller, version, method, params);
                if (result == null) {
                    throw new RpcError(JsonRpc.METHOD_NOT_FOUND, "method not found: " + method, 404, null);
                }
                if (method.endsWith("/list") || method.equals("resources/read")) {
                    // Resources are live data: never cache. The prompt list is catalog-served like tools.
                    result.put("ttlMs", "prompts/list".equals(method) ? options.toolsListTtlMs() : 0L);
                    result.put("cacheScope", "private");
                }
            }
        }
        result.putIfAbsent("resultType", "complete");
        Map<String, Object> meta = new LinkedHashMap<>();
        Object existing = result.get("_meta");
        if (existing instanceof Map<?, ?> em) {
            for (Map.Entry<?, ?> e : em.entrySet()) {
                meta.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        meta.put(McpVersions.META_SERVER_INFO, serverInfo());
        result.put("_meta", meta);
        return result;
    }

    private Object dispatchLegacy(CallerIdentity caller, String version, String method, Map<String, Object> params) throws RpcError {
        switch (method) {
            case "ping":
                return Map.of();
            case "tools/list":
                return Map.of("tools", engine.listTools(caller));
            case "tools/call":
                return callTool(caller, version, params, false);
            default: {
                Map<String, Object> r = contentRequest(caller, version, method, params);
                if (r == null) {
                    throw new RpcError(JsonRpc.METHOD_NOT_FOUND, "method not found: " + method, 404, null);
                }
                return r;
            }
        }
    }

    /**
     * resources/*, prompts/* and completion/complete. Returns null for methods that are not offered
     * (the caller answers method-not-found), so a gateway without content sources behaves exactly as
     * a tools-only server.
     */
    private Map<String, Object> contentRequest(CallerIdentity caller, String version, String method,
                                               Map<String, Object> params) throws RpcError {
        if (content == null) {
            return null;
        }
        switch (method) {
            case "resources/list":
                return content.offersResources() ? governed(content.listResources(caller, version, trace(params))) : null;
            case "resources/templates/list":
                return content.offersResources() ? governed(content.listResourceTemplates(caller, version, trace(params))) : null;
            case "resources/read": {
                if (!content.offersResources()) {
                    return null;
                }
                Object uri = params.get("uri");
                if (!(uri instanceof String u) || u.isEmpty()) {
                    throw new RpcError(JsonRpc.INVALID_PARAMS, "params.uri must be a non-empty string", 400, null);
                }
                return legacySafe(governed(content.readResource(caller, u, version, trace(params),
                        roundTrip(params, JsonValues.object(params, "_meta"), McpVersions.isModern(version)))), version);
            }
            case "prompts/list":
                if (!content.offersPrompts()) {
                    return null;
                }
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("prompts", content.listPrompts(caller));
                return r;
            case "prompts/get": {
                if (!content.offersPrompts()) {
                    return null;
                }
                Object name = params.get("name");
                if (!(name instanceof String n) || n.isEmpty()) {
                    throw new RpcError(JsonRpc.INVALID_PARAMS, "params.name must be a non-empty string", 400, null);
                }
                Object args = params.get("arguments");
                if (args != null && !(args instanceof Map)) {
                    throw new RpcError(JsonRpc.INVALID_PARAMS, "params.arguments must be an object", 400, null);
                }
                return legacySafe(governed(content.getPrompt(caller, n, JsonValues.asObject(args), version, trace(params),
                        roundTrip(params, JsonValues.object(params, "_meta"), McpVersions.isModern(version)))), version);
            }
            case "completion/complete": {
                if (!content.offersCompletions()) {
                    return null;
                }
                if (!(params.get("ref") instanceof Map) || !(params.get("argument") instanceof Map)) {
                    throw new RpcError(JsonRpc.INVALID_PARAMS, "params.ref and params.argument must be objects", 400, null);
                }
                Map<String, Object> p = new LinkedHashMap<>(params);
                p.remove("_meta");
                return governed(content.complete(caller, p, version, trace(params)));
            }
            default:
                return null;
        }
    }

    /** A 2025 client cannot carry inputResponses: an MRTR result becomes an explanatory error for it. */
    private static Map<String, Object> legacySafe(Map<String, Object> result, String version) throws RpcError {
        if ("input_required".equals(result.get("resultType")) && !McpVersions.isModern(version)) {
            throw new RpcError(JsonRpc.INTERNAL_ERROR, "the backend requires additional client input (multi round-trip request), "
                    + "which protocol version " + version + " cannot carry; use MCP 2026-07-28", 200, null);
        }
        return result;
    }

    /** MRTR retry fields; only 2026 clients can carry them. */
    private static io.github.vaquarkhan.aegis.engine.model.RoundTrip roundTrip(Map<String, Object> params, Map<String, Object> meta,
                                                                         boolean modern) throws RpcError {
        if (!modern) {
            if (params.containsKey("inputResponses") || params.containsKey("requestState")) {
                throw new RpcError(JsonRpc.INVALID_PARAMS, "inputResponses and requestState need MCP 2026-07-28", 400, null);
            }
            return io.github.vaquarkhan.aegis.engine.model.RoundTrip.NONE;
        }
        try {
            return io.github.vaquarkhan.aegis.engine.model.RoundTrip.fromParams(params, meta);
        } catch (IllegalArgumentException e) {
            throw new RpcError(JsonRpc.INVALID_PARAMS, e.getMessage(), 400, null);
        }
    }

    private static String trace(Map<String, Object> params) {
        Map<String, Object> meta = JsonValues.object(params, "_meta");
        return meta == null ? null : JsonValues.string(meta, TraceContext.META_KEY);
    }

    /** Allowed: the result. Denied: JSON-RPC error {@code -32010} with the stable deny code (HTTP 200). */
    private static Map<String, Object> governed(ContentGovernor.Outcome o) throws RpcError {
        if (o.allowed()) {
            return new LinkedHashMap<>(o.result());
        }
        Decision d = o.decision();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", d.code());
        data.put("step", (long) d.step());
        data.put("reason", d.reason());
        data.put("requestId", o.requestId());
        throw new RpcError(JsonRpc.GOVERNANCE_DENIED,
                "Aegis denied this request: " + d.code() + " (step " + d.step() + "). " + d.reason(), 200, data);
    }

    private Map<String, Object> callTool(CallerIdentity caller, String version, Map<String, Object> params, boolean modern) throws RpcError {
        Object name = params.get("name");
        if (!(name instanceof String toolName) || toolName.isEmpty()) {
            throw new RpcError(JsonRpc.INVALID_PARAMS, "params.name must be a non-empty string", 400, null);
        }
        Object args = params.get("arguments");
        if (args != null && !(args instanceof Map)) {
            throw new RpcError(JsonRpc.INVALID_PARAMS, "params.arguments must be an object", 400, null);
        }
        Map<String, Object> meta = JsonValues.object(params, "_meta");
        CallOutcome outcome = engine.call(caller, new ToolCall(null, toolName, JsonValues.asObject(args), meta, version,
                roundTrip(params, meta, modern)));
        Map<String, Object> result = new LinkedHashMap<>(outcome.toMcpResult());
        if (!modern) {
            if ("input_required".equals(result.get("resultType"))) {
                // A 2026 backend asked for more input (MRTR); a 2025 client cannot carry inputResponses.
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("content", List.of(Map.of("type", "text", "text",
                        "The backend requires additional client input (multi round-trip request), "
                                + "which protocol version " + version + " cannot carry. Use MCP 2026-07-28.")));
                err.put("isError", true);
                return err;
            }
            result.remove("resultType");
        }
        return result;
    }

    private Map<String, Object> initializeResult(String version) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("protocolVersion", version);
        r.put("capabilities", capabilities());
        r.put("serverInfo", serverInfo());
        if (options.instructions() != null) {
            r.put("instructions", options.instructions());
        }
        return r;
    }

    private String negotiateLegacy(String requested) {
        List<String> legacy = supportedLegacy();
        if (requested != null && legacy.contains(requested)) {
            return requested;
        }
        return legacy.get(0);
    }

    private List<String> supportedModern() {
        List<String> out = new ArrayList<>();
        for (String v : McpVersions.MODERN) {
            if (options.enabledVersions().contains(v)) {
                out.add(v);
            }
        }
        return out;
    }

    private List<String> supportedLegacy() {
        List<String> out = new ArrayList<>();
        for (String v : McpVersions.LEGACY) {
            if (options.enabledVersions().contains(v)) {
                out.add(v);
            }
        }
        return out;
    }

    /** Only what is actually served is advertised (no subscriptions, no list-changed notifications). */
    private Map<String, Object> capabilities() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("tools", Map.of("listChanged", false));
        if (content != null && content.offersResources()) {
            c.put("resources", Map.of("subscribe", false, "listChanged", false));
        }
        if (content != null && content.offersPrompts()) {
            c.put("prompts", Map.of("listChanged", false));
        }
        if (content != null && content.offersCompletions()) {
            c.put("completions", Map.of());
        }
        return c;
    }

    private Map<String, Object> serverInfo() {
        return Map.of("name", options.serverName(), "version", options.serverVersion());
    }
}
