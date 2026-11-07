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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Verifies a {@link HashChainFileAuditSink} journal end to end. Usable as a library call or from
 * the command line: {@code java -cp aegis-engine.jar io.github.vaquarkhan.aegis.engine.audit.AuditChainVerifier <file>}.
 */
public final class AuditChainVerifier {

    private AuditChainVerifier() {}

    /** @param records number of records verified; @param error null when the chain is intact */
    public record Report(long records, String head, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    /** Verifies every byte currently present in a closed journal. */
    public static Report verify(Path file) throws IOException {
        return verify(file, Files.size(file));
    }

    /**
     * Verifies a stable byte prefix of a journal. A live sink supplies the end offset of its last
     * completed newline-delimited record, which lets verification run concurrently with an append
     * without treating the append's unfinished bytes as journal corruption.
     */
    static Report verify(Path file, long endOffsetExclusive) throws IOException {
        return verify(file, endOffsetExclusive, (seq, hash) -> { });
    }

    /** Receives each record after its hash and position were verified. */
    @FunctionalInterface
    interface RecordListener {
        void verified(long seq, String hash);
    }

    static Report verify(Path file, long endOffsetExclusive, RecordListener listener) throws IOException {
        if (endOffsetExclusive < 0) {
            throw new IllegalArgumentException("audit verification offset must not be negative");
        }
        long bytes = Math.min(endOffsetExclusive, Files.size(file));
        String prev = HashChainFileAuditSink.GENESIS;
        long n = 0;
        try (InputStream raw = Files.newInputStream(file);
             InputStream bounded = new PrefixInputStream(raw, bytes);
             BufferedReader r = new BufferedReader(new InputStreamReader(bounded, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                n++;
                Map<String, Object> rec;
                try {
                    rec = Json.parseObject(line.getBytes(StandardCharsets.UTF_8), JsonLimits.AUDIT);
                } catch (JsonException e) {
                    return new Report(n, prev, "record " + n + " is not valid JSON");
                }
                String err = verifyRecord(rec, prev);
                if (err != null) {
                    return new Report(n, prev, "record " + n + ": " + err);
                }
                Object seq = rec.get("seq");
                if (!(seq instanceof Long s) || s != n) {
                    return new Report(n, prev, "record " + n + ": sequence gap or reorder");
                }
                prev = (String) rec.get("hash");
                listener.verified(n, prev);
            }
        }
        return new Report(n, prev, null);
    }

    /**
     * Recomputes one record's hash. When {@code expectedPrev} is non-null it must equal the record's
     * {@code prev}.
     */
    static String verifyRecord(Map<String, Object> rec, String expectedPrev) {
        Object prev = rec.get("prev");
        Object hash = rec.get("hash");
        if (!(prev instanceof String p) || !(hash instanceof String h)) {
            return "missing chain fields";
        }
        if (expectedPrev != null && !expectedPrev.equals(p)) {
            return "chain broken (prev does not match previous record hash)";
        }
        Map<String, Object> body = new LinkedHashMap<>(rec);
        body.remove("hash");
        String recomputed = "sha256:" + Digests.sha256Hex(p + "\n" + Json.canonical(body));
        if (!Digests.constantTimeEquals(recomputed, h)) {
            return "record content does not match its hash";
        }
        return null;
    }

    /** Limits a verifier to a known completed prefix without copying a large audit journal. */
    private static final class PrefixInputStream extends InputStream {
        private final InputStream delegate;
        private long remaining;

        PrefixInputStream(InputStream delegate, long remaining) {
            this.delegate = delegate;
            this.remaining = remaining;
        }

        @Override
        public int read() throws IOException {
            if (remaining == 0) {
                return -1;
            }
            int value = delegate.read();
            if (value >= 0) {
                remaining--;
            } else {
                remaining = 0;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining == 0) {
                return -1;
            }
            int count = delegate.read(buffer, offset, (int) Math.min(length, remaining));
            if (count > 0) {
                remaining -= count;
            } else if (count < 0) {
                remaining = 0;
            }
            return count;
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: AuditChainVerifier <audit.jsonl>");
            System.exit(2);
        }
        Report r = verify(Path.of(args[0]));
        System.out.println((r.ok() ? "OK" : "BROKEN") + " records=" + r.records() + " head=" + r.head()
                + (r.ok() ? "" : " error=" + r.error()));
        System.exit(r.ok() ? 0 : 1);
    }
}
