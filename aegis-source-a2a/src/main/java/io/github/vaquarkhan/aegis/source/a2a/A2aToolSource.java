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
package io.github.vaquarkhan.aegis.source.a2a;

import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.json.JsonException;
import io.github.vaquarkhan.aegis.engine.json.JsonLimits;
import io.github.vaquarkhan.aegis.engine.json.JsonValues;
import io.github.vaquarkhan.aegis.engine.model.ToolDescriptor;
import io.github.vaquarkhan.aegis.engine.model.ToolResult;
import io.github.vaquarkhan.aegis.engine.spi.ExecutionContext;
import io.github.vaquarkhan.aegis.engine.spi.ToolExecutor;
import io.github.vaquarkhan.aegis.engine.spi.ToolSource;
import io.github.vaquarkhan.aegis.mcp.http.BoundedHttp;
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
import java.util.Objects;
import java.util.UUID;

/**
 * One A2A agent as one governed tool. Discovery reads the agent card; a call sends one A2A 1.0
 * JSON-RPC {@code SendMessage} and returns the agent's reply as text.
 *
 * <p>Why one tool per agent and not one per skill: an A2A message has no field that selects a skill.
 * The agent decides what to do from the message content, so a tool named after a "read" skill could
 * still trigger the agent's write skill. The governed unit is therefore the agent endpoint, and the
 * operator classifies it by the strongest thing the agent can do. The skills are listed in the tool
 * description so reviewers and models can see them.
 *
 * <p>Trust rules: the card is untrusted input from the agent. The JSON-RPC endpoint it advertises must
 * be on the card's own origin, or exactly the {@code endpointUrl} the operator pinned; the endpoint is
 * part of the tool description, so a card that moves its endpoint changes the definition digest and
 * the tool is quarantined until a person accepts it. Card signatures are not verified. Every call also
 * passes the engine's egress allow list. Redirects are never followed.
 */
final class A2aToolSource implements ToolSource {

    static final String PROTOCOL_VERSION = "1.0";
    private static final int MAX_CARD_BYTES = 256 * 1024;
    private static final int MAX_DESCRIPTION = 4000;

    private final String id;
    private final URI cardUrl;
    private final URI pinnedEndpoint;
    private final String toolName;
    private final int maxTextChars;
    private final int maxResponseBytes;
    private final long cardTimeoutMillis;
    private final HttpClient http;

    A2aToolSource(String id, URI cardUrl, URI pinnedEndpoint, String toolName, int maxTextChars,
                  int maxResponseBytes, long cardTimeoutMillis) {
        this.id = id;
        this.cardUrl = cardUrl;
        this.pinnedEndpoint = pinnedEndpoint;
        this.toolName = toolName;
        this.maxTextChars = maxTextChars;
        this.maxResponseBytes = maxResponseBytes;
        this.cardTimeoutMillis = cardTimeoutMillis;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(Math.min(cardTimeoutMillis, 5_000)))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public List<SourcedTool> discover() throws Exception {
        Map<String, Object> card = fetchCard();
        String name = text(card.get("name"));
        if (name == null || name.isBlank()) {
            throw new IOException("agent card at " + cardUrl + " has no name");
        }
        Map<String, Object> iface = jsonRpcInterface(card);
        URI endpoint = endpoint(iface);
        String tenant = text(iface.get("tenant"));

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("text", Map.of("type", "string", "minLength", 1L, "maxLength", (long) maxTextChars,
                "description", "The message to send to the agent."));
        props.put("data", Map.of("type", "object",
                "description", "Optional structured input, sent as an A2A data part."));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("text"));
        schema.put("additionalProperties", false);

