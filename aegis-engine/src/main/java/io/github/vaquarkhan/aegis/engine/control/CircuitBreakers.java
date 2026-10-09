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
package io.github.vaquarkhan.aegis.engine.control;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Step 7. One breaker per source: after {@code failureThreshold} consecutive backend failures the
 * breaker opens for {@code openMillis}; then exactly one probe call is let through (half-open).
 * Breaker state is per gateway replica on purpose: it protects this replica's view of the backend.
 *
 * <p>{@link #acquire} returns a {@link Permit}; only the {@code PROBE} permit can clear the
 * half-open state, so a normal call that is denied later cannot release someone else's probe slot.
 */
public final class CircuitBreakers {

    /** Outcome of {@link #acquire}. */
    public enum Permit { DENIED, NORMAL, PROBE }

    private final int failureThreshold;
    private final long openMillis;
    private final Clock clock;
    private final ConcurrentHashMap<String, Breaker> breakers = new ConcurrentHashMap<>();

    public CircuitBreakers(int failureThreshold, long openMillis, Clock clock) {
        this.failureThreshold = failureThreshold;
        this.openMillis = openMillis;
        this.clock = clock;
    }

    public boolean enabled() {
        return failureThreshold > 0;
    }

    public Permit acquire(String sourceId) {
        if (!enabled()) {
            return Permit.NORMAL;
        }
        return breakers.computeIfAbsent(sourceId, k -> new Breaker()).acquire(clock.millis(), openMillis);
    }

    public void success(String sourceId, Permit permit) {
        if (enabled() && permit != Permit.DENIED) {
            breakers.computeIfAbsent(sourceId, k -> new Breaker()).success();
        }
    }

    public void failure(String sourceId, Permit permit) {
        if (enabled() && permit != Permit.DENIED) {
            breakers.computeIfAbsent(sourceId, k -> new Breaker()).failure(clock.millis(), failureThreshold, permit == Permit.PROBE);
        }
    }

    /** Returns a permit that never reached the backend. Only a PROBE permit changes state. */
    public void release(String sourceId, Permit permit) {
        if (permit == Permit.PROBE) {
            Breaker b = breakers.get(sourceId);
            if (b != null) {
                b.releaseProbe();
            }
        }
    }

    public String state(String sourceId) {
        Breaker b = breakers.get(sourceId);
        return b == null ? "CLOSED" : b.state();
    }

    private static final class Breaker {
        private int consecutiveFailures;
        private long openedAt = -1;
        private boolean probeInFlight;

        synchronized Permit acquire(long now, long openMillis) {
            if (openedAt < 0) {
                return Permit.NORMAL;
            }
            if (now - openedAt < openMillis || probeInFlight) {
                return Permit.DENIED;
            }
            probeInFlight = true; // half-open: exactly one probe
            return Permit.PROBE;
        }

        synchronized void success() {
            consecutiveFailures = 0;
            openedAt = -1;
            probeInFlight = false;
        }

        synchronized void failure(long now, int threshold, boolean probe) {
            consecutiveFailures++;
            if (probe || consecutiveFailures >= threshold) {
                openedAt = now;
            }
            if (probe) {
                probeInFlight = false;
            }
        }

        synchronized void releaseProbe() {
            probeInFlight = false;
        }

        synchronized String state() {
            if (openedAt < 0) {
                return "CLOSED";
            }
            return probeInFlight ? "HALF_OPEN" : "OPEN";
        }
    }
}
