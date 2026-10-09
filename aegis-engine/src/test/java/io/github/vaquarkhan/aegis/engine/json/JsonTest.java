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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void duplicateKeysAreRejectedBecauseProxyAndBackendCouldDisagree() {
        JsonException e = assertThrows(JsonException.class,
                () -> Json.parse("{\"name\":\"list_jobs\",\"name\":\"delete_cluster\"}"));
        assertTrue(e.getMessage().contains("duplicate"));
    }

    @Test
    void depthAndSizeLimitsAreEnforcedDuringParse() {
        String deep = "[".repeat(40) + "]".repeat(40);
        assertTrue(assertThrows(JsonException.class, () -> Json.parse(deep, JsonLimits.DEFAULT)).limitExceeded());
        JsonLimits tiny = JsonLimits.DEFAULT.withMaxBytes(10);
        assertTrue(assertThrows(JsonException.class,
                () -> Json.parse("{\"a\":\"0123456789\"}".getBytes(StandardCharsets.UTF_8), tiny)).limitExceeded());
    }

    @Test
    void invalidUtf8LoneSurrogatesAndTrailingContentAreRejected() {
        assertThrows(JsonException.class, () -> Json.parse(new byte[] {'"', (byte) 0xC3, '"'}, JsonLimits.DEFAULT));
        assertThrows(JsonException.class, () -> Json.parse("\"\\ud800\""));
        assertThrows(JsonException.class, () -> Json.parse("{} {}"));
        assertThrows(JsonException.class, () -> Json.parse("[1,]"));
        assertThrows(JsonException.class, () -> Json.parse("01"));
        assertThrows(JsonException.class, () -> Json.parse("\"a\u0001\""));
    }

    @Test
    void numbersAreExactAndCanonicalFormNormalisesThem() {
        Object big = Json.parse("123456789012345678901234567890");
        assertEquals(new BigDecimal("123456789012345678901234567890"), big);
        assertEquals(42L, Json.parse("42"));
        assertEquals(Json.canonical(Json.parse("{\"b\":1.0,\"a\":[2e0,\"x\"]}")),
                Json.canonical(Json.parse("{\"a\":[2,\"x\"],\"b\":1}")));
        assertEquals("{\"a\":[2,\"x\"],\"b\":1}", Json.canonical(Json.parse("{\"b\":1.0,\"a\":[2e0,\"x\"]}")));
        assertEquals("1E+999999", Json.canonical(Json.parse("1e999999")));
    }

    @Test
    void roundTripPreservesStructureAndEscapes() {
        String src = "{\"t\":\"line\\nbreak \\\"q\\\" \\u00e9 \\ud83d\\ude00\",\"n\":null,\"b\":true,\"l\":[1,-2.5]}";
        Object v = Json.parse(src);
        assertEquals(v, Json.parse(Json.write(v)));
        Map<?, ?> m = (Map<?, ?>) v;
        assertEquals(List.of(1L, new BigDecimal("-2.5")), m.get("l"));
    }

    @Test
    void jsonPointerFollowsRfc6901() {
        Object doc = Json.parse("{\"a/b\":{\"m~n\":[10,20]}}");
        assertEquals(20L, JsonPointer.parse("/a~1b/m~0n/1").resolve(doc).orElseThrow());
        assertTrue(JsonPointer.parse("/a~1b/m~0n/01").resolve(doc).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> JsonPointer.parse("a"));
    }
}
