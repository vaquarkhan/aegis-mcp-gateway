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
    void truncatesByUtf8BytesNotChars() {
        OutputControls controls = new OutputControls(5, false);
        String out = controls.bound("\u4e00\u4e01\u4e02");
        assertTrue(out.endsWith(OutputControls.TRUNCATION_MARKER));
        assertEquals("\u4e00" + OutputControls.TRUNCATION_MARKER, out);
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
    void redactsEmailAddressesReferentially() {
        OutputControls controls = new OutputControls(4096, true);
        String out = controls.boundAndRedact("owner: alice@example.com; cc: alice@example.com");
        assertFalse(out.contains("alice@example.com"));
        assertTrue(out.contains("PERSON_1"));
        assertEquals(2, out.split("PERSON_1", -1).length - 1);
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
