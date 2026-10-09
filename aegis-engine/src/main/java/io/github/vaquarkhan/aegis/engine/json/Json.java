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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Strict, bounded RFC 8259 JSON reader and writer with no third-party dependencies.
 *
 * <p>Why a private parser: the engine must load on any host classpath (Apache Knox ships its own
 * Jackson, engines ship theirs). A zero-dependency parser removes version collisions and lets the
 * gateway enforce security properties a general-purpose parser does not enforce by default:
 *
 * <ul>
 *   <li>duplicate object keys are rejected (a proxy and a backend must never disagree on which
 *       {@code "name"} wins);</li>
 *   <li>invalid UTF-8 and lone surrogate escapes are rejected;</li>
 *   <li>depth, size, string, number and element counts are bounded during the parse;</li>
 *   <li>trailing content after the root value is rejected.</li>
 * </ul>
 *
 * <p>Value model: {@code Map<String,Object>} (insertion ordered, unmodifiable), {@code List<Object>}
 * (unmodifiable), {@link String}, {@link Boolean}, {@code null}, and numbers as {@link Long} when
 * integral and in range, otherwise {@link BigDecimal}. Floating point is never used, so number
 * round-trips are exact.
 */
public final class Json {

    private Json() {}

    // ------------------------------------------------------------------ parsing

