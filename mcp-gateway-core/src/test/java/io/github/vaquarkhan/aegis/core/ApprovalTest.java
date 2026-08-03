package io.github.vaquarkhan.aegis.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.core.auth.NonceStore;
import io.github.vaquarkhan.aegis.core.governance.Approval;
import io.github.vaquarkhan.aegis.core.governance.ApprovalTokens;
import org.junit.jupiter.api.Test;

/**
 * @author Viquar Khan
 */
class ApprovalTest {

    private static final String SECRET = "unit-test-approval-secret";

    @Test
    void roundTripSucceedsOnce() {
        Approval approval = new Approval(SECRET, new NonceStore());
        String token = approval.mint("cancel_job", "job-42", 60_000L);
        assertTrue(approval.verify(token, "cancel_job", "job-42"));
    }

    @Test
    void approvalTokensIsTheLldNameForTheSameMinter() {
        NonceStore nonces = new NonceStore();
        ApprovalTokens tokens = new ApprovalTokens(SECRET, nonces);
        assertTrue(tokens.configured());

        String token = tokens.mint("stop_job", "job-42", 60_000L);
        assertTrue(new Approval(SECRET, nonces).verify(token, "stop_job", "job-42"),
                "a token minted through either name verifies through the other");
        assertFalse(tokens.verify(token, "stop_job", "job-42"), "nonce must still be single use");
    }

    @Test
    void replayIsRejected() {
        Approval approval = new Approval(SECRET, new NonceStore());
        String token = approval.mint("cancel_job", "job-42", 60_000L);
        assertTrue(approval.verify(token, "cancel_job", "job-42"));
        assertFalse(approval.verify(token, "cancel_job", "job-42"), "nonce must be single use");
    }

    @Test
    void expiredTokenIsRejected() throws InterruptedException {
        Approval approval = new Approval(SECRET, new NonceStore());
        String token = approval.mint("stop_job", "job-1", 1L);
        Thread.sleep(25L);
        assertFalse(approval.verify(token, "stop_job", "job-1"));
    }

    @Test
    void wrongScopeIsRejected() {
        Approval approval = new Approval(SECRET, new NonceStore());
        String token = approval.mint("cancel_job", "job-42", 60_000L);
        assertFalse(approval.verify(token, "cancel_job", "job-99"));
    }

    @Test
    void wrongToolIsRejected() {
        Approval approval = new Approval(SECRET, new NonceStore());
        String token = approval.mint("cancel_job", "job-42", 60_000L);
        assertFalse(approval.verify(token, "stop_job", "job-42"));
    }

    @Test
    void wildcardTokenMatchesAnyScope() {
        Approval approval = new Approval(SECRET, new NonceStore());
        String token = approval.mint("rescale_job", "*", 60_000L);
        assertTrue(approval.verify(token, "rescale_job", "job-77"));
    }

    @Test
    void tamperedSignatureIsRejected() {
        Approval approval = new Approval(SECRET, new NonceStore());
        String token = approval.mint("cancel_job", "job-42", 60_000L);
        char last = token.charAt(token.length() - 1);
        char repl = last == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, token.length() - 1) + repl;
        assertNotEquals(token, tampered);
        assertFalse(approval.verify(tampered, "cancel_job", "job-42"));
    }

    @Test
    void tokenFromAnotherSecretIsRejected() {
        Approval minter = new Approval("other-secret", new NonceStore());
        Approval verifier = new Approval(SECRET, new NonceStore());
        String token = minter.mint("cancel_job", "job-42", 60_000L);
        assertFalse(verifier.verify(token, "cancel_job", "job-42"));
    }

    @Test
    void verificationFailsClosedWithoutSecret() {
        Approval unconfigured = new Approval(null, new NonceStore());
        assertFalse(unconfigured.configured());
        String token = unconfigured.mint("cancel_job", "job-42", 60_000L);
        assertFalse(unconfigured.verify(token, "cancel_job", "job-42"));
    }

    @Test
    void malformedTokensAreRejected() {
        Approval approval = new Approval(SECRET, new NonceStore());
        assertFalse(approval.verify(null, "cancel_job", "*"));
        assertFalse(approval.verify("", "cancel_job", "*"));
        assertFalse(approval.verify("no-dot", "cancel_job", "*"));
        assertFalse(approval.verify(".", "cancel_job", "*"));
        assertFalse(approval.verify("!!!.!!!", "cancel_job", "*"));
    }
}
