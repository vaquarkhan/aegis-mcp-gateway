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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Step 6. Fixed window per-second rate limiter, one window per caller.
 *
 * <p>The budget is per caller so a single noisy agent cannot spend the ceiling that every other
 * caller shares. A fixed window is deliberate: the gateway wants a hard, easily explained limit
 * that an operator can reason about from a single number, not a smoothed rate that can burst.
 *
 * @author Viquar Khan
 */
public final class RateLimiter {

    /** Bucket key used when a call arrives without a resolved caller. */
    public static final String ANONYMOUS = "-";

    private final int maxPerSecond;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimiter(int maxPerSecond) {
        this.maxPerSecond = maxPerSecond;
    }

    /** Consumes one permit from the caller's window. */
    public boolean allow(String callerId) {
        return windows.computeIfAbsent(key(callerId), k -> new Window()).tryAcquire(maxPerSecond);
    }

    /**
     * Consumes one permit from the shared anonymous window.
     *
     * @deprecated per LLD section 5 the limiter is keyed by caller; use {@link #allow(String)}.
     */
    @Deprecated
    public boolean allow() {
        return allow(ANONYMOUS);
    }

    public int maxPerSecond() {
        return maxPerSecond;
    }

    /** Calls consumed across every caller in their current windows. */
    public int used() {
        int total = 0;
        for (Window w : windows.values()) {
            total += w.used();
        }
        return total;
    }

    /** Calls this caller has consumed in its current window. */
    public int used(String callerId) {
        Window w = windows.get(key(callerId));
        return w == null ? 0 : w.used();
    }

    /** Number of callers currently tracked. */
    public int callers() {
        return windows.size();
    }

    private static String key(String callerId) {
        return (callerId == null || callerId.isBlank()) ? ANONYMOUS : callerId;
    }

    /** One fixed second window. */
    private static final class Window {

        private long startMillis = System.currentTimeMillis();
        private int count;

        synchronized boolean tryAcquire(int maxPerSecond) {
            long now = System.currentTimeMillis();
            if (now - startMillis >= 1000L) {
                startMillis = now;
                count = 0;
            }
            if (count >= maxPerSecond) {
                return false;
            }
            count++;
            return true;
        }

        synchronized int used() {
            return count;
        }
    }
}
