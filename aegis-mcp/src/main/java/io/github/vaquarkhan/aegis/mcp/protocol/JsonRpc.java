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
package io.github.vaquarkhan.aegis.mcp.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

/** JSON-RPC 2.0 message builders and the error codes MCP uses. */
public final class JsonRpc {

    private JsonRpc() {}

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;
    /** 2026-07-28: headers do not match the body, or required headers are missing. */
    public static final int HEADER_MISMATCH = -32020;
    /** 2026-07-28: a required client capability was not declared. */
    public static final int MISSING_REQUIRED_CLIENT_CAPABILITY = -32021;
    /** 2026-07-28: requested protocol version is not supported. */
    public static final int UNSUPPORTED_PROTOCOL_VERSION = -32022;
    /** Implementation-defined range (-32000..-32019): the caller is not authenticated. */
    public static final int UNAUTHENTICATED = -32001;
    /**
     * Implementation-defined: Aegis governance denied a resources/prompts/completion request.
     * {@code data} carries the stable deny {@code code}, {@code step}, {@code reason} and {@code requestId}.
     * (tools/call denials are tool results with {@code isError}, not protocol errors.)
     */
    public static final int GOVERNANCE_DENIED = -32010;

    public static Map<String, Object> result(Object id, Object result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("result", result);
        return m;
    }

    public static Map<String, Object> error(Object id, int code, String message, Object data) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", (long) code);
        err.put("message", message);
        if (data != null) {
            err.put("data", data);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        if (id != null) {
            m.put("id", id);
        }
        m.put("error", err);
        return m;
    }

    public static Map<String, Object> request(Object id, String method, Map<String, Object> params) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("method", method);
        if (params != null) {
            m.put("params", params);
        }
        return m;
    }

    public static Map<String, Object> notification(String method, Map<String, Object> params) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("method", method);
        if (params != null) {
            m.put("params", params);
        }
        return m;
    }

    /** True when {@code id} is a valid JSON-RPC request id (string or integer). */
    public static boolean validId(Object id) {
        return id instanceof String || id instanceof Long || id instanceof Integer;
    }
}
