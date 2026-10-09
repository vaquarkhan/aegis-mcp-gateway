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

import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.json.JsonException;
import io.github.vaquarkhan.aegis.engine.json.JsonLimits;
import io.github.vaquarkhan.aegis.engine.model.Digests;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Proves a journal still contains every head that {@link HashChainFileAuditSink} exported to a
 * checkpoint file. The chain alone shows that the records agree with each other; the checkpoints,
 * kept on separate storage, show that the journal was not truncated or regenerated from some point on.
 */
public final class AuditCheckpointVerifier {

    private static final Pattern HEAD = Pattern.compile("sha256:[0-9a-f]{64}");

    private AuditCheckpointVerifier() {}

    /**
     * @param records        records verified in the journal
     * @param head           journal head after the verified records
     * @param checkpoints    checkpoint lines read
     * @param checkpointSeq  highest exported record number (0 when none)
     * @param error          null when the chain is intact and every checkpoint matches
     */
    public record Report(long records, String head, long checkpoints, long checkpointSeq, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    public static Report verify(Path journal, Path checkpointFile) throws IOException {
        TreeMap<Long, String> expected = new TreeMap<>();
        long lines = 0;
        String journalName = HashChainFileAuditSink.fileName(journal);
        try (BufferedReader r = Files.newBufferedReader(checkpointFile, StandardCharsets.UTF_8)) {
            String line;
            long lastSeq = 0;
            while ((line = r.readLine()) != null) {
                lines++;
                Map<String, Object> cp;
                try {
                    cp = parseCheckpoint(line, checkpointFile);
                } catch (IOException e) {
                    return new Report(0, null, lines, lastSeq, "checkpoint " + lines + ": " + e.getMessage());
                }
                if (!journalName.equals(cp.get("journal"))) {
                    return new Report(0, null, lines, lastSeq, "checkpoint " + lines + " belongs to journal '"
                            + cp.get("journal") + "', not '" + journalName + "'");
                }
                long seq = (Long) cp.get("seq");
                String head = (String) cp.get("head");
                if (seq < lastSeq) {
                    return new Report(0, null, lines, lastSeq, "checkpoint " + lines + " goes back from record "
                            + lastSeq + " to " + seq);
                }
                String earlier = expected.putIfAbsent(seq, head);
                if (earlier != null && !earlier.equals(head)) {
                    return new Report(0, null, lines, lastSeq, "checkpoint " + lines + " gives record " + seq
                            + " two different heads");
                }
                lastSeq = seq;
            }
        }
        long highest = expected.isEmpty() ? 0 : expected.lastKey();
        String[] mismatch = new String[1];
        AuditChainVerifier.Report chain = AuditChainVerifier.verify(journal, Files.size(journal), (seq, hash) -> {
            String want = expected.get(seq);
            if (mismatch[0] == null && want != null && !Digests.constantTimeEquals(want, hash)) {
                mismatch[0] = "record " + seq + " is " + hash + " but " + want + " was exported; the journal was rewritten";
            }
        });
        if (!chain.ok()) {
            return new Report(chain.records(), chain.head(), lines, highest, chain.error());
        }
        if (mismatch[0] != null) {
            return new Report(chain.records(), chain.head(), lines, highest, mismatch[0]);
        }
        if (highest > chain.records()) {
            return new Report(chain.records(), chain.head(), lines, highest, "the journal ends at record "
                    + chain.records() + " but record " + highest + " was exported; the journal was truncated or replaced");
        }
        return new Report(chain.records(), chain.head(), lines, highest, null);
    }

    /** Parses and type-checks one checkpoint line. */
    static Map<String, Object> parseCheckpoint(String line, Path file) throws IOException {
        Map<String, Object> cp;
        try {
            cp = Json.parseObject(line.getBytes(StandardCharsets.UTF_8), JsonLimits.AUDIT);
        } catch (JsonException e) {
            throw new IOException("checkpoint line in " + file + " is not valid JSON");
        }
        if (!(cp.get("journal") instanceof String)
                || !(cp.get("seq") instanceof Long seq) || seq < 1
                || !(cp.get("head") instanceof String head) || !HEAD.matcher(head).matches()) {
            throw new IOException("checkpoint line in " + file + " needs journal, seq >= 1 and a sha256 head");
        }
        return cp;
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: AuditCheckpointVerifier <audit.jsonl> <checkpoints.jsonl>");
            System.exit(2);
        }
        Report r = verify(Path.of(args[0]), Path.of(args[1]));
        System.out.println(describe(r));
        System.exit(r.ok() ? 0 : 1);
    }

    public static String describe(Report r) {
        return (r.ok() ? "OK" : "BROKEN") + " records=" + r.records() + " head=" + r.head()
                + " checkpoints=" + r.checkpoints() + " checkpointedRecord=" + r.checkpointSeq()
                + (r.ok() ? "" : " error=" + r.error());
    }
}
