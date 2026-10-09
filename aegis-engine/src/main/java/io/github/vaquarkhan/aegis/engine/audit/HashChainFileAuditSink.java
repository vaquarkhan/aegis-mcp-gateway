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
import io.github.vaquarkhan.aegis.engine.json.JsonLimits;
import io.github.vaquarkhan.aegis.engine.model.Digests;
import io.github.vaquarkhan.aegis.engine.spi.AuditSink;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Tamper-evident audit journal: one canonical JSON record per line, each carrying the SHA-256 of
 * the previous record ({@code prev}) and its own ({@code hash}). Editing, deleting or reordering
 * any line breaks the chain, which {@link AuditChainVerifier} detects.
 *
 * <p>Restart recovery (a 0.1 gap): on open, the last line is read and verified, and the chain
 * continues from its hash. A torn last line (crash mid-write) is NOT silently repaired: opening
 * fails, so the gateway does not start on a journal it cannot vouch for. The operator inspects it
 * and moves it aside.
 *
 * <p>Scope: a hash chain proves integrity relative to the last head hash retained elsewhere. With an
 * {@link AuditCheckpointPolicy} the sink exports that head itself: it appends {@code {seq, head}} lines
 * to a separate checkpoint file after every N records, on an interval, at open and at close. On open it
 * refuses a journal that is shorter than, or differs at, the last exported head (truncated or replaced),
 * and {@link AuditCheckpointVerifier} checks every exported head offline. A checkpoint that cannot be
 * written is retried before the next record; if the retry fails the record is refused, so the engine
 * denies the call with {@code AUDIT_UNAVAILABLE} rather than running unanchored.
 */
public final class HashChainFileAuditSink implements AuditSink {

    private static final System.Logger LOG = System.getLogger(HashChainFileAuditSink.class.getName());
    /** A checkpoint line is a few hundred bytes; anything longer at the tail is not a checkpoint. */
    private static final int MAX_CHECKPOINT_LINE = 4096;

    public static final String GENESIS = "sha256:" + "0".repeat(64);
    /** A record is bounded before write, so a completed journal always has a restart-safe tail. */
    private static final int MAX_AUDIT_RECORD_BYTES = JsonLimits.AUDIT.maxBytes();
    private static final int MAX_TAIL_SCAN = MAX_AUDIT_RECORD_BYTES + 1;
    /** A byte offset no record will ever occupy; the writer lock sits here so readers are never blocked. */
    private static final long WRITER_LOCK_OFFSET = Long.MAX_VALUE - 1;

    private final Path file;
    private final boolean fsync;
    private final FileChannel channel;
    private final FileLock lock;
    /** End offset of the last completed record. Readers verify this stable prefix without blocking append. */
    private volatile long committedBytes;
    private String prev;
    private long seq;

    private final AuditCheckpointPolicy checkpoints;
    private FileChannel checkpointChannel;
    private FileLock checkpointLock;
    private long checkpointedSeq;
    private IOException checkpointFailure;
    private ScheduledExecutorService checkpointTimer;
    private boolean closed;

    public HashChainFileAuditSink(Path file, boolean fsync) throws IOException {
        this(file, fsync, null);
    }

    /**
     * @param checkpoints head export policy, or {@code null} for none
     */
    public HashChainFileAuditSink(Path file, boolean fsync, AuditCheckpointPolicy checkpoints) throws IOException {
        this.checkpoints = checkpoints;
        this.file = file;
        this.fsync = fsync;
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        FileChannel opened = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        FileLock acquired;
        try {
            // Lock a single sentinel byte far beyond EOF, not the data region. This gives mutual exclusion
            // between writers while leaving the record bytes readable, so the sink's own verifier (and any
            // reader) is never blocked, including on Windows where locks are mandatory.
            acquired = opened.tryLock(WRITER_LOCK_OFFSET, 1, false);
        } catch (OverlappingFileLockException sameJvm) {
            // A second sink in this JVM (for example two Knox topologies naming one file) would append
            // with its own prev/seq and break the chain, so it is refused like a second process.
            acquired = null;
        } catch (IOException | RuntimeException e) {
            opened.close();
            throw e;
        }
        if (acquired == null) {
            opened.close();
            throw new IOException("audit journal " + file.toAbsolutePath()
                    + " is already open for writing; two gateways must not share one journal file");
        }
        // The tail is read only while holding the writer lock, so no other writer can append between
        // reading the last hash and continuing the chain from it.
        String[] tail;
        try {
            tail = readTail(file);
        } catch (IOException | RuntimeException e) {
            try {
                acquired.release();
            } finally {
                opened.close();
            }
            throw e;
        }
        this.prev = tail[0];
        this.seq = Long.parseLong(tail[1]);
        this.channel = opened;
        this.committedBytes = opened.size();
        this.lock = acquired;
        if (checkpoints != null) {
            try {
                openCheckpoints();
            } catch (IOException | RuntimeException e) {
                // Never export a head from a journal that failed its checkpoint check.
                shutdown(false);
                throw e;
            }
        }
    }

