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
package io.github.vaquarkhan.aegis.mcp.testing;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.json.JsonValues;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Handler-independent, minimal MCP test server used for the "existing MCP server" path. It does not
 * call Aegis's production `McpServerHandler`, so client and proxy behavior are not validated against
 * the handler itself. It does reuse the engine's strict JSON parser; it is deterministic protocol
 * coverage, not an external implementation or interoperability certification.
 *
 * <p>Modes: {@link Mode#MODERN} (2026-07-28 stateless, validates mirrored headers),
 * {@link Mode#LEGACY_SESSION} (2025-11-25 with Mcp-Session-Id), {@link Mode#LEGACY_STATELESS}
 * (2025-06-18 without sessions). Optionally answers with SSE, or injects a server-to-client request.
 */
public final class TestMcpServer implements AutoCloseable {

    public enum Mode { MODERN, LEGACY_SESSION, LEGACY_STATELESS }

    private final HttpServer server;
    private final Mode mode;
    private final List<Map<String, Object>> tools = new CopyOnWriteArrayList<>();
    private final Map<String, Function<Map<String, Object>, Map<String, Object>>> handlers = new ConcurrentHashMap<>();
    private final Map<String, Boolean> sessions = new ConcurrentHashMap<>();
    public final List<Map<String, Object>> received = Collections.synchronizedList(new ArrayList<>());
    public final List<Map<String, String>> receivedHeaders = Collections.synchronizedList(new ArrayList<>());
    public volatile boolean respondWithSse;
    public volatile boolean injectServerRequest;
    public volatile int padResponseBytes;
    public static final int PAGE_SIZE = 2;
    private final Map<String, Map<String, Object>> resources = Collections.synchronizedMap(new LinkedHashMap<>());
    private final List<Map<String, Object>> templates = new CopyOnWriteArrayList<>();
    private final Map<String, Map<String, Object>> prompts = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, List<String>> completionValues = new ConcurrentHashMap<>();
    /** When set, resources/read returns this function's result (to simulate a misbehaving server). */
    public volatile Function<String, Map<String, Object>> readOverride;
    /** When set, resources/read is answered from the full params (for multi round-trip tests). */
    public volatile Function<Map<String, Object>, Map<String, Object>> readWithParams;
    private final Map<String, Function<Map<String, Object>, Map<String, Object>>> paramHandlers = new ConcurrentHashMap<>();

    public TestMcpServer(Mode mode) throws IOException {
        this.mode = mode;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", this::handle);
        server.createContext("/redirect", ex -> {
            ex.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data");
            // Content-length 0: empty body. -1 left the stream half-open and hid the 307 from the client.
            ex.sendResponseHeaders(307, 0);
            ex.close();
        });
        server.start();
    }

    public URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
    }

    public URI redirectUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/redirect");
    }

    public TestMcpServer tool(String name, String description, Map<String, Object> inputSchema,
                              Function<Map<String, Object>, Map<String, Object>> handler) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("name", name);
        t.put("description", description);
        t.put("inputSchema", inputSchema);
        tools.removeIf(x -> name.equals(x.get("name")));
        tools.add(t);
        handlers.put(name, handler);
        return this;
    }

    /** A tool whose handler sees the full params (inputResponses, requestState, _meta): for MRTR tests. */
    public TestMcpServer toolWithParams(String name, String description, Function<Map<String, Object>, Map<String, Object>> handler) {
        tool(name, description, Map.of("type", "object"), a -> Map.of());
        paramHandlers.put(name, handler);
        return this;
    }

    /** Sets MCP annotations (hints) on a tool. */
    public void annotate(String name, Map<String, Object> annotations) {
        for (Map<String, Object> t : tools) {
            if (name.equals(t.get("name"))) {
                t.put("annotations", annotations);
            }
        }
    }

    /** Simulates a rug pull: changes a tool's description in place. */
    public void redefine(String name, String newDescription) {
        for (Map<String, Object> t : tools) {
            if (name.equals(t.get("name"))) {
                t.put("description", newDescription);
            }
        }
    }

    public void expireSessions() {
        sessions.clear();
    }

    /** Adds a text resource. Lists are served in pages of {@link #PAGE_SIZE} to exercise cursors. */
    public TestMcpServer resource(String uri, String name, String mimeType, String text) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("uri", uri);
        r.put("name", name);
        r.put("mimeType", mimeType);
        r.put("text", text);
        resources.put(uri, r);
        return this;
    }

    /** Adds a binary resource (base64 blob). */
    public TestMcpServer blobResource(String uri, String name, String mimeType, String base64) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("uri", uri);
        r.put("name", name);
        r.put("mimeType", mimeType);
        r.put("blob", base64);
        resources.put(uri, r);
        return this;
    }

    public TestMcpServer template(String uriTemplate, String name) {
        templates.add(Map.of("uriTemplate", uriTemplate, "name", name));
        return this;
    }

    /**
     * Adds a prompt. {@code messageTemplate} is returned as one user message with {@code {arg}}
     * placeholders substituted; {@code arguments} are {@code name -> required}.
     */
    public TestMcpServer prompt(String name, String description, Map<String, Boolean> arguments, String messageTemplate) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("description", description);
        List<Map<String, Object>> args = new ArrayList<>();
        arguments.forEach((k, v) -> args.add(Map.of("name", k, "required", v)));
        p.put("arguments", args);
        p.put("messages", messageTemplate);
        prompts.put(name, p);
        return this;
    }

    /** Simulates a prompt rug pull: changes the prompt's description in place. */
    public void redefinePrompt(String name, String newDescription) {
        Map<String, Object> p = prompts.get(name);
        if (p != null) {
            p.put("description", newDescription);
        }
    }

    public TestMcpServer completions(String argumentName, List<String> values) {
        completionValues.put(argumentName, List.copyOf(values));
        return this;
    }

    private static Map<String, Object> page(List<Map<String, Object>> all, String key, Map<String, Object> params) {
        int start = params.get("cursor") == null ? 0 : Integer.parseInt(String.valueOf(params.get("cursor")));
        int end = Math.min(all.size(), start + PAGE_SIZE);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put(key, new ArrayList<>(all.subList(start, end)));
        if (end < all.size()) {
            r.put("nextCursor", String.valueOf(end));
        }
        return r;
    }

    public static Map<String, Object> textResult(String text) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("content", List.of(Map.of("type", "text", "text", text)));
        r.put("isError", false);
        return r;
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            if (!"POST".equals(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            Map<String, String> h = new LinkedHashMap<>();
            ex.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(), v.get(0)));
            receivedHeaders.add(h);
            Map<String, Object> msg = Json.parseObject(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            received.add(msg);
            String method = (String) msg.get("method");
            Object id = msg.get("id");
            Map<String, Object> params = JsonValues.objectOrEmpty(msg, "params");
            Map<String, Object> meta = JsonValues.object(params, "_meta");
            boolean modernRequest = meta != null && meta.containsKey("io.modelcontextprotocol/protocolVersion");

            if (mode == Mode.MODERN) {
                if (!modernRequest) {
                    // A modern-only server rejects legacy traffic; body is not a modern error, per spec example.
                    send(ex, 400, "application/json", "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32600,\"message\":\"missing _meta\"}}");
                    return;
                }
                String nameHeader = switch (method) {
                    case "tools/call", "prompts/get" -> String.valueOf(params.get("name"));
                    case "resources/read" -> String.valueOf(params.get("uri"));
                    default -> null;
                };
                if (!"2026-07-28".equals(h.get("mcp-protocol-version")) || !method.equals(h.get("mcp-method"))
                        || (nameHeader != null && !nameHeader.equals(h.get("mcp-name")))) {
                    send(ex, 400, "application/json", "{\"jsonrpc\":\"2.0\",\"id\":" + Json.write(id)
                            + ",\"error\":{\"code\":-32020,\"message\":\"header mismatch\"}}");
                    return;
                }
            } else {
                if (modernRequest && "server/discover".equals(method)) {
                    // Legacy server: unknown method, answered with a non-modern error.
                    send(ex, 400, "application/json", "{\"jsonrpc\":\"2.0\",\"id\":" + Json.write(id)
                            + ",\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}");
                    return;
                }
                if (mode == Mode.LEGACY_SESSION && !"initialize".equals(method)) {
                    String sid = h.get("mcp-session-id");
                    if (sid == null || !sessions.containsKey(sid)) {
                        send(ex, sid == null ? 400 : 404, "application/json", "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32600,\"message\":\"bad session\"}}");
                        return;
                    }
                }
            }
            if (!msg.containsKey("id")) {
                ex.sendResponseHeaders(202, -1);
                return;
            }
            Object result;
            switch (method) {
                case "server/discover" -> result = Map.of("resultType", "complete", "supportedVersions", List.of("2026-07-28"),
                        "capabilities", Map.of("tools", Map.of()), "ttlMs", 0L, "cacheScope", "public");
                case "initialize" -> {
                    String sid = UUID.randomUUID().toString();
                    if (mode == Mode.LEGACY_SESSION) {
                        sessions.put(sid, true);
                        ex.getResponseHeaders().add("Mcp-Session-Id", sid);
                    }
                    result = Map.of("protocolVersion", mode == Mode.LEGACY_SESSION ? "2025-11-25" : "2025-06-18",
                            "capabilities", Map.of("tools", Map.of()), "serverInfo", Map.of("name", "test-server", "version", "1"));
                }
                case "tools/list" -> result = mode == Mode.MODERN
                        ? Map.of("tools", tools, "resultType", "complete", "ttlMs", 0L, "cacheScope", "public")
                        : Map.of("tools", tools);
                case "tools/call" -> {
                    Function<Map<String, Object>, Map<String, Object>> fn = handlers.get(params.get("name"));
                    Function<Map<String, Object>, Map<String, Object>> pfn = paramHandlers.get(String.valueOf(params.get("name")));
                    if (pfn != null) {
                        Map<String, Object> pr = new LinkedHashMap<>(pfn.apply(params));
                        if (mode == Mode.MODERN) {
                            pr.putIfAbsent("resultType", "complete");
                        }
                        send(ex, 200, "application/json", Json.write(Map.of("jsonrpc", "2.0", "id", id, "result", pr)));
                        return;
                    }
                    if (fn == null) {
                        send(ex, 200, "application/json", Json.write(Map.of("jsonrpc", "2.0", "id", id,
                                "error", Map.of("code", -32602L, "message", "Unknown tool"))));
                        return;
                    }
                    Map<String, Object> r = new LinkedHashMap<>(fn.apply(JsonValues.objectOrEmpty(params, "arguments")));
                    if (padResponseBytes > 0) {
                        r.put("padding", "x".repeat(padResponseBytes));
                    }
                    if (mode == Mode.MODERN) {
                        r.putIfAbsent("resultType", "complete");
                    }
                    result = r;
                }
                case "resources/list" -> result = page(resources.values().stream().map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>(r);
                    m.remove("text");
                    m.remove("blob");
                    return m;
                }).toList(), "resources", params);
                case "resources/templates/list" -> result = page(templates, "resourceTemplates", params);
                case "resources/read" -> {
                    String uri = String.valueOf(params.get("uri"));
                    Map<String, Object> r = resources.get(uri);
                    if (readWithParams != null) {
                        result = readWithParams.apply(params);
                    } else if (readOverride != null) {
                        result = readOverride.apply(uri);
                    } else if (r == null) {
                        send(ex, 200, "application/json", Json.write(Map.of("jsonrpc", "2.0", "id", id,
                                "error", Map.of("code", -32002L, "message", "Resource not found"))));
                        return;
                    } else {
                        Map<String, Object> c = new LinkedHashMap<>();
                        c.put("uri", uri);
                        c.put("mimeType", r.get("mimeType"));
                        if (r.containsKey("blob")) {
                            c.put("blob", r.get("blob"));
                        } else {
                            c.put("text", r.get("text"));
                        }
                        result = Map.of("contents", List.of(c));
                    }
                }
                case "prompts/list" -> result = page(prompts.values().stream().map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>(p);
                    m.remove("messages");
                    return m;
                }).toList(), "prompts", params);
                case "prompts/get" -> {
                    Map<String, Object> p = prompts.get(String.valueOf(params.get("name")));
                    if (p == null) {
                        send(ex, 200, "application/json", Json.write(Map.of("jsonrpc", "2.0", "id", id,
                                "error", Map.of("code", -32602L, "message", "Unknown prompt"))));
                        return;
                    }
                    Map<String, Object> args = JsonValues.objectOrEmpty(params, "arguments");
                    String text = String.valueOf(p.get("messages"));
                    for (Map.Entry<String, Object> a : args.entrySet()) {
                        text = text.replace("{" + a.getKey() + "}", String.valueOf(a.getValue()));
                    }
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("description", p.get("description"));
                    r.put("messages", List.of(Map.of("role", "user", "content", Map.of("type", "text", "text", text))));
                    result = r;
                }
                case "completion/complete" -> {
                    Map<String, Object> argument = JsonValues.objectOrEmpty(params, "argument");
                    String prefix = String.valueOf(argument.get("value"));
                    List<String> vs = completionValues.getOrDefault(String.valueOf(argument.get("name")), List.of())
                            .stream().filter(v -> v.startsWith(prefix)).toList();
                    result = Map.of("completion", Map.of("values", vs, "total", (long) vs.size(), "hasMore", false));
                }
                default -> {
                    send(ex, 404, "application/json", Json.write(Map.of("jsonrpc", "2.0", "id", id,
                            "error", Map.of("code", -32601L, "message", "Method not found"))));
                    return;
                }
            }
            if (mode == Mode.MODERN && result instanceof Map<?, ?> rm && !rm.containsKey("resultType")) {
                Map<String, Object> withType = new LinkedHashMap<>();
                rm.forEach((k, v) -> withType.put(String.valueOf(k), v));
                withType.put("resultType", "complete");
                result = withType;
            }
            String response = Json.write(Map.of("jsonrpc", "2.0", "id", id, "result", result));
            if (respondWithSse && "tools/call".equals(method)) {
                StringBuilder sse = new StringBuilder();
                sse.append("event: message\ndata: ").append(Json.write(Map.of("jsonrpc", "2.0", "method", "notifications/progress",
                        "params", Map.of("progressToken", "p", "progress", 1L)))).append("\n\n");
                if (injectServerRequest) {
                    sse.append("data: ").append(Json.write(Map.of("jsonrpc", "2.0", "id", 99L, "method", "sampling/createMessage",
                            "params", Map.of()))).append("\n\n");
                }
                sse.append("data: ").append(response).append("\n\n");
                send(ex, 200, "text/event-stream", sse.toString());
            } else {
                send(ex, 200, "application/json", response);
            }
        } catch (RuntimeException e) {
            send(ex, 500, "text/plain", "test server failure: " + e);
        }
    }

    private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
