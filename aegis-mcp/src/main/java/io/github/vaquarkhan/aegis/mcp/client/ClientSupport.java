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

import io.github.vaquarkhan.aegis.engine.json.JsonValues;
import io.github.vaquarkhan.aegis.mcp.protocol.JsonRpc;
import io.github.vaquarkhan.aegis.mcp.protocol.McpVersions;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared helpers for downstream MCP clients. */
final class ClientSupport {

    private ClientSupport() {}

    /** JSON-RPC error codes that identify a modern (2026-07-28) server when returned to a modern probe. */
    static final Set<Long> MODERN_ERROR_CODES = Set.of((long) JsonRpc.UNSUPPORTED_PROTOCOL_VERSION,
            (long) JsonRpc.HEADER_MISMATCH, (long) JsonRpc.MISSING_REQUIRED_CLIENT_CAPABILITY);

    static Map<String, Object> clientInfo() {
        return Map.of("name", "aegis-gateway", "version", "2.0.0-SNAPSHOT");
    }

    /** Adds modern per-request metadata to params (without overwriting unrelated _meta keys). */
    static Map<String, Object> withModernMeta(Map<String, Object> params) {
        Map<String, Object> p = new LinkedHashMap<>(params == null ? Map.of() : params);
        Map<String, Object> meta = new LinkedHashMap<>();
        Map<String, Object> existing = JsonValues.object(p, "_meta");
        if (existing != null) {
            meta.putAll(existing);
        }
        meta.put(McpVersions.META_PROTOCOL_VERSION, McpVersions.V2026_07_28);
        meta.putIfAbsent(McpVersions.META_CLIENT_CAPABILITIES, Map.of()); // a forwarded client declaration wins
        meta.put(McpVersions.META_CLIENT_INFO, clientInfo());
        p.put("_meta", meta);
        return p;
    }

    static byte[] readBounded(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) >= 0) {
            total += n;
            if (total > max) {
                throw new IOException("downstream response exceeds " + max + " bytes");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** Extracts the result of a JSON-RPC response or raises its error. */
    static Map<String, Object> resultOf(Map<String, Object> response) throws McpClient.McpCallException {
        Map<String, Object> err = JsonValues.object(response, "error");
        if (err != null) {
            Long code = JsonValues.integral(err.get("code"));
            String msg = JsonValues.string(err, "message");
            throw new McpClient.McpCallException("downstream MCP error " + code + ": " + (msg == null ? "" : truncate(msg)),
                    code == null ? null : code.intValue(), false, null);
        }
        Map<String, Object> result = JsonValues.object(response, "result");
        if (result == null) {
            throw new McpClient.McpCallException("downstream MCP response has no result object");
        }
        return result;
    }

    static String truncate(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }

    /** True when an MCP Tool object has the fields the gateway needs. */
    static boolean validTool(Object o) {
        Map<String, Object> m = JsonValues.asObject(o);
        return m != null && m.get("name") instanceof String n && !n.isBlank()
                && (m.get("inputSchema") == null || m.get("inputSchema") instanceof Map);
    }

    static List<Object> list(Map<String, Object> m, String key) {
        List<Object> l = JsonValues.asList(m.get(key));
        return l == null ? List.of() : l;
    }
}
