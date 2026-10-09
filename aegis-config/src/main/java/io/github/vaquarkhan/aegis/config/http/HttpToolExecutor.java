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
package io.github.vaquarkhan.aegis.config.http;

import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.json.JsonException;
import io.github.vaquarkhan.aegis.engine.json.JsonLimits;
import io.github.vaquarkhan.aegis.engine.json.JsonValues;
import io.github.vaquarkhan.aegis.engine.model.ToolResult;
import io.github.vaquarkhan.aegis.engine.spi.ExecutionContext;
import io.github.vaquarkhan.aegis.engine.spi.ToolExecutor;
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
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes a {@link HttpToolSpec}. Redirects are never followed (a redirect could reach a
 * destination the egress control never saw). 5xx and transport failures count against the circuit
 * breaker; 4xx are returned to the model as tool errors.
 */
public final class HttpToolExecutor implements ToolExecutor {

    /** {@code {arg}} is one path segment; {@code {arg/}} is a path-typed value of several segments. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_.-]+)(/)?}");
    private static final Pattern EXACT = Pattern.compile("^\\{([A-Za-z0-9_.*-]+)}$");
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> RESTRICTED = Set.of("host", "content-length", "connection", "upgrade", "expect");

    private final URI baseUrl;
    private final HttpToolSpec spec;
    private final HttpClient http;

    public HttpToolExecutor(URI baseUrl, HttpToolSpec spec, HttpClient http) {
        if (!METHODS.contains(spec.method())) {
            throw new IllegalArgumentException("unsupported HTTP method " + spec.method());
        }
        for (String h : spec.headers().keySet()) {
            if (RESTRICTED.contains(h.toLowerCase(Locale.ROOT)) || "authorization".equals(h.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("header '" + h + "' cannot be set statically (use credentials for Authorization)");
            }
        }
        this.baseUrl = baseUrl;
        this.spec = spec;
        this.http = http;
    }

    /** Placeholder names used in the path template (config validation requires them in inputSchema.required). */
    public static List<String> pathPlaceholders(String path) {
        List<String> out = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(path);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    @Override
    public List<URI> egressTargets(Map<String, Object> args) {
        return List.of(render(args));
    }

    /** Builds the exact URI for these arguments. */
    public URI render(Map<String, Object> args) {
        StringBuilder path = new StringBuilder();
        Matcher m = PLACEHOLDER.matcher(spec.path());
        int last = 0;
        while (m.find()) {
            path.append(spec.path(), last, m.start());
            Object v = args.get(m.group(1));
            String s = scalar(v);
            if (s == null) {
                throw new IllegalArgumentException("path argument '" + m.group(1) + "' is missing or not a scalar");
            }
            path.append(m.group(2) == null ? segment(m.group(1), s) : segments(m.group(1), s));
            last = m.end();
        }
        path.append(spec.path().substring(last));
        StringBuilder q = new StringBuilder();
        for (Map.Entry<String, String> e : spec.query().entrySet()) {
            Matcher em = EXACT.matcher(e.getValue());
            String value;
            if (em.matches()) {
                Object v = args.get(em.group(1));
                if (v == null) {
                    continue;
                }
                value = scalar(v);
                if (value == null) {
                    throw new IllegalArgumentException("query argument '" + em.group(1) + "' must be a scalar");
                }
            } else {
                value = e.getValue();
            }
            q.append(q.length() == 0 ? "" : "&").append(encode(e.getKey())).append('=').append(encode(value));
        }
        String base = baseUrl.toString().replaceAll("/+$", "");
        return URI.create(base + path + (q.length() == 0 ? "" : "?" + q));
    }

    @Override
    public ToolResult execute(ExecutionContext ctx) throws ExecutionException {
        URI uri;
        try {
            uri = render(ctx.arguments());
        } catch (IllegalArgumentException e) {
            return ToolResult.text("Invalid arguments: " + e.getMessage(), true);
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(ctx.timeoutMillis()))
                .header("Accept", "application/json");
        spec.headers().forEach(b::header);
        ctx.credentials().forEach(b::header);
        b.setHeader("traceparent", ctx.traceparent()); // W3C trace context: the backend joins the caller's trace
        HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.noBody();
        if (spec.body() != null) {
            body = HttpRequest.BodyPublishers.ofString(Json.write(fill(spec.body(), ctx.arguments())), StandardCharsets.UTF_8);
            b.header("Content-Type", "application/json");
        }
        b.method(spec.method(), body);
        BoundedHttp.Result resp;
        byte[] data;
        try {
            resp = BoundedHttp.send(http, b.build(), spec.maxResponseBytes(), ctx.timeoutMillis());
            data = resp.body();
        } catch (HttpTimeoutException e) {
            throw new ExecutionException("backend timed out", true, e);
        } catch (IOException e) {
            throw new ExecutionException("backend call failed: " + e.getClass().getSimpleName() + " " + safeMessage(e), false, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExecutionException("interrupted", false, e);
        }
        int status = resp.status();
        String text = new String(data, StandardCharsets.UTF_8);
        if (status >= 500 || status == 429) {
            throw new ExecutionException("backend returned HTTP " + status);
        }
        if (status >= 300) {
            return ToolResult.text("Backend returned HTTP " + status + ": " + truncate(text), true);
        }
        String ct = resp.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        boolean needsJson = spec.responseStatusCheck() != null || (ct.contains("json") && !text.isBlank());
        if (needsJson) {
            try {
                Object parsed = Json.parse(data, JsonLimits.CONFIG.withMaxBytes(spec.maxResponseBytes()));
                HttpToolSpec.ResponseStatusCheck statusCheck = spec.responseStatusCheck();
                if (statusCheck != null) {
                    Optional<Object> applicationStatus = statusCheck.pointer().resolve(parsed);
                    Long actual = applicationStatus.map(JsonValues::integral).orElse(null);
                    if (actual == null || actual.longValue() != statusCheck.equals()) {
                        // Do not leak an embedded backend error body to the model. This is a backend failure,
                        // even though the HTTP transport itself returned 200 (for example Jolokia/Ignite).
                        throw new ExecutionException("backend response failed configured status validation", false, false, null);
                    }
                }
                if (ct.contains("json") && !text.isBlank()) {
                    Object selected = parsed;
                    if (spec.responsePointer() != null) {
                        Optional<Object> sel = spec.responsePointer().resolve(parsed);
                        selected = sel.orElse(null);
                    }
                    String out = Json.write(selected);
                    Object structured = selected instanceof Map ? selected : Map.of("value", selected == null ? "" : selected);
                    return ToolResult.structured(out, structured);
                }
            } catch (JsonException e) {
                if (spec.responseStatusCheck() != null) {
                    throw new ExecutionException("backend response failed configured status validation", false, false, e);
                }
                return ToolResult.text("Backend returned invalid JSON (" + e.getMessage() + ")", true);
            }
        }
        return ToolResult.text(text.isEmpty() ? "HTTP " + status : text, false);
    }

    /** Fills a body template with argument values (type-preserving; no string splicing). */
    static Object fill(Object template, Map<String, Object> args) {
        if (template instanceof String s) {
            Matcher m = EXACT.matcher(s);
            if (m.matches()) {
                return "*".equals(m.group(1)) ? args : args.get(m.group(1));
            }
            return s;
        }
        if (template instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                Object v = fill(e.getValue(), args);
                boolean placeholder = e.getValue() instanceof String sv && EXACT.matcher(sv).matches();
                if (v == null && placeholder) {
                    continue; // absent optional argument: omit the field
                }
                out.put(String.valueOf(e.getKey()), v);
            }
            return out;
        }
        if (template instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object o : l) {
                out.add(fill(o, args));
            }
            return out;
        }
        return template;
    }

