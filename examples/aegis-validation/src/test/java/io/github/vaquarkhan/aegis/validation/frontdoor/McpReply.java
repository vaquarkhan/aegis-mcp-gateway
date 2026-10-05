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
package io.github.vaquarkhan.aegis.validation.frontdoor;

import io.github.vaquarkhan.aegis.engine.json.Json;
import java.util.List;
import java.util.Map;

/** One HTTP response from a front door, with the Aegis decision read out of the MCP result. */
public record McpReply(int status, String body) {

    @SuppressWarnings("unchecked")
    private Map<String, Object> result() {
        if (status != 200 || body == null || body.isBlank() || body.charAt(0) != '{') {
            return null;
        }
        Object r = Json.parseObject(body).get("result");
        return r instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    /** The {@code io.github.vaquarkhan.aegis/decision} object, or an empty map when there is none. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> decision() {
        Map<String, Object> r = result();
        if (r == null || !(r.get("_meta") instanceof Map<?, ?> meta)) {
            return Map.of();
        }
        Object d = meta.get("io.github.vaquarkhan.aegis/decision");
        return d instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /** True only when the HTTP exchange succeeded and Aegis allowed and executed the call. */
    public boolean allowed() {
        return "ALLOWED".equals(decision().get("outcome"));
    }

    /** The stable deny code, or null when the call was allowed or never reached Aegis. */
    public String code() {
        Object c = decision().get("code");
        return c == null ? null : c.toString();
    }

    public String get(String decisionKey) {
        Object v = decision().get(decisionKey);
        return v == null ? null : v.toString();
    }

    /** What the model would see: the text content, followed by any {@code structuredContent} as JSON. */
    public String text() {
        Map<String, Object> r = result();
        if (r == null) {
            return body == null ? "" : body;
        }
        StringBuilder sb = new StringBuilder();
        if (r.get("content") instanceof List<?> content) {
            for (Object c : content) {
                if (c instanceof Map<?, ?> m && m.get("text") != null) {
                    sb.append(m.get("text")).append('\n');
                }
            }
        }
        if (r.get("structuredContent") != null) {
            sb.append(Json.write(r.get("structuredContent")));
        }
        return sb.toString();
    }
}
