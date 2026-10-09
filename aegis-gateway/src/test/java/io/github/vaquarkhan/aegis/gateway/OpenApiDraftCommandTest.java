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
package io.github.vaquarkhan.aegis.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The --openapi-draft command: writes once, refuses to overwrite, and the draft starts a gateway. */
class OpenApiDraftCommandTest {

    @TempDir
    Path dir;

    record Run(int code, String out, String err) {}

    private static Run cli(String... args) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(args, new ByteArrayInputStream(new byte[0]), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), Map.of("DRAFT_TEST_HMAC", "0123456789abcdef0123456789abcdef")::get, false);
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void draftIsWrittenOnceAndLoadsAsAGateway() throws Exception {
        Path spec = dir.resolve("pets.yaml");
        Files.writeString(spec, """
                openapi: 3.1.0
                info: {title: Pets, version: "1"}
                paths:
                  /pets/{id}:
                    get:
                      operationId: getPet
                      summary: Get a pet.
                      parameters: [{name: id, in: path, required: true, schema: {type: integer}}]
                      responses: {200: {description: ok}}
                """, StandardCharsets.UTF_8);
        Path draft = dir.resolve("draft.yaml");
        Run r = cli("--openapi-draft", spec.toString(), draft.toString(), "--source-id", "pets", "--base-url", "http://127.0.0.1:1");
        assertEquals(Main.OK, r.code(), r.err());
        assertTrue(r.out().contains("1 tools, all DESTRUCTIVE"), r.out());
        String text = Files.readString(draft, StandardCharsets.UTF_8);
        assertTrue(text.contains("name: \"pets_get_pet\"") && text.contains("class: DESTRUCTIVE"), text);

        assertEquals(Main.USAGE, cli("--openapi-draft", spec.toString(), draft.toString(), "--source-id", "pets",
                "--base-url", "http://127.0.0.1:1").code(), "never overwrites");
        assertEquals(text, Files.readString(draft, StandardCharsets.UTF_8));

        // The written draft plus a front door (the draft leaves host to the operator) starts the gateway.
        Files.writeString(draft, text + "\nhost:\n  identity: {type: jwt, jwt: {issuer: t, audience: t, algorithms: [HS256], hmacSecretEnv: DRAFT_TEST_HMAC}}\n"
                + "  stdio: {actor: reviewer, groups: [reviewers]}\n", StandardCharsets.UTF_8);
        Run start = cli("--config", draft.toString(), "--stdio");
        assertEquals(Main.OK, start.code(), start.err());
    }

    @Test
    void usageAndBrokenInputAreReported() throws Exception {
        assertEquals(Main.USAGE, cli("--openapi-draft").code());
        assertEquals(Main.USAGE, cli("--openapi-draft", "a", "b", "--source-id").code());
        assertEquals(Main.USAGE, cli("--openapi-draft", "a", "b", "--nope", "x").code());
        Path bad = dir.resolve("bad.json");
        Files.writeString(bad, "{\"swagger\":\"2.0\"}", StandardCharsets.UTF_8);
        Run r = cli("--openapi-draft", bad.toString(), dir.resolve("o.yaml").toString());
        assertEquals(Main.BROKEN, r.code());
        assertTrue(r.err().contains("OpenAPI 3.x"), r.err());
        assertTrue(!Files.exists(dir.resolve("o.yaml")));
    }
}
