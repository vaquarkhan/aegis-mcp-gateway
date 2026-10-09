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
package io.github.vaquarkhan.aegis.engine.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HashChainFileAuditSinkTest {

    @TempDir
    Path dir;

    @Test
    void chainSurvivesRestartAndVerifies() throws Exception {
        Path f = dir.resolve("audit.jsonl");
        try (HashChainFileAuditSink s = new HashChainFileAuditSink(f, true)) {
            s.append(Map.of("event", "ALLOW_INTENT", "tool", "a"));
            s.append(Map.of("event", "EXECUTION_RESULT", "tool", "a"));
        }
        String headBefore;
        try (HashChainFileAuditSink s = new HashChainFileAuditSink(f, true)) {
            headBefore = s.head();
            AuditChainVerifier.Report r = s.verify();
            assertTrue(r.ok(), r.error());
            assertEquals(2, r.records());
            s.append(Map.of("event", "DECISION", "tool", "c"));
        }
        AuditChainVerifier.Report r = AuditChainVerifier.verify(f);
        assertTrue(r.ok(), r.error());
        assertEquals(3, r.records());
        assertFalse(headBefore.equals(r.head()));
    }

    @Test
    void aSecondWriterInTheSameProcessIsRefusedAndTheChainStaysIntact() throws Exception {
        Path f = dir.resolve("audit.jsonl");
        try (HashChainFileAuditSink first = new HashChainFileAuditSink(f, false)) {
            first.append(Map.of("event", "ALLOW_INTENT", "tool", "a"));
            IOException e = assertThrows(IOException.class, () -> new HashChainFileAuditSink(f, false));
            assertTrue(e.getMessage().contains("already open for writing"), e.getMessage());
            first.append(Map.of("event", "EXECUTION_RESULT", "tool", "a"));
        }
        // Once the first writer closed, a new one continues the same chain.
        try (HashChainFileAuditSink second = new HashChainFileAuditSink(f, false)) {
            second.append(Map.of("event", "DECISION", "tool", "b"));
        }
        AuditChainVerifier.Report r = AuditChainVerifier.verify(f);
        assertTrue(r.ok(), r.error());
        assertEquals(3, r.records());
    }

    @Test
    void editingOrDeletingARecordIsDetected() throws Exception {
        Path f = dir.resolve("audit.jsonl");
        try (HashChainFileAuditSink s = new HashChainFileAuditSink(f, false)) {
            for (int i = 0; i < 4; i++) {
                s.append(Map.of("event", "DECISION", "n", (long) i, "outcome", "DENIED:POLICY_DENIED:step3"));
            }
        }
        List<String> lines = Files.readAllLines(f);
        List<String> edited = new java.util.ArrayList<>(lines);
        edited.set(1, lines.get(1).replace("DENIED:POLICY_DENIED:step3", "ALLOWED"));
        Path g = dir.resolve("edited.jsonl");
        Files.write(g, edited);
        assertFalse(AuditChainVerifier.verify(g).ok());

        List<String> deleted = new java.util.ArrayList<>(lines);
        deleted.remove(2);
        Path h = dir.resolve("deleted.jsonl");
        Files.write(h, deleted);
        assertFalse(AuditChainVerifier.verify(h).ok());
    }

    @Test
    void tornLastRecordRefusesToContinueTheChain() throws Exception {
        Path f = dir.resolve("audit.jsonl");
        try (HashChainFileAuditSink s = new HashChainFileAuditSink(f, false)) {
            s.append(Map.of("event", "DECISION"));
        }
        Files.write(f, "{\"event\":\"ALLOW_IN".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        assertThrows(IOException.class, () -> new HashChainFileAuditSink(f, false));
    }

    @Test
    void recordLargerThanTheLegacyTailScanSurvivesRestartAndVerification() throws Exception {
        Path f = dir.resolve("audit.jsonl");
        String payload = "x".repeat((1 << 20) + 4096);
        try (HashChainFileAuditSink s = new HashChainFileAuditSink(f, true)) {
            s.append(Map.of("event", "ALLOW_INTENT", "payload", payload));
        }
        try (HashChainFileAuditSink s = new HashChainFileAuditSink(f, true)) {
            assertTrue(s.verify().ok(), "a complete bounded record must not deny restart");
            assertEquals(1, s.verify().records());
        }
    }

    @Test
    void oversizedRecordIsRefusedBeforeItCanPoisonRestartRecovery() throws Exception {
        Path f = dir.resolve("audit.jsonl");
        try (HashChainFileAuditSink s = new HashChainFileAuditSink(f, false)) {
            s.append(Map.of("event", "ALLOW_INTENT"));
            String head = s.head();
            assertThrows(IOException.class, () -> s.append(Map.of("event", "EXECUTION_RESULT", "payload", "x".repeat(8 << 20))));
            assertEquals(head, s.head(), "a refused record must not advance the chain head");
        }
        AuditChainVerifier.Report report = AuditChainVerifier.verify(f);
        assertTrue(report.ok(), report.error());
        assertEquals(1, report.records());
    }

    @Test
    void liveVerificationUsesTheCommittedPrefixNotAConcurrentPartialRecord() throws Exception {
        Path f = dir.resolve("audit.jsonl");
        try (HashChainFileAuditSink s = new HashChainFileAuditSink(f, false)) {
            s.append(Map.of("event", "ALLOW_INTENT"));
            Files.write(f, "{\"event\":\"EXECUTION_RESULT".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
            AuditChainVerifier.Report report = s.verify();
            assertTrue(report.ok(), report.error());
            assertEquals(1, report.records());
        }
    }
}
