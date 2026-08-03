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
        String tampered = token.substring(0, token.length() - 2) + "AA";
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
