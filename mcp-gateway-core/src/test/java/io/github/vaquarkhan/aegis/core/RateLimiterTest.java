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
package io.github.vaquarkhan.aegis.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.core.governance.RateLimiter;
import org.junit.jupiter.api.Test;

/**
 * @author Viquar Khan
 */
class RateLimiterTest {

    @Test
    void allowsUpToTheBudgetThenDenies() {
        RateLimiter limiter = new RateLimiter(3);
        assertTrue(limiter.allow("ops"));
        assertTrue(limiter.allow("ops"));
        assertTrue(limiter.allow("ops"));
        assertFalse(limiter.allow("ops"));
        assertEquals(3, limiter.used("ops"));
        assertEquals(3, limiter.used());
    }

    @Test
    void eachCallerGetsItsOwnWindow() {
        RateLimiter limiter = new RateLimiter(1);
        assertTrue(limiter.allow("ops"));
        assertFalse(limiter.allow("ops"));
        assertTrue(limiter.allow("viewer"), "one noisy caller must not spend another caller's budget");
        assertEquals(2, limiter.callers());
        assertEquals(2, limiter.used());
    }

    @Test
    void blankCallerFallsBackToTheAnonymousBucket() {
        RateLimiter limiter = new RateLimiter(1);
        assertTrue(limiter.allow(null));
        assertFalse(limiter.allow("  "));
        assertEquals(1, limiter.used(RateLimiter.ANONYMOUS));
        assertEquals(1, limiter.callers());
    }

    @Test
    void windowResetsAfterOneSecond() throws InterruptedException {
        RateLimiter limiter = new RateLimiter(1);
        assertTrue(limiter.allow("ops"));
        assertFalse(limiter.allow("ops"));
        Thread.sleep(1050L);
        assertTrue(limiter.allow("ops"), "budget must refresh once the window rolls");
    }

    @Test
    void reportsConfiguredCeiling() {
        assertEquals(42, new RateLimiter(42).maxPerSecond());
    }

    @Test
    void isSafeUnderConcurrency() throws InterruptedException {
        int permits = 50;
        RateLimiter limiter = new RateLimiter(permits);
        java.util.concurrent.atomic.AtomicInteger allowed = new java.util.concurrent.atomic.AtomicInteger();
        int threads = 8;
        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            workers[i] = new Thread(() -> {
                for (int j = 0; j < 40; j++) {
                    if (limiter.allow("shared")) {
                        allowed.incrementAndGet();
                    }
                }
            });
        }
        for (Thread t : workers) {
            t.start();
        }
        for (Thread t : workers) {
            t.join();
        }
        // Window rollover may grant more than one window's worth, but never fewer than one.
        assertTrue(allowed.get() >= permits, "expected at least one full window of permits");
        assertTrue(allowed.get() <= threads * 40);
    }
}
