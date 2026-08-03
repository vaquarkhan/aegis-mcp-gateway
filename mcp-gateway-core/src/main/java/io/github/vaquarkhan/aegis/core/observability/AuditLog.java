package io.github.vaquarkhan.aegis.core.observability;

import io.github.vaquarkhan.aegis.core.interceptor.Decision;
import io.github.vaquarkhan.aegis.core.interceptor.Observer;
import io.github.vaquarkhan.aegis.core.spi.CallContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounded, hash-chained audit trail with optional durable append-only file.
 *
 * <p>Each entry hashes the previous hash together with its own record, so altering or removing a
 * historical entry invalidates every hash after it and {@link #verifyChain()} returns false.
 * When {@code MCP_GW_AUDIT_FILE} is set, every entry is also appended as
 * {@code prevHash|hash|record} so operators can verify after process restart.
 *
 * @author Viquar Khan
 */
public final class AuditLog implements Observer {

    private static final Logger LOG = LoggerFactory.getLogger(AuditLog.class);

    /**
     * One chained audit record.
     *
     * @author Viquar Khan
     */
    public record Entry(String record, String hash, String prevHash) {}

    private static final int MAX = 500;

    private final Object lock = new Object();
    private final List<Entry> entries = new ArrayList<>();
    private final Path durableFile;
    private String prev = "";

    public AuditLog() {
        this(null);
    }

    public AuditLog(Path durableFile) {
        this.durableFile = durableFile;
        if (durableFile != null) {
            try {
                Path parent = durableFile.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                if (!Files.exists(durableFile)) {
                    Files.createFile(durableFile);
                }
            } catch (IOException e) {
                throw new IllegalArgumentException(
                        "MCP_GW_AUDIT_FILE unusable: " + durableFile + " (" + e.getMessage() + ")", e);
            }
        }
    }

    public void append(String caller, String tool, String outcome) {
        String trace = Trace.get();
        if (trace == null || trace.isBlank()) {
            trace = "-";
        }
        String record = Instant.now() + " | trace=" + trace + " | " + caller + " | " + tool + " | " + outcome;
        synchronized (lock) {
            String hash = sha256(prev + " | " + record);
            Entry entry = new Entry(record, hash, prev);
            entries.add(entry);
            if (entries.size() > MAX) {
                // Keep the in-memory window bounded; durable file retains the full chain.
                entries.remove(0);
            }
            prev = hash;
            appendDurable(entry);
        }
    }

    @Override
    public void onOutcome(CallContext ctx, Decision decision, long elapsedMillis) {
        String caller = ctx.caller() == null ? "-" : ctx.caller().callerId();
        append(caller, ctx.toolName(), decision.auditOutcome());
    }

    public List<String> recent() {
        synchronized (lock) {
            List<String> out = new ArrayList<>(entries.size());
            for (Entry e : entries) {
                out.add(e.hash().substring(0, 12) + "  " + e.record());
            }
            return out;
        }
    }

    public int size() {
        synchronized (lock) {
            return entries.size();
        }
    }

    public Path durableFile() {
        return durableFile;
    }

    /**
     * Recomputes the in-memory chain from the oldest retained entry.
     *
     * <p>The retained window is bounded, so verification anchors on the oldest retained entry.
     * Prefer {@link #verifyDurableChain()} when a durable file is configured.
     */
    public boolean verifyChain() {
        synchronized (lock) {
            if (entries.isEmpty()) {
                return true;
            }
            String expectedPrev = entries.get(0).prevHash();
            for (Entry e : entries) {
                if (!expectedPrev.equals(e.prevHash())) {
                    return false;
                }
                if (!sha256(e.prevHash() + " | " + e.record()).equals(e.hash())) {
                    return false;
                }
                expectedPrev = e.hash();
            }
            return true;
        }
    }

    /** Verifies the append-only durable file end to end when configured. */
    public boolean verifyDurableChain() {
        if (durableFile == null) {
            return verifyChain();
        }
        synchronized (lock) {
            try {
                List<String> lines = Files.readAllLines(durableFile, StandardCharsets.UTF_8);
                String expectedPrev = "";
                boolean first = true;
                for (String line : lines) {
                    if (line == null || line.isBlank()) {
                        continue;
                    }
                    String[] parts = line.split("\\|", 3);
                    if (parts.length != 3) {
                        return false;
                    }
                    String prevHash = parts[0];
                    String hash = parts[1];
                    String record = parts[2];
                    if (first) {
                        expectedPrev = prevHash;
                        first = false;
                    }
                    if (!expectedPrev.equals(prevHash)) {
                        return false;
                    }
                    if (!sha256(prevHash + " | " + record).equals(hash)) {
                        return false;
                    }
                    expectedPrev = hash;
                }
                return true;
            } catch (IOException e) {
                LOG.warn("durable audit verify failed: {}", e.getMessage());
                return false;
            }
        }
    }

    /** Test hook: replaces a record without recomputing hashes, simulating tampering. */
    void tamperForTest(int index, String replacementRecord) {
        synchronized (lock) {
            Entry old = entries.get(index);
            entries.set(index, new Entry(replacementRecord, old.hash(), old.prevHash()));
        }
    }

    private void appendDurable(Entry entry) {
        if (durableFile == null) {
            return;
        }
        String line = entry.prevHash() + "|" + entry.hash() + "|" + entry.record() + System.lineSeparator();
        try {
            Files.writeString(durableFile, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.error("failed to append durable audit entry: {}", e.getMessage());
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
