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
package io.github.vaquarkhan.aegis.core.governance;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Step 7. Per-tool circuit breaker.
 *
 * <p>After {@code failureThreshold} consecutive failures the breaker opens. Once
 * {@code resetMillis} has elapsed it moves to half-open and admits exactly one probe: success
 * closes it, failure reopens it. Only {@code TIMEOUT} and {@code BACKEND_ERROR} are counted as
 * failures, so caller side input errors cannot open a tool for everybody.
 *
 * @author Viquar Khan
 */
public final class CircuitBreaker {

    private enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final long resetMillis;
    private final ConcurrentHashMap<String, ToolState> states = new ConcurrentHashMap<>();

    public CircuitBreaker(int failureThreshold, long resetMillis) {
        this.failureThreshold = failureThreshold;
        this.resetMillis = resetMillis;
    }

    public boolean isOpen(String tool) {
        ToolState s = states.computeIfAbsent(tool, k -> new ToolState());
        synchronized (s) {
            if (s.state == State.OPEN) {
                if (System.currentTimeMillis() - s.openedAt >= resetMillis) {
                    // This call becomes the single probe, so claim the slot as we hand it out.
                    s.state = State.HALF_OPEN;
                    s.halfOpenInFlight.set(true);
                    return false;
                }
                return true;
            }
            if (s.state == State.HALF_OPEN) {
                return !s.halfOpenInFlight.compareAndSet(false, true);
            }
            return false;
        }
    }

    public void recordSuccess(String tool) {
        ToolState s = states.computeIfAbsent(tool, k -> new ToolState());
        synchronized (s) {
            s.failures = 0;
            s.state = State.CLOSED;
            s.halfOpenInFlight.set(false);
        }
    }

    public void recordFailure(String tool) {
        ToolState s = states.computeIfAbsent(tool, k -> new ToolState());
        synchronized (s) {
            s.failures++;
            if (s.state == State.HALF_OPEN || s.failures >= failureThreshold) {
                s.state = State.OPEN;
                s.openedAt = System.currentTimeMillis();
            }
            s.halfOpenInFlight.set(false);
        }
    }

    public int failureThreshold() {
        return failureThreshold;
    }

    public long resetMillis() {
        return resetMillis;
    }

    private static final class ToolState {
        State state = State.CLOSED;
        int failures;
        long openedAt;
        final AtomicBoolean halfOpenInFlight = new AtomicBoolean(false);
    }
}
