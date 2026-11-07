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

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Regression for a bug class reported against other MCP gateways: a response filter written in an
 * expression language (jq) could read the gateway's own environment ({@code env}, {@code $ENV}) and
 * return credentials to the model. Aegis selects response parts with RFC 6901 JSON Pointer only, which
 * names a path and has no functions, variables or pipes.
 */
class JsonPointerExpressionTest {

    @Test
    void jqExpressionsAreRejectedAtParse() {
        for (String jq : new String[] {".token", "env", "$ENV", ".data | env.AWS_SECRET_ACCESS_KEY", "..", ".[] | select(.a)"}) {
            assertThrows(IllegalArgumentException.class, () -> JsonPointer.parse(jq), jq);
        }
    }

    @Test
    void expressionTextInsideAPointerIsALiteralKeyNotCode() {
        Map<String, Object> body = Json.parseObject("{\"data\":{\"rows\":[1]}}");
        assertEquals(Optional.empty(), JsonPointer.parse("/$ENV").resolve(body));
        assertEquals(Optional.empty(), JsonPointer.parse("/data | env").resolve(body));
        assertEquals(Optional.empty(), JsonPointer.parse("/env/HOME").resolve(body));
        Map<String, Object> literal = Json.parseObject("{\"$ENV\":\"just a key\"}");
        assertEquals(Optional.of("just a key"), JsonPointer.parse("/$ENV").resolve(literal));
    }

    @Test
    void aPointerOnlyEverReturnsPartsOfTheDocumentItWasGiven() {
        Map<String, Object> body = Json.parseObject("{\"a\":{\"b\":\"c\"}}");
        String home = System.getenv("PATH");
        Optional<Object> whole = JsonPointer.parse("").resolve(body);
        assertEquals(Optional.of(body), whole);
        assertTrue(home == null || !Json.write(whole.orElseThrow()).contains(home));
    }
}