    /** Opens the checkpoint file, checks the journal against its last exported head, and anchors the current head. */
    private void openCheckpoints() throws IOException {
        Path cp = checkpoints.file();
        if (cp.toAbsolutePath().normalize().equals(file.toAbsolutePath().normalize())) {
            throw new IOException("audit checkpoint file must not be the journal itself: " + cp.toAbsolutePath());
        }
        Path parent = cp.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        FileChannel opened = FileChannel.open(cp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        FileLock acquired;
        try {
            acquired = opened.tryLock(WRITER_LOCK_OFFSET, 1, false);
        } catch (OverlappingFileLockException sameJvm) {
            acquired = null;
        } catch (IOException | RuntimeException e) {
            opened.close();
            throw e;
        }
        if (acquired == null) {
            opened.close();
            throw new IOException("audit checkpoint file " + cp.toAbsolutePath()
                    + " is already open for writing; each journal needs its own checkpoint file");
        }
        checkpointChannel = opened;
        checkpointLock = acquired;
        Map<String, Object> last = readLastCheckpoint(cp);
        if (last != null) {
            Object journal = last.get("journal");
            if (!fileName(file).equals(journal)) {
                throw new IOException("audit checkpoint file " + cp.toAbsolutePath() + " belongs to journal '" + journal
                        + "', not '" + fileName(file) + "'");
            }
            long lastSeq = (Long) last.get("seq");
            String lastHead = (String) last.get("head");
            if (lastSeq > seq) {
                throw new IOException("audit journal " + file.toAbsolutePath() + " holds " + seq
                        + " records but record " + lastSeq + " (" + lastHead + ") was exported to " + cp.toAbsolutePath()
                        + "; the journal was truncated or replaced");
            }
            if (lastSeq == seq && !Digests.constantTimeEquals(lastHead, prev)) {
                throw new IOException("audit journal " + file.toAbsolutePath() + " record " + seq
                        + " does not match the exported head " + lastHead + "; the journal was rewritten");
            }
            checkpointedSeq = lastSeq;
        }
        if (seq > checkpointedSeq) {
            writeCheckpoint("open");
        }
        if (!checkpoints.every().isZero()) {
            long periodMs = Math.max(1, checkpoints.every().toMillis());
            checkpointTimer = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread t = new Thread(runnable, "aegis-audit-checkpoint");
                t.setDaemon(true);
                return t;
            });
            checkpointTimer.scheduleAtFixedRate(this::intervalCheckpoint, periodMs, periodMs, TimeUnit.MILLISECONDS);
        }
    }

    private synchronized void intervalCheckpoint() {
        if (closed || seq <= checkpointedSeq) {
            return;
        }
        try {
            writeCheckpoint("interval");
        } catch (IOException e) {
            checkpointFailure = e;
            LOG.log(System.Logger.Level.ERROR, "audit checkpoint export to " + checkpoints.file() + " failed", e);
        }
    }

    /** Appends {@code {ts, journal, seq, head, reason}} for the current head and forces it to storage. */
    private void writeCheckpoint(String reason) throws IOException {
        Map<String, Object> cp = new LinkedHashMap<>();
        cp.put("ts", checkpoints.clock().instant().toString());
        cp.put("journal", fileName(file));
        cp.put("seq", seq);
        cp.put("head", prev);
        cp.put("reason", reason);
        ByteBuffer buf = ByteBuffer.wrap((Json.canonical(cp) + "\n").getBytes(StandardCharsets.UTF_8));
        while (buf.hasRemaining()) {
            checkpointChannel.write(buf);
        }
        checkpointChannel.force(false);
        checkpointedSeq = seq;
        checkpointFailure = null;
    }

    @Override
    public synchronized void append(Map<String, Object> record) throws IOException {
        if (closed) {
            throw new IOException("audit journal " + file.toAbsolutePath() + " is closed");
        }
        if (checkpointFailure != null) {
            // The previous export failed: retry it before adding anything that would also go unanchored.
            try {
                writeCheckpoint("retry");
            } catch (IOException e) {
                throw new IOException("audit checkpoint export to " + checkpoints.file().toAbsolutePath()
                        + " is failing; no new record is written until it succeeds", e);
            }
        }
        Map<String, Object> r = new LinkedHashMap<>(record);
        r.remove("hash");
        r.put("seq", seq + 1);
        r.put("prev", prev);
        String body = Json.canonical(r);
        String hash = "sha256:" + Digests.sha256Hex(prev + "\n" + body);
        r.put("hash", hash);
        byte[] line = (Json.canonical(r) + "\n").getBytes(StandardCharsets.UTF_8);
        if (line.length - 1 > MAX_AUDIT_RECORD_BYTES) {
            throw new IOException("audit record exceeds " + MAX_AUDIT_RECORD_BYTES + " bytes");
        }
        ByteBuffer buf = ByteBuffer.wrap(line);
        while (buf.hasRemaining()) {
            channel.write(buf);
        }
        if (fsync) {
            channel.force(false);
        }
        committedBytes = channel.position();
        prev = hash;
        seq++;
        if (checkpoints != null && seq - checkpointedSeq >= checkpoints.everyRecords()) {
            try {
                writeCheckpoint("records");
            } catch (IOException e) {
                // This record is already durable; the next append retries the export first.
                checkpointFailure = e;
                LOG.log(System.Logger.Level.ERROR, "audit checkpoint export to " + checkpoints.file() + " failed", e);
            }
        }
    }

    public synchronized String head() {
        return prev;
    }

    /**
     * Verifies the last fully committed prefix without taking the append monitor. An append in
     * progress is beyond {@code committedBytes}, so verification neither observes a torn record
     * nor stalls governed calls behind a potentially long scan.
     */
    public AuditChainVerifier.Report verify() throws IOException {
        return AuditChainVerifier.verify(file, committedBytes);
    }

    public Path file() {
        return file;
    }

    @Override
    public synchronized void close() {
        shutdown(true);
    }

    private synchronized void shutdown(boolean finalCheckpoint) {
        if (closed) {
            return;
        }
        closed = true;
        if (checkpointTimer != null) {
            checkpointTimer.shutdownNow();
        }
        if (checkpointChannel != null) {
            if (finalCheckpoint && checkpointChannel.isOpen() && seq > checkpointedSeq) {
                try {
                    writeCheckpoint("close");
                } catch (IOException e) {
                    LOG.log(System.Logger.Level.ERROR, "final audit checkpoint export to " + checkpoints.file() + " failed", e);
                }
            }
            try {
                if (checkpointLock != null && checkpointLock.isValid()) {
                    checkpointLock.release();
                }
            } catch (IOException ignored) {
                // releasing a lock on a channel that is about to close is not actionable
            }
            try {
                checkpointChannel.close();
            } catch (IOException ignored) {
                // closing a checkpoint channel that failed is not actionable
            }
        }
        if (lock != null && lock.isValid()) {
            try {
                lock.release();
            } catch (IOException ignored) {
                // releasing a lock on a channel that is about to close is not actionable
            }
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // closing a journal channel that failed is not actionable
        }
    }

    static String fileName(Path p) {
        Path name = p.toAbsolutePath().normalize().getFileName();
        return name == null ? p.toString() : name.toString();
    }

    /** @return the last checkpoint line, or null for a new or empty checkpoint file */
    static Map<String, Object> readLastCheckpoint(Path cp) throws IOException {
        if (!Files.exists(cp) || Files.size(cp) == 0) {
            return null;
        }
        try (RandomAccessFile raf = new RandomAccessFile(cp.toFile(), "r")) {
            long len = raf.length();
            raf.seek(len - 1);
            if (raf.read() != '\n') {
                throw new IOException("audit checkpoint file " + cp + " ends with a partial line; inspect it before continuing");
            }
            long start = Math.max(0, len - MAX_CHECKPOINT_LINE - 1);
            byte[] buf = new byte[(int) (len - start)];
            raf.seek(start);
            raf.readFully(buf);
            int end = buf.length - 1;
            int i = end - 1;
            while (i >= 0 && buf[i] != '\n') {
                i--;
            }
            if (i < 0 && start > 0) {
                throw new IOException("audit checkpoint file " + cp + " last line exceeds " + MAX_CHECKPOINT_LINE + " bytes");
            }
            return AuditCheckpointVerifier.parseCheckpoint(new String(buf, i + 1, end - i - 1, StandardCharsets.UTF_8), cp);
        }
    }

    /** @return {prevHash, seq} of the verified last record, or genesis for a new/empty journal */
    private static String[] readTail(Path file) throws IOException {
        if (!Files.exists(file) || Files.size(file) == 0) {
            return new String[] {GENESIS, "0"};
        }
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long len = raf.length();
            raf.seek(len - 1);
            if (raf.read() != '\n') {
                throw new IOException("audit journal " + file + " ends with a partial record; refusing to continue the chain");
            }
            long start = Math.max(0, len - MAX_TAIL_SCAN);
            byte[] buf = new byte[(int) (len - start)];
            raf.seek(start);
            raf.readFully(buf);
            int end = buf.length - 1; // index of final '\n'
            int i = end - 1;
            while (i >= 0 && buf[i] != '\n') {
                i--;
            }
            if (i < 0 && start > 0) {
                throw new IOException("audit journal " + file + " last record exceeds " + MAX_TAIL_SCAN + " bytes");
            }
            String last = new String(buf, i + 1, end - i - 1, StandardCharsets.UTF_8);
            Map<String, Object> rec = Json.parseObject(last.getBytes(StandardCharsets.UTF_8), JsonLimits.AUDIT);
            String err = AuditChainVerifier.verifyRecord(rec, null);
            if (err != null) {
                throw new IOException("audit journal " + file + " last record fails verification: " + err);
            }
            return new String[] {(String) rec.get("hash"), String.valueOf(rec.get("seq"))};
        }
    }
}
