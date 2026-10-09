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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * RFC 6901 JSON Pointer. Used by configuration to name exactly which argument holds a governed
 * resource (for example {@code /namespace} and {@code /table}), instead of guessing from the first
 * top-level key as Aegis 0.1 did.
 */
public final class JsonPointer {

    private final String text;
    private final List<String> tokens;

    private JsonPointer(String text, List<String> tokens) {
        this.text = text;
        this.tokens = tokens;
    }

    public static JsonPointer parse(String pointer) {
        if (pointer == null) {
            throw new IllegalArgumentException("JSON pointer must not be null");
        }
        if (pointer.isEmpty()) {
            return new JsonPointer("", List.of());
        }
        if (pointer.charAt(0) != '/') {
            throw new IllegalArgumentException("JSON pointer must start with '/': " + pointer);
        }
        List<String> out = new ArrayList<>();
        for (String raw : pointer.substring(1).split("/", -1)) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (c == '~') {
                    if (i + 1 >= raw.length()) {
                        throw new IllegalArgumentException("invalid '~' escape in pointer: " + pointer);
                    }
                    char n = raw.charAt(++i);
                    if (n == '0') {
                        sb.append('~');
                    } else if (n == '1') {
                        sb.append('/');
                    } else {
                        throw new IllegalArgumentException("invalid '~' escape in pointer: " + pointer);
                    }
                } else {
                    sb.append(c);
                }
            }
            out.add(sb.toString());
        }
        return new JsonPointer(pointer, List.copyOf(out));
    }

    /** Resolves the pointer; empty when any step is missing or of the wrong container type. */
    public Optional<Object> resolve(Object root) {
        Object cur = root;
        for (String t : tokens) {
            if (cur instanceof Map<?, ?> m) {
                if (!m.containsKey(t)) {
                    return Optional.empty();
                }
                cur = m.get(t);
            } else if (cur instanceof List<?> l) {
                if (t.isEmpty() || !t.chars().allMatch(Character::isDigit) || (t.length() > 1 && t.charAt(0) == '0')) {
                    return Optional.empty();
                }
                int idx;
                try {
                    idx = Integer.parseInt(t);
                } catch (NumberFormatException e) {
                    return Optional.empty();
                }
                if (idx >= l.size()) {
                    return Optional.empty();
                }
                cur = l.get(idx);
            } else {
                return Optional.empty();
            }
        }
        return Optional.ofNullable(cur);
    }

    public List<String> tokens() {
        return tokens;
    }

    @Override
    public String toString() {
        return text;
    }
}
