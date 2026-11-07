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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Recursive traversal of JSON values. Every content control (injection screening, URL egress
 * checks, output redaction) uses this walker so nothing inspects only top-level keys.
 *
 * <p>Motivation: a top-level-only filter is bypassed when an untrusted payload is nested inside a
 * structured argument. Map keys are visited as well as values, because a key is model-controlled text
 * too.
 */
public final class ValueWalker {

    /** Hard stop independent of parser limits, for values built programmatically. */
    public static final int MAX_DEPTH = 128;

    private ValueWalker() {}

    /** True if any string (key or value) at any depth matches. */
    public static boolean anyString(Object value, Predicate<String> test) {
        return anyString(value, test, 0);
    }

    private static boolean anyString(Object value, Predicate<String> test, int depth) {
        if (depth > MAX_DEPTH) {
            // Unbounded structures are treated as matching, so callers fail closed.
            return true;
        }
        if (value instanceof String s) {
            return test.test(s);
        }
        if (value instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() instanceof String k && test.test(k)) {
                    return true;
                }
                if (anyString(e.getValue(), test, depth + 1)) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof List<?> l) {
            for (Object o : l) {
                if (anyString(o, test, depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Collects every string value (not keys) at any depth. */
    public static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        anyString(value, s -> {
            out.add(s);
            return false;
        });
        return out;
    }

    /** Returns a copy with every string VALUE (not key) transformed; structure is preserved. */
    public static Object mapStrings(Object value, Function<String, String> fn) {
        return mapStrings(value, fn, 0);
    }

    private static Object mapStrings(Object value, Function<String, String> fn, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("value nesting exceeds " + MAX_DEPTH);
        }
        if (value instanceof String s) {
            return fn.apply(s);
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), mapStrings(e.getValue(), fn, depth + 1));
            }
            return out;
        }
        if (value instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object o : l) {
                out.add(mapStrings(o, fn, depth + 1));
            }
            return out;
        }
        return value;
    }
}
