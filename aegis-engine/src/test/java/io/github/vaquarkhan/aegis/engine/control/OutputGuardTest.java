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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.model.ToolResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Binary tool output is refused by default and, when allowed, passes byte-for-byte. */
class OutputGuardTest {

    /** Base64 that happens to contain a JWT-shaped run, so a string redactor would corrupt it. */
    static final String DATA = "eyJhbGciOi" + "JIUzI1NiJ9.eyJzdWIiOiJ4eHh4eHgifQ.c2lnbmF0dXJlMTIz";

    static ToolResult imageResult() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", List.of(
                Map.of("type", "text", "text", "chart for Bearer abcdefghijklmnopqrstuvwxyz0123"),
                Map.of("type", "image", "mimeType", "image/png", "data", DATA)));
        body.put("isError", false);
        return new ToolResult(body);
    }

    @Test
    void binaryToolOutputIsWithheldByDefault() {
        OutputGuard.Guarded g = OutputGuard.defaults().apply(imageResult());
        assertTrue(g.binaryRefused());
        assertTrue(g.result().isError());
        assertFalse(Json.write(g.result().body()).contains(DATA), "the binary payload is not passed on");
        // An embedded resource blob is binary too.
        ToolResult blob = new ToolResult(Map.of("content", List.of(Map.of("type", "resource",
                "resource", Map.of("uri", "file:///a.bin", "blob", "AAAA")))));
        assertTrue(OutputGuard.defaults().apply(blob).binaryRefused());
    }

    @Test
    void allowedBinaryPassesUnchangedWhileTextIsStillRedacted() {
        OutputGuard guard = new OutputGuard(1 << 20, OutputGuard.DEFAULT_REDACTIONS, true);
        OutputGuard.Guarded g = guard.apply(imageResult());
        assertFalse(g.binaryRefused());
        String out = Json.write(g.result().body());
        assertTrue(out.contains(DATA), "binary data is copied byte-for-byte, never regex-rewritten");
        assertFalse(out.contains("abcdefghijklmnopqrstuvwxyz0123"), "text parts are still redacted");
        assertEquals(1, g.redactions());
    }

    @Test
    void textOnlyOutputIsUnaffected() {
        OutputGuard.Guarded g = OutputGuard.defaults().apply(ToolResult.text("plain", false));
        assertFalse(g.binaryRefused());
        assertFalse(g.result().isError());
    }
}
