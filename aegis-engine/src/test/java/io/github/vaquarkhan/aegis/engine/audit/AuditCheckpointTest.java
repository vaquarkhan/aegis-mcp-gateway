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

import io.github.vaquarkhan.aegis.engine.json.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditCheckpointTest {

    @TempDir
    Path dir;

    private AuditCheckpointPolicy policy(Path cp, long everyRecords, Duration every) {
        return new AuditCheckpointPolicy(cp, everyRecords, every, Clock.systemUTC());
    }

    private static void write(HashChainFileAuditSink s, int n, String tag) throws IOException {
        for (int i = 0; i < n; i++) {
            s.append(Map.of("event", "DECISION", "tool", tag + i));
        }
    }

    private static List<Map<String, Object>> lines(Path f) throws IOException {
        return Files.readAllLines(f, StandardCharsets.UTF_8).stream().map(Json::parseObject).toList();
    }

    @Test
    void headsAreExportedEveryNRecordsAndAtCloseAndTheVerifierAcceptsThem() throws Exception {
        Path journal = dir.resolve("audit.jsonl");
        Path cp = dir.resolve("anchor").resolve("checkpoints.jsonl");
        String head;
        try (HashChainFileAuditSink s = new HashChainFileAuditSink(journal, true, policy(cp, 2, Duration.ZERO))) {
            write(s, 5, "t");
            head = s.head();
        }
        List<Map<String, Object>> cps = lines(cp);
        assertEquals(List.of(2L, 4L, 5L), cps.stream().map(c -> c.get("seq")).toList());
        assertEquals(List.of("records", "records", "close"), cps.stream().map(c -> c.get("reason")).toList());
        assertEquals(head, cps.get(2).get("head"));
        assertEquals("audit.jsonl", cps.get(0).get("journal"));

        AuditCheckpointVerifier.Report r = AuditCheckpointVerifier.verify(journal, cp);
        assertTrue(r.ok(), r.error());
        assertEquals(5, r.records());
        assertEquals(3, r.checkpoints());
        assertEquals(5, r.checkpointSeq());

        // A restart continues both files; the recovered head is already exported, so open adds nothing.
        try (HashChainFileAuditSink s = new HashChainFileAuditSink(journal, true, policy(cp, 2, Duration.ZERO))) {
            write(s, 2, "u");
        }
        assertEquals(List.of(2L, 4L, 5L, 7L), lines(cp).stream().map(c -> c.get("seq")).toList());
        assertTrue(AuditCheckpointVerifier.verify(journal, cp).ok());
    }

    @Test
    void aJournalWrittenWithoutCheckpointsIsAnchoredWhenCheckpointingStarts() throws Exception {
        Path journal = dir.resolve("audit.jsonl");
        Path cp = dir.resolve("checkpoints.jsonl");
        try (HashChainFileAuditSink s = new HashChainFileAuditSink(journal, false)) {
            write(s, 3, "t");
        }
        try (HashChainFileAuditSink s = new HashChainFileAuditSink(journal, false, policy(cp, 100, Duration.ZERO))) {
            assertEquals(1, lines(cp).size());
            assertEquals("open", lines(cp).get(0).get("reason"));
            assertEquals(3L, lines(cp).get(0).get("seq"));
            assertEquals(s.head(), lines(cp).get(0).get("head"));
        }
    }

    @Test
    void aTruncatedJournalIsRefusedAtOpenAndByTheVerifierWithoutExportingItsHead() throws Exception {
        Path journal = dir.resolve("audit.jsonl");
        Path cp = dir.resolve("checkpoints.jsonl");
        try (HashChainFileAuditSink s = new HashChainFileAuditSink(journal, true, policy(cp, 2, Duration.ZERO))) {
            write(s, 4, "t");
        }
        // Drop the last two records: the remaining chain is still internally valid.
        List<String> kept = Files.readAllLines(journal, StandardCharsets.UTF_8).subList(0, 2);
        Files.write(journal, kept, StandardCharsets.UTF_8);
        assertTrue(AuditChainVerifier.verify(journal).ok(), "the chain alone cannot see a truncation");
        byte[] before = Files.readAllBytes(cp);

        IOException e = assertThrows(IOException.class,
                () -> new HashChainFileAuditSink(journal, true, policy(cp, 2, Duration.ZERO)));
        assertTrue(e.getMessage().contains("truncated or replaced"), e.getMessage());
        assertEquals(new String(before, StandardCharsets.UTF_8), Files.readString(cp), "a refused open exports nothing");

        AuditCheckpointVerifier.Report r = AuditCheckpointVerifier.verify(journal, cp);
        assertFalse(r.ok());
        assertTrue(r.error().contains("truncated or replaced"), r.error());

        // The refused open released both files: a second attempt fails on the same check, not on a lock.
        IOException again = assertThrows(IOException.class,
                () -> new HashChainFileAuditSink(journal, true, policy(cp, 2, Duration.ZERO)));
        assertTrue(again.getMessage().contains("truncated or replaced"), again.getMessage());
    }

    @Test
    void aRegeneratedJournalWithTheSameLengthIsRefusedAtOpenAndByTheVerifier() throws Exception {
        Path journal = dir.resolve("audit.jsonl");
        Path cp = dir.resolve("checkpoints.jsonl");
        try (HashChainFileAuditSink s = new HashChainFileAuditSink(journal, true, policy(cp, 2, Duration.ZERO))) {
            write(s, 4, "real");
        }
        // Someone rebuilds a fresh, internally consistent chain of the same length with other content.
        Files.delete(journal);
        try (HashChainFileAuditSink s = new HashChainFileAuditSink(journal, true)) {
            write(s, 4, "forged");
        }
        assertTrue(AuditChainVerifier.verify(journal).ok(), "the chain alone cannot see a regenerated journal");

        IOException e = assertThrows(IOException.class,
                () -> new HashChainFileAuditSink(journal, true, policy(cp, 2, Duration.ZERO)));
        assertTrue(e.getMessage().contains("was rewritten"), e.getMessage());

        AuditCheckpointVerifier.Report r = AuditCheckpointVerifier.verify(journal, cp);
        assertFalse(r.ok());
        assertTrue(r.error().startsWith("record 2 is "), r.error());
        assertTrue(r.error().contains("rewritten"), r.error());
    }

    @Test
    void theCheckpointFileCannotBeTheJournalOrBeSharedBetweenTwoJournals() throws Exception {
        Path journal = dir.resolve("audit.jsonl");
        IOException same = assertThrows(IOException.class,
                () -> new HashChainFileAuditSink(journal, false, policy(dir.resolve(".").resolve("audit.jsonl"), 1, Duration.ZERO)));
        assertTrue(same.getMessage().contains("must not be the journal"), same.getMessage());

        Path cp = dir.resolve("checkpoints.jsonl");
        try (HashChainFileAuditSink a = new HashChainFileAuditSink(dir.resolve("a.jsonl"), false, policy(cp, 1, Duration.ZERO))) {
            IOException shared = assertThrows(IOException.class,
                    () -> new HashChainFileAuditSink(dir.resolve("b.jsonl"), false, policy(cp, 1, Duration.ZERO)));
            assertTrue(shared.getMessage().contains("already open for writing"), shared.getMessage());
            a.append(Map.of("event", "DECISION"));
        }
        // A file that belongs to journal a is refused for journal b, at open and offline.
        IOException other = assertThrows(IOException.class,
                () -> new HashChainFileAuditSink(dir.resolve("b.jsonl"), false, policy(cp, 1, Duration.ZERO)));
        assertTrue(other.getMessage().contains("belongs to journal 'a.jsonl'"), other.getMessage());
    }

    @Test
    void theIntervalExportsANewHeadWithoutFurtherRecords() throws Exception {
        Path journal = dir.resolve("audit.jsonl");
        Path cp = dir.resolve("checkpoints.jsonl");
        try (HashChainFileAuditSink s = new HashChainFileAuditSink(journal, false, policy(cp, 1000, Duration.ofMillis(50)))) {
            write(s, 1, "t");
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline && (!Files.exists(cp) || Files.size(cp) == 0)) {
                Thread.sleep(20);
            }
            List<Map<String, Object>> cps = lines(cp);
            assertEquals(1, cps.size(), String.valueOf(cps));
            assertEquals("interval", cps.get(0).get("reason"));
            assertEquals(s.head(), cps.get(0).get("head"));
            Thread.sleep(200);
            assertEquals(1, lines(cp).size(), "an unchanged head is not exported again");
        }
    }

    @Test
    void theVerifierRejectsMalformedOrInconsistentCheckpointLines() throws Exception {
        Path journal = dir.resolve("audit.jsonl");
        try (HashChainFileAuditSink s = new HashChainFileAuditSink(journal, false)) {
            write(s, 3, "t");
        }
        String head = AuditChainVerifier.verify(journal).head();
        Path cp = dir.resolve("checkpoints.jsonl");

        Files.writeString(cp, "{\"journal\":\"audit.jsonl\",\"seq\":3,\"head\":\"md5:abc\"}\n");
        assertTrue(AuditCheckpointVerifier.verify(journal, cp).error().contains("sha256 head"));

        Files.writeString(cp, "{\"journal\":\"other.jsonl\",\"seq\":3,\"head\":\"" + head + "\"}\n");
        assertTrue(AuditCheckpointVerifier.verify(journal, cp).error().contains("belongs to journal"));

        Files.writeString(cp, "{\"journal\":\"audit.jsonl\",\"seq\":3,\"head\":\"" + head + "\"}\n"
                + "{\"journal\":\"audit.jsonl\",\"seq\":2,\"head\":\"" + head + "\"}\n");
        assertTrue(AuditCheckpointVerifier.verify(journal, cp).error().contains("goes back"));

        Files.writeString(cp, "{\"journal\":\"audit.jsonl\",\"seq\":3,\"head\":\"" + head + "\"}\n");
        AuditCheckpointVerifier.Report ok = AuditCheckpointVerifier.verify(journal, cp);
        assertTrue(ok.ok(), ok.error());
        assertTrue(AuditCheckpointVerifier.describe(ok).startsWith("OK records=3"));
    }

    @Test
    void policyArgumentsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> policy(dir.resolve("c"), 0, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> policy(dir.resolve("c"), 1, Duration.ofSeconds(-1)));
    }
}
