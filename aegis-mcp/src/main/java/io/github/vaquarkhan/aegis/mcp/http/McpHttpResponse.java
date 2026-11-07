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
package io.github.vaquarkhan.aegis.mcp.http;

import io.github.vaquarkhan.aegis.engine.json.Json;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Host-neutral HTTP response. */
public record McpHttpResponse(int status, Map<String, String> headers, byte[] body) {

    public McpHttpResponse {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        body = body == null ? new byte[0] : body;
    }

    public static McpHttpResponse json(int status, Object value) {
        return json(status, value, Map.of());
    }

    public static McpHttpResponse json(int status, Object value, Map<String, String> extraHeaders) {
        Map<String, String> h = new LinkedHashMap<>(extraHeaders);
        h.put("Content-Type", "application/json");
        h.put("Cache-Control", "no-store");
        return new McpHttpResponse(status, h, Json.write(value).getBytes(StandardCharsets.UTF_8));
    }

    public static McpHttpResponse empty(int status) {
        return new McpHttpResponse(status, Map.of(), new byte[0]);
    }

    public static McpHttpResponse empty(int status, Map<String, String> headers) {
        return new McpHttpResponse(status, headers, new byte[0]);
    }

    public String bodyText() {
        return new String(body, StandardCharsets.UTF_8);
    }
}
