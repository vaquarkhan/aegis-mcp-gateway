/*
 * Licensed to the Aegis MCP Gateway project under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vaquarkhan.aegis.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.core.auth.CallerIdentity;
import io.github.vaquarkhan.aegis.core.auth.TokenRegistry;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * @author Viquar Khan
 */
class TokenRegistryTest {

    private static final String TOKEN_A = "token-alpha";
    private static final String TOKEN_B = "token-beta";

    /** Pre-LLD four field line: one scope list covers resources, jobs and jars. */
    private static String line(String caller, String token, String scopes, boolean readonly) {
        return caller + " : " + TokenRegistry.sha256Hex(token) + " : " + scopes + " : " + readonly;
    }

    /** LLD section 15 line: separate jobs and jars allow lists. */
    private static String lldLine(String caller, String token, String jobs, String jars, boolean readonly) {
        return caller + " : " + TokenRegistry.sha256Hex(token)
                + " : " + jobs + " : " + jars + " : " + readonly;
    }

    @Test
    void parsesLldJobAndJarAllowLists() {
        TokenRegistry registry = TokenRegistry.parse(List.of(
                lldLine("ops", TOKEN_A, "job-1,job-2", "jar-1", false)), "test");

        CallerIdentity ops = registry.authenticateBearerToken(TOKEN_A).orElseThrow();
        assertEquals("ops", ops.subject());
        assertTrue(ops.jobAllowed("job-1"));
        assertFalse(ops.jobAllowed("job-9"));
        assertTrue(ops.jarAllowed("jar-1"));
        assertFalse(ops.jarAllowed("jar-9"), "a job id must not unlock a jar id");
        assertTrue(ops.scopeAllowed("job-2"));
    }

    @Test
    void parsesLldLineWithOutboundHeader() {
        TokenRegistry registry = TokenRegistry.parse(List.of(
                "ops : " + TokenRegistry.sha256Hex(TOKEN_A)
                        + " : job-1 : jar-1 : false : Basic dXNlcjpwYXNz"), "test");
        CallerIdentity ops = registry.authenticateBearerToken(TOKEN_A).orElseThrow();
        assertEquals("Basic dXNlcjpwYXNz", ops.outboundAuthHeader());
        assertTrue(ops.jarAllowed("jar-1"));
        assertFalse(ops.readonly());
    }

    @Test
    void legacyFourFieldLineFillsEveryAllowList() {
        TokenRegistry registry = TokenRegistry.parse(List.of(
                line("ops", TOKEN_A, "job-1", false)), "test");
        CallerIdentity ops = registry.authenticateBearerToken(TOKEN_A).orElseThrow();
        assertTrue(ops.jobAllowed("job-1"));
        assertTrue(ops.jarAllowed("job-1"));
        assertTrue(ops.scopeAllowed("job-1"));
    }

    @Test
    void parsesEntriesAndAuthenticates() {
        TokenRegistry registry = TokenRegistry.parse(List.of(
                "# comment",
                "",
                line("ops", TOKEN_A, "job-1,job-2", false),
                line("viewer", TOKEN_B, "*", true)), "test");

        assertEquals(2, registry.size());

        Optional<CallerIdentity> ops = registry.authenticateBearerToken(TOKEN_A);
        assertTrue(ops.isPresent());
        assertEquals("ops", ops.get().callerId());
        assertFalse(ops.get().readonly());
        assertTrue(ops.get().scopeAllowed("job-1"));
        assertFalse(ops.get().scopeAllowed("job-9"));

        Optional<CallerIdentity> viewer = registry.authenticateBearerToken(TOKEN_B);
        assertTrue(viewer.isPresent());
        assertTrue(viewer.get().readonly());
        assertTrue(viewer.get().scopeAllowed("anything"));
    }

    @Test
    void unknownTokenIsRejected() {
        TokenRegistry registry = TokenRegistry.parse(List.of(line("ops", TOKEN_A, "*", false)), "test");
        assertTrue(registry.authenticateBearerToken("wrong").isEmpty());
        assertTrue(registry.authenticateBearerToken("").isEmpty());
        assertTrue(registry.authenticateBearerToken(null).isEmpty());
    }

    @Test
    void optionalOutboundHeaderIsParsed() {
        // Five fields with a boolean in position four is the legacy form, not the LLD form.
        TokenRegistry registry = TokenRegistry.parse(List.of(
                "ops : " + TokenRegistry.sha256Hex(TOKEN_A) + " : * : false : Basic dXNlcjpwYXNz"), "test");
        CallerIdentity ops = registry.authenticateBearerToken(TOKEN_A).orElseThrow();
        assertEquals("Basic dXNlcjpwYXNz", ops.outboundAuthHeader());
        assertFalse(ops.readonly());
    }

    @Test
    void emptyFileIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> TokenRegistry.parse(List.of("# only a comment"), "test"));
    }

    @Test
    void emptyScopeListIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> TokenRegistry.parse(List.of(line("ops", TOKEN_A, "", false)), "test"));
        assertThrows(IllegalArgumentException.class,
                () -> TokenRegistry.parse(List.of(lldLine("ops", TOKEN_A, "job-1", "", false)), "test"));
    }

    @Test
    void malformedLinesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> TokenRegistry.parse(List.of("ops : notahash : * : false"), "test"));
        assertThrows(IllegalArgumentException.class,
                () -> TokenRegistry.parse(List.of("ops : " + TokenRegistry.sha256Hex(TOKEN_A)), "test"));
        assertThrows(IllegalArgumentException.class,
                () -> TokenRegistry.parse(List.of(" : " + TokenRegistry.sha256Hex(TOKEN_A) + " : * : false"),
                        "test"));
    }

    @Test
    void duplicateTokenHashIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> TokenRegistry.parse(List.of(
                line("ops", TOKEN_A, "*", false),
                line("other", TOKEN_A, "*", true)), "test"));
    }

    @Test
    void hashesAreStableAndLowercaseHex() {
        String hash = TokenRegistry.sha256Hex(TOKEN_A);
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
        assertEquals(hash, TokenRegistry.sha256Hex(TOKEN_A));
    }
}
