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
package io.github.vaquarkhan.aegis.engine.catalog;

import io.github.vaquarkhan.aegis.engine.spi.HighWaterStateStore;
import io.github.vaquarkhan.aegis.engine.spi.StateStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Rollback protection for signed operator documents (server catalog, catalog manifest, break-glass
 * order). Each document may carry a signed {@code "serial"}, a whole number the signer raises with
 * every new version. The gateway keeps the highest serial it has accepted per document kind in its
 * {@link StateStore}, and refuses a document whose serial is lower, or that has no serial once a
 * numbered one was accepted. An older file signed by the same key therefore no longer starts the
 * gateway.
 *
 * <p>The record lives as long as the state store does: with {@code derby-local} or {@code postgres}
 * state it survives restarts (and with {@code postgres} it is shared by every replica); with the
 * default in-memory state it lasts for one process only. The state store advances one high-water
 * key atomically, so a candidate that arrives after a higher accepted serial is refused across
 * replicas.
 */
public final class SignedSerial {

    /** Largest serial: the largest integer every JSON implementation represents exactly. */
    public static final long MAX = 9_007_199_254_740_991L;

    static final String PREFIX = "aegis:signed-serial:";
    private SignedSerial() {}

    /** Parses an optional {@code "serial"} value; 0 when absent. */
    static long parse(String at, Object v) throws IOException {
        if (v == null) {
            return 0;
        }
        if (!(v instanceof Long n) || n < 1 || n > MAX) {
            throw new IOException(at + ": \"serial\" must be a whole number from 1 to " + MAX);
        }
        return n;
    }

    /**
     * Checks {@code serial} against the highest serial this gateway accepted for {@code kind}, then
     * records it. Fails closed: an unreadable record refuses the document.
     *
     * @param kind        document kind, for example {@link SignedServerCatalog#KIND}
     * @param gatewayName the configured gateway name; each gateway keeps its own record
     * @param serial      the document's serial, 0 when it has none
     * @param what        the document as named in error messages
     * @return the current highest accepted serial (0 when none)
     * @throws IOException when the document is older than one already accepted
     */
    public static long accept(StateStore store, String kind, String gatewayName, long serial, String what) throws IOException {
        if (serial < 0 || serial > MAX) {
            throw new IOException(what + ": serial out of range");
        }
        if (!(store instanceof HighWaterStateStore highWater)) {
            if (serial == 0) {
                return 0;
            }
            throw new IOException(what + " carries serial " + serial + ", but the configured state provider does not"
                    + " support the atomic high-water mark required for rollback protection");
        }
        String key = PREFIX + kind + ":" + HexFormat.of().formatHex(gatewayName.getBytes(StandardCharsets.UTF_8));
        final long highest;
        try {
            highest = highWater.updateHighWaterMark(key, serial);
        } catch (IllegalStateException e) {
            throw new IOException("the state store holds an unreadable serial for " + what + "; refusing to start", e);
        }
        if (highest > serial) {
            throw new IOException(what + (serial == 0 ? " has no serial" : " has serial " + serial)
                    + ", but this gateway already accepted serial " + highest
                    + "; sign a new version with a higher serial (an older signed file is not accepted again)");
        }
        return highest;
    }
}
