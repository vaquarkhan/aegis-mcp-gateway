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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An MCP {@code CallToolResult} (or, for 2026-07-28 backends, an {@code InputRequiredResult}) as a
 * JSON object. The engine treats it as data: it bounds and redacts text content but does not
 * reinterpret backend semantics.
 */
public record ToolResult(Map<String, Object> body) {

    public ToolResult {
        body = body == null ? Map.of() : body;
    }

    public static ToolResult text(String text, boolean isError) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("content", List.of(Map.of("type", "text", "text", text)));
        m.put("isError", isError);
        return new ToolResult(m);
    }

    public static ToolResult structured(String text, Object structured) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("content", List.of(Map.of("type", "text", "text", text)));
        if (structured != null) {
            m.put("structuredContent", structured);
        }
        m.put("isError", false);
        return new ToolResult(m);
    }

    public boolean isError() {
        return Boolean.TRUE.equals(body.get("isError"));
    }

    /** True for an MRTR interim result; it is passed through, and the client's retry is governed again. */
    public boolean inputRequired() {
        return "input_required".equals(body.get("resultType"));
    }
}
