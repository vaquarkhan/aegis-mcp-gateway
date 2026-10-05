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
package io.github.vaquarkhan.aegis.engine.json;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Typed accessors over the {@link Json} value model. Nothing here coerces types silently. */
public final class JsonValues {

    private JsonValues() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object v) {
        return v instanceof List ? (List<Object>) v : null;
    }

    public static Map<String, Object> object(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return asObject(v);
    }

    public static Map<String, Object> objectOrEmpty(Map<String, Object> m, String key) {
        Map<String, Object> o = object(m, key);
        return o == null ? Collections.emptyMap() : o;
    }

    public static String string(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v instanceof String s ? s : null;
    }

    public static boolean isString(Object v) {
        return v instanceof String;
    }

    /** Returns true only for a JSON boolean {@code true}; strings like "true" do not count. */
    public static boolean isTrue(Object v) {
        return Boolean.TRUE.equals(v);
    }

    /** Integral JSON number as long, or null when absent or not integral. */
    public static Long integral(Object v) {
        if (v instanceof Long l) {
            return l;
        }
        if (v instanceof Integer i) {
            return i.longValue();
        }
        return null;
    }
}
