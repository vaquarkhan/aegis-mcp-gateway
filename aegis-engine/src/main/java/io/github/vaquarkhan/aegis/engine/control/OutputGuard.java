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
package io.github.vaquarkhan.aegis.engine.control;

import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.model.ControlTokens;
import io.github.vaquarkhan.aegis.engine.model.ToolResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Bounds and redacts backend output before it reaches the model. Redaction walks every string at
 * any depth of {@code content} and {@code structuredContent} (see {@link ValueWalker}).
 *
 * <p>Default patterns catch common secret shapes (cloud access keys, private-key blocks, bearer
 * tokens, JWTs). They are heuristics that reduce accidental leakage; they are not a DLP product.
 */
public final class OutputGuard {

    public static final List<String> DEFAULT_REDACTIONS = List.of(
            "AKIA[0-9A-Z]{16}",
            // Bounded body: an unbounded lazy span over many unterminated BEGIN markers is quadratic.
            "-----BEGIN [A-Z ]{0,40}PRIVATE KEY-----[\\s\\S]{0,16384}?-----END [A-Z ]{0,40}PRIVATE KEY-----",
            "(?i)bearer\\s+[A-Za-z0-9._~+/=-]{20,}",
            "eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}");

    public static final String REDACTED = "[REDACTED]";
    private static final int MAX_REGEX_INPUT = 1 << 20;

    private final int maxBytes;
    private final List<Pattern> redactions;
    private final boolean allowBinary;

    /** Guard that refuses binary tool output (the default). */
    public OutputGuard(int maxBytes, List<String> redactionPatterns) {
        this(maxBytes, redactionPatterns, false);
    }

    /**
     * @param allowBinary pass binary parts of tool results ({@code data} of image and audio content,
     *                    and any {@code blob}); they are opaque to redaction and screening, so the
     *                    default refuses them
     */
    public OutputGuard(int maxBytes, List<String> redactionPatterns, boolean allowBinary) {
        this.maxBytes = maxBytes <= 0 ? 1 << 20 : maxBytes;
        List<Pattern> ps = new ArrayList<>();
        for (String p : redactionPatterns == null ? DEFAULT_REDACTIONS : redactionPatterns) {
            ps.add(Pattern.compile(p));
        }
        this.redactions = List.copyOf(ps);
        this.allowBinary = allowBinary;
    }

    public static OutputGuard defaults() {
        return new OutputGuard(1 << 20, DEFAULT_REDACTIONS);
    }

    public boolean allowBinary() {
        return allowBinary;
    }

    /**
     * Result of guarding: the safe result plus what was changed (for audit).
     *
     * @param binaryRefused the backend returned binary content and this guard does not allow it; the
     *                      caller must withhold the result
     */
    public record Guarded(ToolResult result, int redactions, boolean truncated, boolean binaryRefused) {}

    public Guarded apply(ToolResult in) {
        int[] count = new int[1];
        boolean[] binary = new boolean[1];
        // Every field of the result is redacted, not only content/structuredContent: MRTR
        // inputRequests, upstream _meta and any extension field reach the client too. Binary payloads
        // are left byte-for-byte intact (a regular expression would corrupt them) and reported.
        Map<String, Object> body = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : in.body().entrySet()) {
            body.put(e.getKey(), guardValue(e.getValue(), count, binary, 0));
        }
        if (binary[0] && !allowBinary) {
            return new Guarded(ToolResult.text("Aegis withheld this result: it contains binary content", true),
                    count[0], false, true);
        }
        byte[] size = Json.write(body).getBytes(StandardCharsets.UTF_8);
        boolean truncated = false;
        if (size.length > maxBytes) {
            truncated = true;
            String text = firstText(body);
            byte[] tb = text.getBytes(StandardCharsets.UTF_8);
            int keep = Math.max(0, Math.min(tb.length, maxBytes / 2));
            String cut = new String(tb, 0, keep, StandardCharsets.UTF_8);
            Map<String, Object> small = new LinkedHashMap<>();
            small.put("content", List.of(Map.of("type", "text",
                    "text", cut + "\n[output truncated by Aegis: " + size.length + " bytes exceeds " + maxBytes + "]")));
            small.put("isError", Boolean.TRUE.equals(body.get("isError")));
            if (body.containsKey("resultType")) {
                small.put("resultType", body.get("resultType"));
            }
            body = small;
        }
        if (count[0] > 0 || truncated) {
            Map<String, Object> meta = new LinkedHashMap<>();
            Object existing = body.get("_meta");
            if (existing instanceof Map<?, ?> em) {
                for (Map.Entry<?, ?> e : em.entrySet()) {
                    meta.put(String.valueOf(e.getKey()), e.getValue());
                }
            }
            meta.put(ControlTokens.META_PREFIX + "redactions", (long) count[0]);
            meta.put(ControlTokens.META_PREFIX + "truncated", truncated);
            body.put("_meta", meta);
        }
        return new Guarded(new ToolResult(body), count[0], truncated, false);
    }

    /**
     * Redacts every string value at any depth, except binary payloads: {@code blob} anywhere, and
     * {@code data} of an {@code image} or {@code audio} content part. Those are reported in
     * {@code binary} and copied unchanged.
     */
    private Object guardValue(Object v, int[] count, boolean[] binary, int depth) {
        if (depth > ValueWalker.MAX_DEPTH) {
            throw new IllegalArgumentException("value nesting exceeds " + ValueWalker.MAX_DEPTH);
        }
        if (v instanceof String s) {
            return redact(s, count);
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object o : l) {
                out.add(guardValue(o, count, binary, depth + 1));
            }
            return out;
        }
        if (v instanceof Map<?, ?> m) {
            Object type = m.get("type");
            boolean media = "image".equals(type) || "audio".equals(type);
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                if ("blob".equals(k) || (media && "data".equals(k))) {
                    binary[0] = true;
                    out.put(k, e.getValue());
                } else {
                    out.put(k, guardValue(e.getValue(), count, binary, depth + 1));
                }
            }
            return out;
        }
        return v;
    }

    /** Redacts every string of any JSON value (used for resources and prompts). */
    public record Redacted(Object value, int redactions) {}

    public Redacted redactAll(Object value) {
        int[] count = new int[1];
        Object v = ValueWalker.mapStrings(value, s -> redact(s, count));
        return new Redacted(v, count[0]);
    }

    public int maxBytes() {
        return maxBytes;
    }

    private String redact(String s, int[] count) {
        String out = s.length() > MAX_REGEX_INPUT ? s.substring(0, MAX_REGEX_INPUT) : s;
        for (Pattern p : redactions) {
            java.util.regex.Matcher m = p.matcher(out);
            StringBuilder sb = new StringBuilder();
            boolean any = false;
            while (m.find()) {
                any = true;
                count[0]++;
                m.appendReplacement(sb, REDACTED);
            }
            if (any) {
                m.appendTail(sb);
                out = sb.toString();
            }
        }
        return out;
    }

    private static String firstText(Map<String, Object> body) {
        Object c = body.get("content");
        if (c instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m && m.get("text") instanceof String t) {
                    return t;
                }
            }
        }
        return "";
    }
}