    public static Object parse(byte[] utf8, JsonLimits limits) {
        if (utf8.length > limits.maxBytes()) {
            throw new JsonException("JSON document exceeds " + limits.maxBytes() + " bytes", true);
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(utf8))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new JsonException("JSON document is not valid UTF-8");
        }
        return new Parser(text, limits).parseDocument();
    }

    public static Object parse(String text, JsonLimits limits) {
        // Size is checked on the encoded form so String and byte[] inputs share one bound.
        if ((long) text.length() * 3 > limits.maxBytes()
                && text.getBytes(StandardCharsets.UTF_8).length > limits.maxBytes()) {
            throw new JsonException("JSON document exceeds " + limits.maxBytes() + " bytes", true);
        }
        return new Parser(text, limits).parseDocument();
    }

    public static Object parse(String text) {
        return parse(text, JsonLimits.DEFAULT);
    }

    /** Parses and requires the root to be an object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(byte[] utf8, JsonLimits limits) {
        Object v = parse(utf8, limits);
        if (!(v instanceof Map)) {
            throw new JsonException("JSON root must be an object");
        }
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text, JsonLimits.DEFAULT);
        if (!(v instanceof Map)) {
            throw new JsonException("JSON root must be an object");
        }
        return (Map<String, Object>) v;
    }

    private static final class Parser {
        private final String s;
        private final JsonLimits limits;
        private int pos;
        private int values;

        Parser(String s, JsonLimits limits) {
            this.s = s;
            this.limits = limits;
        }

        Object parseDocument() {
            skipWs();
            Object v = parseValue(0);
            skipWs();
            if (pos != s.length()) {
                throw error("unexpected trailing content");
            }
            return v;
        }

        private Object parseValue(int depth) {
            if (++values > limits.maxTotalValues()) {
                throw new JsonException("JSON document has too many values", true);
            }
            if (pos >= s.length()) {
                throw error("unexpected end of input");
            }
            char c = s.charAt(pos);
            switch (c) {
                case '{':
                    return parseObject(depth + 1);
                case '[':
                    return parseArray(depth + 1);
                case '"':
                    return parseString();
                case 't':
                    expectLiteral("true");
                    return Boolean.TRUE;
                case 'f':
                    expectLiteral("false");
                    return Boolean.FALSE;
                case 'n':
                    expectLiteral("null");
                    return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return parseNumber();
                    }
                    throw error("unexpected character");
            }
        }

        private Map<String, Object> parseObject(int depth) {
            if (depth > limits.maxDepth()) {
                throw new JsonException("JSON nesting exceeds depth " + limits.maxDepth(), true);
            }
            pos++; // '{'
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') {
                pos++;
                return Collections.unmodifiableMap(map);
            }
            while (true) {
                skipWs();
                if (peek() != '"') {
                    throw error("expected object key");
                }
                String key = parseString();
                skipWs();
                expect(':');
                skipWs();
                Object value = parseValue(depth);
                if (map.containsKey(key)) {
                    throw new JsonException("duplicate object key");
                }
                map.put(key, value);
                if (map.size() > limits.maxObjectEntries()) {
                    throw new JsonException("JSON object has too many members", true);
                }
                skipWs();
                char c = next();
                if (c == '}') {
                    return Collections.unmodifiableMap(map);
                }
                if (c != ',') {
                    throw error("expected ',' or '}'");
                }
            }
        }

        private List<Object> parseArray(int depth) {
            if (depth > limits.maxDepth()) {
                throw new JsonException("JSON nesting exceeds depth " + limits.maxDepth(), true);
            }
            pos++; // '['
            ArrayList<Object> list = new ArrayList<>();
            skipWs();
            if (peek() == ']') {
                pos++;
                return Collections.unmodifiableList(list);
            }
            while (true) {
                skipWs();
                list.add(parseValue(depth));
                if (list.size() > limits.maxArrayElements()) {
                    throw new JsonException("JSON array has too many elements", true);
                }
                skipWs();
                char c = next();
                if (c == ']') {
                    return Collections.unmodifiableList(list);
                }
                if (c != ',') {
                    throw error("expected ',' or ']'");
                }
            }
        }

        private String parseString() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw error("unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    break;
                }
                if (c < 0x20) {
                    throw error("unescaped control character in string");
                }
                if (c == '\\') {
                    if (pos >= s.length()) {
                        throw error("unterminated escape");
                    }
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u': {
                            char u = readHex4();
                            if (Character.isHighSurrogate(u)) {
                                if (pos + 6 > s.length() || s.charAt(pos) != '\\' || s.charAt(pos + 1) != 'u') {
                                    throw error("lone surrogate escape");
                                }
                                pos += 2;
                                char low = readHex4();
                                if (!Character.isLowSurrogate(low)) {
                                    throw error("lone surrogate escape");
                                }
                                sb.append(u).append(low);
                            } else if (Character.isLowSurrogate(u)) {
                                throw error("lone surrogate escape");
                            } else {
                                sb.append(u);
                            }
                            break;
                        }
                        default:
                            throw error("invalid escape");
                    }
                } else {
                    sb.append(c);
                }
                if (sb.length() > limits.maxStringChars()) {
                    throw new JsonException("JSON string exceeds " + limits.maxStringChars() + " chars", true);
                }
            }
            return sb.toString();
        }

        private char readHex4() {
            if (pos + 4 > s.length()) {
                throw error("truncated unicode escape");
            }
            int v = 0;
            for (int i = 0; i < 4; i++) {
                int d = Character.digit(s.charAt(pos++), 16);
                if (d < 0) {
                    throw error("invalid unicode escape");
                }
                v = (v << 4) | d;
            }
            return (char) v;
        }

        private Object parseNumber() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            if (peek() == '0') {
                pos++;
            } else if (isDigit(peek()) && peek() != '0') {
                while (isDigit(peek())) {
                    pos++;
                }
            } else {
                throw error("invalid number");
            }
            boolean integral = true;
            if (peek() == '.') {
                integral = false;
                pos++;
                if (!isDigit(peek())) {
                    throw error("invalid number fraction");
                }
                while (isDigit(peek())) {
                    pos++;
                }
            }
            if (peek() == 'e' || peek() == 'E') {
                integral = false;
                pos++;
                if (peek() == '+' || peek() == '-') {
                    pos++;
                }
                if (!isDigit(peek())) {
                    throw error("invalid number exponent");
                }
                while (isDigit(peek())) {
                    pos++;
                }
            }
            if (pos - start > limits.maxNumberChars()) {
                throw new JsonException("JSON number is too long", true);
            }
            String text = s.substring(start, pos);
            if (integral) {
                BigInteger bi = new BigInteger(text);
                if (bi.bitLength() < 64) {
                    return bi.longValue();
                }
                return new BigDecimal(bi);
            }
            try {
                return new BigDecimal(text);
            } catch (NumberFormatException | ArithmeticException e) {
                throw error("number out of range");
            }
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private void expectLiteral(String lit) {
            if (!s.startsWith(lit, pos)) {
                throw error("invalid literal");
            }
            pos += lit.length();
        }

        private void expect(char c) {
            if (next() != c) {
                throw error("expected '" + c + "'");
            }
        }

        private char next() {
            if (pos >= s.length()) {
                throw error("unexpected end of input");
            }
            return s.charAt(pos++);
        }

        private char peek() {
            return pos < s.length() ? s.charAt(pos) : '\0';
        }

        private void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        private JsonException error(String what) {
            return new JsonException("malformed JSON at offset " + pos + ": " + what);
        }
    }

    // ------------------------------------------------------------------ writing

    /** Compact serialisation preserving map iteration order. */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, false);
        return sb.toString();
    }

    public static byte[] writeBytes(Object value) {
        return write(value).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Canonical serialisation used for digests: object keys sorted by UTF-16 code unit order (as in
     * RFC 8785), no whitespace, numbers normalised (1.0 and 1 are the same value). This is the
     * Aegis canonical form; it is not a claim of full RFC 8785 number-format compliance.
     */
    public static String canonical(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, true);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object v, boolean canonical) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String str) {
            writeString(sb, str);
        } else if (v instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
            sb.append(((Number) v).longValue());
        } else if (v instanceof BigInteger bi) {
            sb.append(bi);
        } else if (v instanceof BigDecimal bd) {
            sb.append(formatDecimal(bd));
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new JsonException("NaN and Infinity are not representable in JSON");
            }
            sb.append(formatDecimal(BigDecimal.valueOf(d)));
        } else if (v instanceof Map<?, ?> m) {
            Map<String, Object> map = (Map<String, Object>) m;
            Iterable<Map.Entry<String, Object>> entries = canonical ? new TreeMap<>(map).entrySet() : map.entrySet();
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : entries) {
                if (!(e.getKey() instanceof String)) {
                    throw new JsonException("JSON object keys must be strings");
                }
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, e.getKey());
                sb.append(':');
                writeValue(sb, e.getValue(), canonical);
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(sb, o, canonical);
            }
            sb.append(']');
        } else if (v instanceof Object[] arr) {
            writeValue(sb, List.of(arr), canonical);
        } else {
            throw new JsonException("unsupported JSON value type " + v.getClass().getName());
        }
    }

    private static String formatDecimal(BigDecimal bd) {
        BigDecimal n = bd.signum() == 0 ? BigDecimal.ZERO : bd.stripTrailingZeros();
        if (n.scale() <= 0 && n.precision() - n.scale() <= 19) {
            BigInteger bi = n.toBigIntegerExact();
            return bi.toString();
        }
        return n.toString();
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}
