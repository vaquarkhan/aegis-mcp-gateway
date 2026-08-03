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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.core.governance.OutputControls;
import org.junit.jupiter.api.Test;

/**
 * @author Viquar Khan
 */
class OutputControlsTest {

    @Test
    void truncatesAboveTheCeiling() {
        OutputControls controls = new OutputControls(16, false);
        String out = controls.boundAndRedact("0123456789abcdefghij");
        assertTrue(out.startsWith("0123456789abcdef"));
        assertTrue(out.endsWith(OutputControls.TRUNCATION_MARKER));
    }

    @Test
    void leavesShortOutputIntact() {
        OutputControls controls = new OutputControls(1024, false);
        assertEquals("hello", controls.boundAndRedact("hello"));
    }

    @Test
    void nullBecomesEmpty() {
        OutputControls controls = new OutputControls(1024, true);
        assertEquals("", controls.boundAndRedact(null));
    }

    @Test
    void redactsApiKeysAndPasswords() {
        OutputControls controls = new OutputControls(4096, true);
        String out = controls.boundAndRedact("{\"api_key\":\"abc123\",\"password\":\"hunter2\"}");
        assertFalse(out.contains("abc123"));
        assertFalse(out.contains("hunter2"));
        assertTrue(out.contains(OutputControls.REDACTION));
    }

    @Test
    void redactsBearerHeadersAndJwts() {
        OutputControls controls = new OutputControls(4096, true);
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r0";
        String out = controls.boundAndRedact("Authorization: Bearer " + jwt);
        assertFalse(out.contains(jwt));
    }

    @Test
    void redactsPrivateKeyBlocksAndAwsKeys() {
        OutputControls controls = new OutputControls(4096, true);
        assertFalse(controls.boundAndRedact("-----BEGIN RSA PRIVATE KEY-----")
                .contains("BEGIN RSA PRIVATE KEY"));
        assertFalse(controls.boundAndRedact("key=AKIAIOSFODNN7EXAMPLE").contains("AKIAIOSFODNN7EXAMPLE"));
    }

    @Test
    void redactsEmailAddresses() {
        OutputControls controls = new OutputControls(4096, true);
        assertFalse(controls.boundAndRedact("owner: alice@example.com").contains("alice@example.com"));
    }

    @Test
    void redactionCanBeDisabled() {
        OutputControls controls = new OutputControls(4096, false);
        assertTrue(controls.boundAndRedact("password: hunter2").contains("hunter2"));
    }

    @Test
    void boundingHappensBeforeRedaction() {
        // A secret that begins inside the retained window must still be redacted after truncation.
        OutputControls controls = new OutputControls(24, true);
        String out = controls.boundAndRedact("password: supersecretvalue-and-a-long-tail");
        assertFalse(out.contains("supersecretvalue"));
        assertTrue(out.contains(OutputControls.REDACTION));
    }
}
