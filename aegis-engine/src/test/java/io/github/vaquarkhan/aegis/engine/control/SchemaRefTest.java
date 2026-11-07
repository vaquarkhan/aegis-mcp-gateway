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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.engine.json.Json;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code $ref} handling in tool input schemas. A tool schema comes from the upstream server, so a
 * reference in it is untrusted: Aegis resolves local references only and never fetches a remote one
 * (a remote fetch would be a server-side request to a URL the operator never allowed).
 */
class SchemaRefTest {

    private static Map<String, Object> schema(String json) {
        return Json.parseObject(json);
    }

    @Test
    void remoteRefIsDeniedAndNeverFetched() {
        // A loopback port with nothing listening: if the validator tried to fetch, the test would hang
        // or fail differently. The reason must name the remote reference.
        Map<String, Object> s = schema("{\"type\":\"object\",\"properties\":{\"q\":"
                + "{\"$ref\":\"http://127.0.0.1:9/schemas/q.json\"}}}");
        String r = SchemaValidator.validate(s, Json.parse("{\"q\":\"x\"}"));
        assertNotNull(r);
        assertTrue(r.contains("remote $ref"), r);
        assertTrue(r.contains("/q"), r);
    }

    @Test
    void relativeFileRefAndNonStringRefAreDenied() {
        assertNotNull(SchemaValidator.validate(schema("{\"$ref\":\"other.json#/x\"}"), Json.parse("{}")));
        assertNotNull(SchemaValidator.validate(schema("{\"$ref\":\"file:///etc/passwd\"}"), Json.parse("{}")));
        assertNotNull(SchemaValidator.validate(schema("{\"$ref\":42}"), Json.parse("{}")));
    }

    @Test
    void localDefsRefIsEnforced() {
        Map<String, Object> s = schema("{\"type\":\"object\",\"$defs\":{\"topic\":{\"type\":\"string\",\"maxLength\":5}},"
                + "\"properties\":{\"topic\":{\"$ref\":\"#/$defs/topic\"}},\"required\":[\"topic\"]}");
        assertNull(SchemaValidator.validate(s, Json.parse("{\"topic\":\"abc\"}")));
        assertNotNull(SchemaValidator.validate(s, Json.parse("{\"topic\":\"abcdefgh\"}")));
        assertNotNull(SchemaValidator.validate(s, Json.parse("{\"topic\":7}")));
    }

    @Test
    void legacyDefinitionsAndEscapedPointerResolve() {
        Map<String, Object> s = schema("{\"definitions\":{\"a/b\":{\"enum\":[\"x\"]},\"t~n\":{\"type\":\"integer\"}},"
                + "\"type\":\"object\",\"properties\":{\"p\":{\"$ref\":\"#/definitions/a~1b\"},"
                + "\"n\":{\"$ref\":\"#/definitions/t~0n\"}}}");
        assertNull(SchemaValidator.validate(s, Json.parse("{\"p\":\"x\",\"n\":3}")));
        assertNotNull(SchemaValidator.validate(s, Json.parse("{\"p\":\"y\"}")));
        assertNotNull(SchemaValidator.validate(s, Json.parse("{\"n\":\"3\"}")));
    }

    @Test
    void refAppliesAlongsideSiblingKeywords() {
        Map<String, Object> s = schema("{\"$defs\":{\"s\":{\"type\":\"string\"}},"
                + "\"type\":\"object\",\"properties\":{\"v\":{\"$ref\":\"#/$defs/s\",\"maxLength\":2}}}");
        assertNull(SchemaValidator.validate(s, Json.parse("{\"v\":\"ab\"}")));
        assertNotNull(SchemaValidator.validate(s, Json.parse("{\"v\":\"abc\"}")), "sibling maxLength still applies");
        assertNotNull(SchemaValidator.validate(s, Json.parse("{\"v\":1}")), "referenced type still applies");
    }

    @Test
    void unresolvableAndAnchorRefsFailClosed() {
        assertNotNull(SchemaValidator.validate(schema("{\"$ref\":\"#/$defs/missing\"}"), Json.parse("{}")));
        assertNotNull(SchemaValidator.validate(schema("{\"$ref\":\"#someAnchor\"}"), Json.parse("{}")));
        assertNotNull(SchemaValidator.validate(schema("{\"$defs\":{\"x\":true},\"$ref\":\"#/$defs/x\"}"), Json.parse("{}")),
                "a boolean subschema is not an object schema this validator evaluates, so it fails closed");
    }

    @Test
    void recursiveSchemaValidatesNestedValues() {
        Map<String, Object> s = schema("{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},"
                + "\"child\":{\"$ref\":\"#\"}},\"additionalProperties\":false}");
        assertNull(SchemaValidator.validate(s, Json.parse("{\"name\":\"a\",\"child\":{\"name\":\"b\",\"child\":{}}}")));
        String r = SchemaValidator.validate(s, Json.parse("{\"child\":{\"child\":{\"evil\":1}}}"));
        assertNotNull(r);
        assertTrue(r.contains("/child/child/evil"), r);
    }

    @Test
    void selfReferenceCycleIsBounded() {
        // {"$ref":"#"} on the root never consumes the value; the depth limit ends it with a deny.
        String r = SchemaValidator.validate(schema("{\"$ref\":\"#\"}"), Json.parse("{}"));
        assertNotNull(r);
        assertTrue(r.contains("too deep"), r);
        String mutual = SchemaValidator.validate(schema("{\"$defs\":{\"a\":{\"$ref\":\"#/$defs/b\"},"
                + "\"b\":{\"$ref\":\"#/$defs/a\"}},\"$ref\":\"#/$defs/a\"}"), Json.parse("1"));
        assertNotNull(mutual);
    }
}
