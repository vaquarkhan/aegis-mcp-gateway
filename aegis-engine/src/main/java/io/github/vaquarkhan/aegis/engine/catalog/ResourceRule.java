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
package io.github.vaquarkhan.aegis.engine.catalog;

import io.github.vaquarkhan.aegis.engine.json.JsonPointer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Names exactly which argument carries a governed resource and what kind of resource it is.
 * Example: {@code {type: "iceberg:table", pointers: ["/namespace", "/table"], join: "."}} turns
 * {@code {"namespace":"sales","table":"orders"}} into {@code iceberg:table:sales.orders}.
 *
 * <p>Fixes 0.1 {@code Scope.resourceOf}, which took the first top-level argument and widened a
 * missing value to {@code *}. Here a missing required value is a denial, never a wildcard.
 *
 * @param type     resource type prefix, e.g. {@code kafka:topic}
 * @param pointers one or more JSON pointers into the backend arguments; multiple values are joined
 * @param join     separator between multiple pointer values
 * @param required when true, a missing value denies the call
 */
public record ResourceRule(String type, List<JsonPointer> pointers, String join, boolean required) {

    public ResourceRule {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("resource type must be set");
        }
        if (pointers == null || pointers.isEmpty()) {
            throw new IllegalArgumentException("resource rule needs at least one pointer");
        }
        pointers = List.copyOf(pointers);
        join = join == null ? "." : join;
    }

    /** Outcome of extraction: resources, or a reason the call must be denied. */
    public record Extraction(List<String> resources, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    /**
     * Extracts resources. A single pointer that resolves to an array yields one resource per
     * element (multi-valued arguments such as a list of topics are all governed, not just the first).
     */
    public Extraction extract(Map<String, Object> arguments) {
        if (pointers.size() == 1) {
            Optional<Object> v = pointers.get(0).resolve(arguments);
            if (v.isEmpty()) {
                return required ? new Extraction(List.of(), "missing resource argument " + pointers.get(0))
                        : new Extraction(List.of(), null);
            }
            Object value = v.get();
            List<String> out = new ArrayList<>();
            if (value instanceof List<?> list) {
                if (list.isEmpty() && required) {
                    return new Extraction(List.of(), "empty resource list at " + pointers.get(0));
                }
                for (Object item : list) {
                    String s = scalar(item);
                    if (s == null) {
                        return new Extraction(List.of(), "non-scalar resource value at " + pointers.get(0));
                    }
                    String err = hygiene(s);
                    if (err != null) {
                        return new Extraction(List.of(), err + " at " + pointers.get(0));
                    }
                    out.add(type + ":" + s);
                }
                return new Extraction(out, null);
            }
            String s = scalar(value);
            if (s == null) {
                return new Extraction(List.of(), "non-scalar resource value at " + pointers.get(0));
            }
            String err = hygiene(s);
            if (err != null) {
                return new Extraction(List.of(), err + " at " + pointers.get(0));
            }
            return new Extraction(List.of(type + ":" + s), null);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pointers.size(); i++) {
            Optional<Object> v = pointers.get(i).resolve(arguments);
            String s = v.map(ResourceRule::scalar).orElse(null);
            if (s == null) {
                return required ? new Extraction(List.of(), "missing resource argument " + pointers.get(i))
                        : new Extraction(List.of(), null);
            }
            String err = hygiene(s);
            if (err != null) {
                return new Extraction(List.of(), err + " at " + pointers.get(i));
            }
            if (!join.isEmpty() && s.contains(join)) {
                // Otherwise ("sales.secret", "x") and ("sales", "secret.x") would be the same resource.
                return new Extraction(List.of(), "resource part contains the separator '" + join + "' at " + pointers.get(i));
            }
            if (i > 0) {
                sb.append(join);
            }
            sb.append(s);
        }
        return new Extraction(List.of(type + ":" + sb), null);
    }

    private static String scalar(Object v) {
        if (v instanceof String s) {
            return s;
        }
        if (v instanceof Long || v instanceof Integer) {
            return v.toString();
        }
        return null;
    }

    /** Shared input hygiene for resource identifiers (0.1 {@code Inputs} rules). */
    static String hygiene(String s) {
        if (s.isEmpty()) {
            return "empty resource value";
        }
        if (s.length() > 512) {
            return "resource value too long";
        }
        if (s.contains("..")) {
            return "resource value contains '..'";
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return "resource value contains a control character";
            }
        }
        if (s.contains("*") || s.contains("?")) {
            return "resource value contains a wildcard character";
        }
        return null;
    }
}