    /**
     * One path segment. Separators and escapes are refused outright instead of relying on
     * percent-encoding alone: some backends decode {@code %2F} before routing, which would let
     * {@code "a/../admin"} change the route.
     */
    private static String segment(String name, String s) {
        if (s.isEmpty() || s.contains("..") || ".".equals(s)
                || s.indexOf('/') >= 0 || s.indexOf('\\') >= 0 || s.indexOf('%') >= 0) {
            throw new IllegalArgumentException("path argument '" + name + "' is missing or not a safe path segment");
        }
        return encode(s);
    }

    /**
     * A path-typed value ({@code {arg/}}): several segments, for APIs whose resource identifier is
     * itself a path (HDFS files, ZooKeeper znodes, object-store keys). Every segment is validated and
     * encoded individually by {@link #segment}, so this accepts a deeper path than {@code {arg}} without
     * accepting a traversal: {@code /user/hive/warehouse} is fine, {@code /user/../etc} is refused.
     * Leading and trailing slashes are normalised away, so {@code "/"} addresses the root.
     */
    private static String segments(String name, String s) {
        String trimmed = s;
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String part : trimmed.split("/", -1)) {
            sb.append(sb.length() == 0 ? "" : "/").append(segment(name, part));
        }
        return sb.toString();
    }

    private static String scalar(Object v) {
        if (v instanceof String s) {
            return s;
        }
        if (v instanceof Long || v instanceof Integer || v instanceof Boolean) {
            return v.toString();
        }
        return null;
    }

    /** RFC 3986 percent-encoding of everything except unreserved characters. */
    static String encode(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return sb.toString();
    }

    private static String safeMessage(Exception e) {
        String m = e.getMessage();
        return m == null ? "" : truncate(m);
    }

    private static String truncate(String s) {
        return s.length() > 2000 ? s.substring(0, 2000) + "..." : s;
    }
}
