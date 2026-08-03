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
package io.github.vaquarkhan.aegis.core.observability;

import io.github.vaquarkhan.aegis.core.interceptor.Decision;
import io.github.vaquarkhan.aegis.core.interceptor.Observer;
import io.github.vaquarkhan.aegis.core.spi.CallContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Bounded, hash-chained audit trail.
 *
 * <p>Each entry hashes the previous hash together with its own record, so altering or removing a
 * historical entry invalidates every hash after it and {@link #verifyChain()} returns false. This
 * makes tampering detectable without needing an external store.
 *
 * @author Viquar Khan
 */
public final class AuditLog implements Observer {

    /**
     * One chained audit record.
     *
     * @author Viquar Khan
     */
    public record Entry(String record, String hash, String prevHash) {}

    private static final int MAX = 500;

    private final Object lock = new Object();
    private final List<Entry> entries = new ArrayList<>();
    private String prev = "";

    public void append(String caller, String tool, String outcome) {
        String trace = Trace.get();
        if (trace == null || trace.isBlank()) {
            trace = "-";
        }
        String record = Instant.now() + " | trace=" + trace + " | " + caller + " | " + tool + " | " + outcome;
        synchronized (lock) {
            String hash = sha256(prev + " | " + record);
            entries.add(new Entry(record, hash, prev));
            if (entries.size() > MAX) {
                entries.remove(0);
            }
            prev = hash;
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

    /**
     * Recomputes the chain from the oldest retained entry.
     *
     * <p>Note that the retained window is bounded, so the first entry's {@code prevHash} is only
     * empty until eviction begins. Verification anchors on the oldest retained entry.
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

    /** Test hook: replaces a record without recomputing hashes, simulating tampering. */
    void tamperForTest(int index, String replacementRecord) {
        synchronized (lock) {
            Entry old = entries.get(index);
            entries.set(index, new Entry(replacementRecord, old.hash(), old.prevHash()));
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