        ToolDescriptor d = new ToolDescriptor(toolName, name, description(card, name, endpoint), schema, null, null);
        return List.of(new SourcedTool(d, new Executor(endpoint, tenant)));
    }

    private Map<String, Object> fetchCard() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(cardUrl).timeout(Duration.ofMillis(cardTimeoutMillis))
                .header("Accept", "application/json").GET().build();
        BoundedHttp.Result r;
        try {
            r = BoundedHttp.send(http, req, MAX_CARD_BYTES, cardTimeoutMillis);
        } catch (HttpTimeoutException e) {
            throw new IOException("agent card at " + cardUrl + " did not arrive within " + cardTimeoutMillis + "ms", e);
        }
        if (r.status() != 200) {
            throw new IOException("agent card at " + cardUrl + " answered HTTP " + r.status());
        }
        try {
            return Json.parseObject(r.body(), JsonLimits.DEFAULT);
        } catch (JsonException | IllegalArgumentException e) {
            throw new IOException("agent card at " + cardUrl + " is not a JSON object: " + e.getMessage(), e);
        }
    }

    /** The first JSON-RPC interface of protocol 1.x; A2A 0.3 cards and other bindings are refused. */
    private Map<String, Object> jsonRpcInterface(Map<String, Object> card) throws IOException {
        List<Object> interfaces = JsonValues.asList(card.get("supportedInterfaces"));
        List<String> seen = new ArrayList<>();
        if (interfaces != null) {
            for (Object o : interfaces) {
                Map<String, Object> i = JsonValues.asObject(o);
                if (i == null) {
                    continue;
                }
                String binding = text(i.get("protocolBinding"));
                String version = text(i.get("protocolVersion"));
                seen.add(binding + " " + version);
                if ("JSONRPC".equalsIgnoreCase(binding) && version != null && version.startsWith("1.")) {
                    return i;
                }
            }
        }
        String legacy = text(card.get("protocolVersion"));
        throw new IOException("agent card at " + cardUrl + " offers no A2A 1.x JSON-RPC interface (found "
                + (seen.isEmpty() ? "none" : String.join(", ", seen)) + (legacy == null ? "" : "; card protocolVersion " + legacy)
                + "); Aegis speaks A2A " + PROTOCOL_VERSION + " over JSON-RPC");
    }

    private URI endpoint(Map<String, Object> iface) throws IOException {
        String raw = text(iface.get("url"));
        URI u;
        try {
            u = raw == null ? null : URI.create(raw);
        } catch (IllegalArgumentException e) {
            u = null;
        }
        if (u == null || u.getScheme() == null || u.getHost() == null
                || !List.of("http", "https").contains(u.getScheme().toLowerCase(Locale.ROOT)) || u.getRawUserInfo() != null) {
            throw new IOException("agent card at " + cardUrl + " advertises an endpoint that is not an absolute http(s) URL");
        }
        if (pinnedEndpoint != null) {
            if (!sameUrl(u, pinnedEndpoint)) {
                throw new IOException("agent card at " + cardUrl + " points calls at " + u
                        + ", not at the pinned settings.endpointUrl " + pinnedEndpoint);
            }
            return pinnedEndpoint;
        }
        if (!sameOrigin(u, cardUrl)) {
            throw new IOException("agent card at " + cardUrl + " points calls at " + u + ", which is not the card's own origin;"
                    + " set settings.endpointUrl to that URL to accept it");
        }
        return u;
    }

    static boolean sameOrigin(URI a, URI b) {
        return a.getScheme().equalsIgnoreCase(b.getScheme()) && a.getHost().equalsIgnoreCase(b.getHost())
                && port(a) == port(b);
    }

    static boolean sameUrl(URI a, URI b) {
        return sameOrigin(a, b) && Objects.equals(trimSlash(a.getRawPath()), trimSlash(b.getRawPath()))
                && Objects.equals(a.getRawQuery(), b.getRawQuery());
    }

    private static int port(URI u) {
        return u.getPort() >= 0 ? u.getPort() : "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
    }

    private static String trimSlash(String p) {
        String s = p == null ? "" : p;
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String description(Map<String, Object> card, String name, URI endpoint) {
        StringBuilder sb = new StringBuilder("Sends one message to the A2A agent \"").append(oneLine(name)).append("\" and returns its reply.");
        String desc = text(card.get("description"));
        if (desc != null && !desc.isBlank()) {
            sb.append(" Agent description: ").append(oneLine(desc));
        }
        List<Object> skills = JsonValues.asList(card.get("skills"));
        if (skills != null && !skills.isEmpty()) {
            sb.append(" Skills:");
            for (Object o : skills) {
                Map<String, Object> s = JsonValues.asObject(o);
                if (s == null) {
                    continue;
                }
                String sid = text(s.get("id"));
                String sname = text(s.get("name"));
                String sdesc = text(s.get("description"));
                sb.append(" [").append(oneLine(sname != null ? sname : String.valueOf(sid))).append(']');
                if (sdesc != null && !sdesc.isBlank()) {
                    sb.append(' ').append(oneLine(sdesc));
                }
                sb.append(';');
            }
        }
        sb.append(" Calls go to ").append(endpoint).append('.');
        String out = sb.toString();
        return out.length() > MAX_DESCRIPTION ? out.substring(0, MAX_DESCRIPTION) + "..." : out;
    }

    private static String oneLine(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    static String text(Object o) {
        return o instanceof String s ? s : null;
    }

    /** Sends to the endpoint fixed at discovery; a new card means a new executor. */
    final class Executor implements ToolExecutor {

        private final URI endpoint;
        private final String tenant;

        Executor(URI endpoint, String tenant) {
            this.endpoint = endpoint;
            this.tenant = tenant;
        }

        URI endpoint() {
            return endpoint;
        }

        @Override
        public List<URI> egressTargets(Map<String, Object> arguments) {
            return List.of(endpoint);
        }

        @Override
        public ToolResult execute(ExecutionContext ctx) throws ExecutionException {
            if (ctx.dryRun()) {
                // A2A has no preview call. Sending the message would run it, so nothing is sent.
                return ToolResult.text("This agent offers no preview; nothing was sent.", true);
            }
            Object text = ctx.arguments().get("text");
            if (!(text instanceof String t) || t.isEmpty() || t.codePointCount(0, t.length()) > maxTextChars) {
                return ToolResult.text("Invalid arguments: text must be a non-empty string of at most " + maxTextChars + " characters", true);
            }
            List<Object> parts = new ArrayList<>();
            parts.add(Map.of("text", t));
            Object data = ctx.arguments().get("data");
            if (data != null) {
                if (!(data instanceof Map)) {
                    return ToolResult.text("Invalid arguments: data must be an object", true);
                }
                parts.add(Map.of("data", data));
            }
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("messageId", UUID.randomUUID().toString());
            message.put("role", "ROLE_USER");
            message.put("parts", parts);
            Map<String, Object> params = new LinkedHashMap<>();
            if (tenant != null && !tenant.isEmpty()) {
                params.put("tenant", tenant);
            }
            params.put("message", message);
            Map<String, Object> rpc = new LinkedHashMap<>();
            rpc.put("jsonrpc", "2.0");
            rpc.put("id", ctx.requestId() == null ? UUID.randomUUID().toString() : ctx.requestId());
            rpc.put("method", "SendMessage");
            rpc.put("params", params);

            HttpRequest.Builder b = HttpRequest.newBuilder(endpoint).timeout(Duration.ofMillis(ctx.timeoutMillis()))
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .header("A2A-Version", PROTOCOL_VERSION);
            ctx.credentials().forEach(b::header);
            b.setHeader("traceparent", ctx.traceparent());
            b.POST(HttpRequest.BodyPublishers.ofString(Json.write(rpc), StandardCharsets.UTF_8));
            BoundedHttp.Result r;
            try {
                r = BoundedHttp.send(http, b.build(), maxResponseBytes, ctx.timeoutMillis());
            } catch (HttpTimeoutException e) {
                throw new ExecutionException("agent timed out", true, e);
            } catch (IOException e) {
                throw new ExecutionException("agent call failed: " + e.getClass().getSimpleName(), false, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ExecutionException("interrupted", false, e);
            }
            if (r.status() >= 500 || r.status() == 429) {
                throw new ExecutionException("agent returned HTTP " + r.status());
            }
            if (r.status() != 200) {
                return ToolResult.text("Agent returned HTTP " + r.status(), true);
            }
            Map<String, Object> reply;
            try {
                reply = Json.parseObject(r.body(), JsonLimits.DEFAULT);
            } catch (JsonException | IllegalArgumentException e) {
                return ToolResult.text("Agent reply is not JSON-RPC JSON", true);
            }
            return render(reply);
        }
    }

    /** Turns a SendMessage JSON-RPC reply into tool text. Never forwards the raw task history. */
    static ToolResult render(Map<String, Object> reply) {
        Map<String, Object> error = JsonValues.object(reply, "error");
        if (error != null) {
            Object code = error.get("code");
            String msg = text(error.get("message"));
            return ToolResult.text("Agent returned error " + code + (msg == null ? "" : ": " + oneLine(msg)), true);
        }
        Map<String, Object> result = JsonValues.object(reply, "result");
        if (result == null) {
            return ToolResult.text("Agent reply has neither result nor error", true);
        }
        Map<String, Object> message = JsonValues.object(result, "message");
        if (message != null) {
            return ToolResult.text(partsText(JsonValues.asList(message.get("parts"))), false);
        }
        Map<String, Object> task = JsonValues.object(result, "task");
        if (task == null) {
            return ToolResult.text("Agent reply has neither a message nor a task", true);
        }
        Map<String, Object> status = JsonValues.object(task, "status");
        String state = status == null ? null : text(status.get("state"));
        StringBuilder sb = new StringBuilder();
        List<Object> artifacts = JsonValues.asList(task.get("artifacts"));
        if (artifacts != null) {
            for (Object a : artifacts) {
                Map<String, Object> am = JsonValues.asObject(a);
                if (am != null) {
                    append(sb, partsText(JsonValues.asList(am.get("parts"))));
                }
            }
        }
        Map<String, Object> statusMessage = status == null ? null : JsonValues.object(status, "message");
        if (statusMessage != null) {
            append(sb, partsText(JsonValues.asList(statusMessage.get("parts"))));
        }
        String body = sb.toString();
        if ("TASK_STATE_COMPLETED".equals(state)) {
            return ToolResult.text(body, false);
        }
        String why = switch (state == null ? "" : state) {
            case "TASK_STATE_FAILED", "TASK_STATE_REJECTED", "TASK_STATE_CANCELED" -> "the agent task ended as " + state;
            case "TASK_STATE_INPUT_REQUIRED", "TASK_STATE_AUTH_REQUIRED" -> "the agent asked for more (" + state
                    + "); Aegis sends one message per call and does not continue a task";
            default -> "the agent task did not finish in this call (state " + state + ")";
        };
        return ToolResult.text(body.isEmpty() ? why : why + ": " + body, true);
    }

    private static void append(StringBuilder sb, String s) {
        if (s.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append(s);
    }

    private static String partsText(List<Object> parts) {
        StringBuilder sb = new StringBuilder();
        if (parts == null) {
            return "";
        }
        for (Object p : parts) {
            Map<String, Object> m = JsonValues.asObject(p);
            if (m == null) {
                continue;
            }
            String piece;
            if (m.get("text") instanceof String t) {
                piece = t;
            } else if (m.containsKey("data")) {
                piece = Json.write(m.get("data"));
            } else if (m.get("url") instanceof String u) {
                piece = "[file at " + u + "]";
            } else if (m.containsKey("raw")) {
                piece = "[binary part omitted]";
            } else {
                continue;
            }
            append(sb, piece);
        }
        return sb.toString();
    }
}
