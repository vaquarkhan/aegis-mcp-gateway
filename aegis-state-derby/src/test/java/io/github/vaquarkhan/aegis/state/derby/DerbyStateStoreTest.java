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
package io.github.vaquarkhan.aegis.state.derby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.config.BuildContext;
import io.github.vaquarkhan.aegis.config.ConfigException;
import io.github.vaquarkhan.aegis.config.ConfigNode;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Contract tests for the durable single-replica StateStore provider. */
class DerbyStateStoreTest {

    private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");

    @TempDir
    Path dir;

    private DerbyStateStore open() throws Exception {
        return new DerbyStateStore(dir.resolve("aegis-state"), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void factoryCreatesOnlyLocalPathsUnderTheConfigurationDirectory() throws Exception {
        DerbyStateStoreFactory factory = new DerbyStateStoreFactory();
        assertEquals("derby-local", factory.type());
        try (DerbyStateStore store = (DerbyStateStore) factory.create(
                new ConfigNode("state.settings", Map.of("path", "factory-state")), BuildContext.defaults(dir))) {
            assertTrue(store.putIfAbsent("nonce", "v", NOW.plusSeconds(60)));
        }
        ConfigException e = assertThrows(ConfigException.class, () -> factory.create(
                new ConfigNode("state.settings", Map.of("path", "../outside")), BuildContext.defaults(dir)));
        assertTrue(e.getMessage().contains("stay under"), e.getMessage());
    }

    @Test
    void stateSurvivesRestartAndExpiredStateDoesNot() throws Exception {
        try (DerbyStateStore store = open()) {
            store.put("approval/request-1", "pending", NOW.plusSeconds(60));
            store.put("approval/expired", "old", NOW.minusSeconds(1));
            assertEquals("pending", store.get("approval/request-1").orElseThrow());
            assertTrue(store.get("approval/expired").isEmpty());
        }
        try (DerbyStateStore reopened = open()) {
            assertEquals("pending", reopened.get("approval/request-1").orElseThrow());
            assertTrue(reopened.get("approval/expired").isEmpty());
            assertEquals(Map.of("approval/request-1", "pending"), reopened.scan("approval/", 10));
        }
    }

    @Test
    void nonceHasExactlyOneWinnerUnderConcurrency() throws Exception {
        try (DerbyStateStore store = open()) {
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger winners = new AtomicInteger();
            Thread[] threads = new Thread[16];
            for (int i = 0; i < threads.length; i++) {
                threads[i] = new Thread(() -> {
                    try {
                        start.await();
                        if (store.putIfAbsent("receipt/r1", "one", NOW.plusSeconds(60))) {
                            winners.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                threads[i].start();
            }
            start.countDown();
            for (Thread thread : threads) {
                thread.join();
            }
            assertEquals(1, winners.get());
            assertEquals("one", store.take("receipt/r1").orElseThrow());
            assertTrue(store.take("receipt/r1").isEmpty(), "take is a single-use operation");
        }
    }

    @Test
    void rejectedRateCostIsNotChargedAndWindowRollsOver() throws Exception {
        try (DerbyStateStore store = open()) {
            store.put("value", "one", null);
            store.put("value", "two", null);
            assertEquals("two", store.get("value").orElseThrow(), "put replaces an existing value");
            assertTrue(store.scan("value", 0).isEmpty(), "zero scan limit returns no state");
            assertFalse(store.tryAcquire("rate/bad", -1, 5, Duration.ofMinutes(1), NOW));
            assertTrue(store.tryAcquire("rate/alice", 3, 5, Duration.ofMinutes(1), NOW));
            assertFalse(store.tryAcquire("rate/alice", 3, 5, Duration.ofMinutes(1), NOW));
            assertTrue(store.tryAcquire("rate/alice", 2, 5, Duration.ofMinutes(1), NOW),
                    "the refused cost must not consume budget");
            assertTrue(store.tryAcquire("rate/alice", 5, 5, Duration.ofMinutes(1), NOW.plusSeconds(60)));
        }
    }
}
