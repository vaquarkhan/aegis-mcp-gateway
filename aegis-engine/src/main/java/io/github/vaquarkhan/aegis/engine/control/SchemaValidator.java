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

import io.github.vaquarkhan.aegis.engine.json.JsonValues;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates tool arguments against the subset of JSON Schema that MCP tool definitions use in
 * practice: {@code type}, {@code properties}, {@code required}, {@code additionalProperties},
 * {@code enum}, {@code const}, {@code minLength}/{@code maxLength}, {@code minimum}/{@code maximum}
 * (and exclusive forms), {@code items}, {@code minItems}/{@code maxItems}.
 *
 * <p>{@code $ref}: a local reference ({@code #}, {@code #/$defs/...}, {@code #/definitions/...}) is
 * resolved inside the same schema and evaluated next to its sibling keywords, as JSON Schema
 * 2020-12 does. A reference that points anywhere else (an {@code http(s)} URL, a relative file, a
 * plain-name anchor) is never fetched: the call is denied, because the gateway cannot check what it
 * does not have, and fetching a URL named by an upstream schema would be a server-side request the
 * operator never allowed. Reference cycles are bounded by the same depth limit as nesting.
 *
 * <p>Scope: MCP 2026-07-28 allows any JSON Schema 2020-12 keyword. Keywords outside the
 * subset ({@code anyOf}, {@code oneOf}, {@code allOf}, {@code pattern}, formats...) are
 * NOT evaluated here; the backend remains responsible for them. {@code pattern} is skipped on
 * purpose: evaluating upstream-supplied regular expressions in the gateway is a ReDoS risk.
 * This validator is a gate against malformed and unexpected input, not a complete schema engine.
 */
public final class SchemaValidator {

    private static final int MAX_DEPTH = 64;

    private SchemaValidator() {}

    /** @return null when valid, else a reason that names the JSON path but never echoes values */
    public static String validate(Map<String, Object> schema, Object value) {
        return check(schema, schema, value, "", 0);
    }

    private static String check(Map<String, Object> root, Map<String, Object> schema, Object v,
                                String path, int depth) {
        if (schema == null) {
            return null;
        }
        if (depth > MAX_DEPTH) {
            return "schema nesting too deep at " + where(path);
        }
        Object ref = schema.get("$ref");
        if (ref != null) {
            if (!(ref instanceof String refText) || !refText.startsWith("#")) {
                return "schema uses a remote $ref, which Aegis does not fetch, at " + where(path);
            }
            Map<String, Object> target = resolveLocal(root, refText);
            if (target == null) {
                return "schema has an unresolvable $ref at " + where(path);
            }
            String r = check(root, target, v, path, depth + 1);
            if (r != null) {
                return r;
            }
        }
        Object type = schema.get("type");
        if (type != null && !typeMatches(type, v)) {
            return "wrong type at " + where(path);
        }
        if (schema.containsKey("const") && !jsonEquals(schema.get("const"), v)) {
            return "value not allowed at " + where(path);
        }
        List<Object> en = JsonValues.asList(schema.get("enum"));
        if (en != null) {
            boolean found = false;
            for (Object o : en) {
                if (jsonEquals(o, v)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return "value not in enum at " + where(path);
            }
        }
        if (v instanceof String s) {
            Long min = JsonValues.integral(schema.get("minLength"));
            Long max = JsonValues.integral(schema.get("maxLength"));
            int len = s.codePointCount(0, s.length());
            if (min != null && len < min) {
                return "string too short at " + where(path);
            }
            if (max != null && len > max) {
                return "string too long at " + where(path);
            }
        }
        if (isNumber(v)) {
            BigDecimal n = toDecimal(v);
            String r = bound(schema, "minimum", n, path, false, true);
            if (r == null) {
                r = bound(schema, "maximum", n, path, false, false);
            }
            if (r == null) {
                r = bound(schema, "exclusiveMinimum", n, path, true, true);
            }
            if (r == null) {
                r = bound(schema, "exclusiveMaximum", n, path, true, false);
            }
            if (r != null) {
                return r;
            }
        }
        if (v instanceof Map<?, ?> rawMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> obj = (Map<String, Object>) rawMap;
            List<Object> required = JsonValues.asList(schema.get("required"));
            if (required != null) {
                for (Object r : required) {
                    if (r instanceof String name && !obj.containsKey(name)) {
                        return "missing required property " + where(path + "/" + name);
                    }
                }
            }
            Map<String, Object> props = JsonValues.object(schema, "properties");
            Object additional = schema.get("additionalProperties");
            for (Map.Entry<String, Object> e : obj.entrySet()) {
                String childPath = path + "/" + e.getKey();
                Map<String, Object> ps = props == null ? null : JsonValues.asObject(props.get(e.getKey()));
                if (ps != null) {
                    String r = check(root, ps, e.getValue(), childPath, depth + 1);
                    if (r != null) {
                        return r;
                    }
                } else if (props == null || !props.containsKey(e.getKey())) {
                    if (Boolean.FALSE.equals(additional)) {
                        return "unexpected property " + where(childPath);
                    }
                    Map<String, Object> as = JsonValues.asObject(additional);
                    if (as != null) {
                        String r = check(root, as, e.getValue(), childPath, depth + 1);
                        if (r != null) {
                            return r;
                        }
                    }
                }
            }
        }
        if (v instanceof List<?> list) {
            Long minItems = JsonValues.integral(schema.get("minItems"));
            Long maxItems = JsonValues.integral(schema.get("maxItems"));
            if (minItems != null && list.size() < minItems) {
                return "too few items at " + where(path);
            }
            if (maxItems != null && list.size() > maxItems) {
                return "too many items at " + where(path);
            }
            Map<String, Object> items = JsonValues.object(schema, "items");
            if (items != null) {
                for (int i = 0; i < list.size(); i++) {
                    String r = check(root, items, list.get(i), path + "/" + i, depth + 1);
                    if (r != null) {
                        return r;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Resolves {@code #} or {@code #/a/b} (JSON Pointer in a URI fragment) against the root schema.
     * Plain-name anchors such as {@code #foo} are not resolved and so fail closed.
     */
    private static Map<String, Object> resolveLocal(Map<String, Object> root, String ref) {
        String fragment = ref.substring(1);
        if (fragment.isEmpty()) {
            return root;
        }
        if (!fragment.startsWith("/")) {
            return null;
        }
        String decoded;
        try {
            decoded = java.net.URLDecoder.decode(fragment.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        Object node = root;
        for (String token : decoded.substring(1).split("/", -1)) {
            String key = token.replace("~1", "/").replace("~0", "~");
            if (node instanceof Map<?, ?> m) {
                node = m.get(key);
            } else if (node instanceof List<?> l && key.matches("0|[1-9][0-9]{0,8}")) {
                int i = Integer.parseInt(key);
                node = i < l.size() ? l.get(i) : null;
            } else {
                return null;
            }
            if (node == null) {
                return null;
            }
        }
        return JsonValues.asObject(node);
    }

    private static String bound(Map<String, Object> schema, String key, BigDecimal n, String path,
                                boolean exclusive, boolean lower) {
        Object b = schema.get(key);
        if (!isNumber(b)) {
            return null;
        }
        int c = n.compareTo(toDecimal(b));
        boolean bad = lower ? (exclusive ? c <= 0 : c < 0) : (exclusive ? c >= 0 : c > 0);
        return bad ? "number out of range at " + where(path) : null;
    }

    private static boolean typeMatches(Object type, Object v) {
        if (type instanceof String t) {
            return single(t, v);
        }
        List<Object> types = JsonValues.asList(type);
        if (types != null) {
            for (Object t : types) {
                if (t instanceof String ts && single(ts, v)) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private static final Set<String> KNOWN = Set.of("object", "array", "string", "integer", "number", "boolean", "null");

    private static boolean single(String t, Object v) {
        if (!KNOWN.contains(t)) {
            return true; // unknown type keyword: not ours to judge
        }
        return switch (t) {
            case "object" -> v instanceof Map;
            case "array" -> v instanceof List;
            case "string" -> v instanceof String;
            case "boolean" -> v instanceof Boolean;
            case "null" -> v == null;
            case "integer" -> v instanceof Long || v instanceof Integer
                    || (v instanceof BigDecimal bd && bd.stripTrailingZeros().scale() <= 0);
            case "number" -> isNumber(v);
            default -> true;
        };
    }

    private static boolean isNumber(Object v) {
        return v instanceof Long || v instanceof Integer || v instanceof BigDecimal;
    }

    private static BigDecimal toDecimal(Object v) {
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        return BigDecimal.valueOf(((Number) v).longValue());
    }

    private static boolean jsonEquals(Object a, Object b) {
        if (isNumber(a) && isNumber(b)) {
            return toDecimal(a).compareTo(toDecimal(b)) == 0;
        }
        return java.util.Objects.equals(a, b);
    }

    private static String where(String path) {
        return path.isEmpty() ? "/" : path;
    }
}
