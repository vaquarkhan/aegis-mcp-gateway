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
package io.github.vaquarkhan.aegis.engine.model;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * W3C Trace Context ({@code traceparent}, version 00) for correlating one agent request across the
 * client, the gateway, and every backend it reaches.
 *
 * <p>Where it comes from: the MCP request's {@code params._meta.traceparent} (the key OpenTelemetry
 * uses for MCP), else the HTTP {@code traceparent} header, else a new root trace. An invalid value is
 * ignored and replaced by a new root (the W3C rule), never trusted partially. The gateway is a hop:
 * every governed call gets its own span id whose parent is the caller's span, so the audit record,
 * the backend request and the caller's trace line up.
 *
 * <p>Scope: this is context propagation and correlation, not an OpenTelemetry SDK. The ids are
 * written to audit and forwarded, so any tracing backend that ingests either can join them; when
 * {@code tracing.otlp.endpoint} is configured, {@code aegis-observability-otlp} also exports one span
 * per decision.
 *
 * @param traceId      32 lower-case hex characters, not all zero
 * @param spanId       this hop's span id, 16 lower-case hex characters, not all zero
 * @param parentSpanId the caller's span id, or null for a new root
 * @param sampled      the caller's sampled flag (propagated unchanged)
 */
public record TraceContext(String traceId, String spanId, String parentSpanId, boolean sampled) {

    public static final String META_KEY = "traceparent";
    public static final String HEADER = "traceparent";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ZERO_TRACE = "0".repeat(32);
    private static final String ZERO_SPAN = "0".repeat(16);

    /** The {@code traceparent} value to send downstream (this hop is the parent of the next). */
    public String header() {
        return "00-" + traceId + "-" + spanId + "-" + (sampled ? "01" : "00");
    }

    /** New root trace (no valid inbound context). */
    public static TraceContext newRoot() {
        return new TraceContext(hex(16, ZERO_TRACE), hex(8, ZERO_SPAN), null, false);
    }

    /** Child hop of an inbound {@code traceparent}, or a new root when absent or invalid. */
    public static TraceContext continueFrom(String inbound) {
        Optional<String[]> p = parse(inbound);
        if (p.isEmpty()) {
            return newRoot();
        }
        String[] f = p.get();
        boolean sampled = (Integer.parseInt(f[3], 16) & 0x01) == 1;
        return new TraceContext(f[1], hex(8, ZERO_SPAN), f[2], sampled);
    }

    /** Resolves the inbound context from {@code _meta} first, then the transport header. */
    public static TraceContext from(Map<String, Object> meta, String header) {
        Object m = meta == null ? null : meta.get(META_KEY);
        if (m instanceof String s && parse(s).isPresent()) {
            return continueFrom(s);
        }
        return continueFrom(header);
    }

    /**
     * Strict W3C parse: {@code version-traceid-parentid-flags}; version {@code ff} is invalid, a
     * version 00 value must have exactly 4 fields, ids must be lower-case hex and not all zero.
     */
    static Optional<String[]> parse(String v) {
        if (v == null || v.length() < 55 || v.length() > 512) {
            return Optional.empty();
        }
        String[] f = v.trim().split("-", -1);
        if (f.length < 4 || f[0].length() != 2 || !lowerHex(f[0]) || "ff".equals(f[0])) {
            return Optional.empty();
        }
        if ("00".equals(f[0]) && f.length != 4) {
            return Optional.empty();
        }
        if (f[1].length() != 32 || !lowerHex(f[1]) || ZERO_TRACE.equals(f[1])
                || f[2].length() != 16 || !lowerHex(f[2]) || ZERO_SPAN.equals(f[2])
                || f[3].length() != 2 || !lowerHex(f[3])) {
            return Optional.empty();
        }
        return Optional.of(f);
    }

    private static boolean lowerHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private static String hex(int bytes, String zero) {
        String h;
        do {
            byte[] b = new byte[bytes];
            RANDOM.nextBytes(b);
            h = HexFormat.of().formatHex(b);
        } while (zero.equals(h));
        return h;
    }
}
