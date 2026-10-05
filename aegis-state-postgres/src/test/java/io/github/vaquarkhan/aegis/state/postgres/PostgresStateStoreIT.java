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
package io.github.vaquarkhan.aegis.state.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.config.BuildContext;
import io.github.vaquarkhan.aegis.config.ConfigNode;
import io.github.vaquarkhan.aegis.engine.spi.StateStore;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Real PostgreSQL integration tests. They run only with {@code -Ppostgres-integration} and environment
 * variables {@code AEGIS_POSTGRES_URL}, {@code AEGIS_POSTGRES_USER}, and {@code AEGIS_POSTGRES_PASSWORD}.
 * No container, fake JDBC driver, mock database, or in-memory substitute is used.
 */
class PostgresStateStoreIT {

    private static final Instant NOW = Instant.parse("2026-10-02T00:00:00Z");
    private String url;
    private String user;
    private String password;
    private String namespace;

    @BeforeEach
    void requireRealPostgres() {
        url = System.getenv("AEGIS_POSTGRES_URL");
        user = System.getenv("AEGIS_POSTGRES_USER");
        password = System.getenv("AEGIS_POSTGRES_PASSWORD");
        Assumptions.assumeTrue(url != null && user != null && password != null,
                "set AEGIS_POSTGRES_URL, AEGIS_POSTGRES_USER and AEGIS_POSTGRES_PASSWORD; skipped, never passed");
        namespace = "it-" + UUID.randomUUID();
    }

    @AfterEach
    void removeOnlyThisTestNamespace() throws Exception {
        if (url == null || namespace == null) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            try (PreparedStatement state = connection.prepareStatement("DELETE FROM aegis_state WHERE namespace = ?");
                    PreparedStatement window = connection.prepareStatement("DELETE FROM aegis_window WHERE namespace = ?")) {
                state.setString(1, namespace);
                state.executeUpdate();
                window.setString(1, namespace);
                window.executeUpdate();
            }
        }
    }

    @Test
    void twoIndependentStoresShareSingleUseNonceAndRateWindow() throws Exception {
        try (PostgresStateStore first = open(); PostgresStateStore second = open()) {
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger nonceWinners = new AtomicInteger();
            AtomicInteger admitted = new AtomicInteger();
            ExecutorService workers = Executors.newFixedThreadPool(24);
            try {
                Future<?>[] futures = new Future<?>[24];
                for (int i = 0; i < futures.length; i++) {
                    StateStore store = i % 2 == 0 ? first : second;
                    futures[i] = workers.submit(() -> {
                        try {
                            start.await();
                            if (store.putIfAbsent("approval-used:nonce", "used", NOW.plusSeconds(60))) {
                                nonceWinners.incrementAndGet();
                            }
                            if (store.tryAcquire("rate:alice", 1, 10, Duration.ofMinutes(1), NOW)) {
                                admitted.incrementAndGet();
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                }
                start.countDown();
                for (Future<?> future : futures) {
                    future.get(30, TimeUnit.SECONDS);
                }
            } finally {
                workers.shutdownNow();
            }
            assertEquals(1, nonceWinners.get(), "one real PostgreSQL uniqueness winner across two stores");
            assertEquals(10, admitted.get(), "atomic PostgreSQL counter admits no more than capacity");
            assertEquals("used", first.take("approval-used:nonce").orElseThrow());
            assertTrue(second.take("approval-used:nonce").isEmpty(), "take remains global and single-use");
            assertFalse(second.tryAcquire("rate:alice", 1, 10, Duration.ofMinutes(1), NOW), "full window rejects");
            assertTrue(second.tryAcquire("rate:alice", 10, 10, Duration.ofMinutes(1), NOW.plusSeconds(60)),
                    "next fixed window has a fresh budget");
        }
    }

    @Test
    void persistsAcrossConnectionsExpiresAndDoesNotChargeRejectedCost() throws Exception {
        try (PostgresStateStore first = open()) {
            first.put("approval/request", "pending", NOW.plusSeconds(60));
            first.put("expired", "old", NOW.minusSeconds(1));
            assertTrue(first.tryAcquire("rate:bob", 3, 5, Duration.ofMinutes(1), NOW));
            assertFalse(first.tryAcquire("rate:bob", 3, 5, Duration.ofMinutes(1), NOW), "rejected cost is not charged");
        }
        try (PostgresStateStore reopened = open()) {
            assertEquals("pending", reopened.get("approval/request").orElseThrow());
            assertTrue(reopened.get("expired").isEmpty());
            assertEquals(Map.of("approval/request", "pending"), reopened.scan("approval/", 10));
            assertTrue(reopened.tryAcquire("rate:bob", 2, 5, Duration.ofMinutes(1), NOW),
                    "only the first admitted cost was charged");
        }
    }

    @Test
    void scanEscapesPrefixesAndInvalidRateRequestsDoNotTouchRealState() throws Exception {
        try (PostgresStateStore store = open()) {
            store.put("scan_%/first", "one", NOW.plusSeconds(60));
            store.put("scan_%/second", "two", NOW.plusSeconds(60));
            store.put("scan-other", "other", NOW.plusSeconds(60));

            assertEquals(Map.of(), store.scan("scan_%/", 0), "non-positive scan limits return no state");
            assertEquals(Map.of("scan_%/first", "one"), store.scan("scan_%/", 1),
                    "prefix wildcards are escaped rather than widening the query");
            assertEquals(Map.of("scan_%/first", "one", "scan_%/second", "two"), store.scan("scan_%/", 10));

            assertFalse(store.tryAcquire("rate:invalid", -1, 1, Duration.ofMinutes(1), NOW));
            assertFalse(store.tryAcquire("rate:invalid", 1, 0, Duration.ofMinutes(1), NOW));
            assertFalse(store.tryAcquire("rate:invalid", 1, 1, Duration.ZERO, NOW));
            assertTrue(store.tryAcquire("rate:valid", 1, 1, Duration.ofMinutes(1), NOW),
                    "invalid requests must not create or charge a counter");
        }
    }

    @Test
    void factoryUsesEnvironmentNamesRatherThanCredentialValues() throws Exception {
        PostgresStateStoreFactory factory = new PostgresStateStoreFactory();
        assertEquals("postgres", factory.type());
        Map<String, String> env = Map.of("PG_USER", user, "PG_PASSWORD", password);
        ConfigNode settings = new ConfigNode("state.settings", Map.of(
                "jdbcUrl", url, "usernameEnv", "PG_USER", "passwordEnv", "PG_PASSWORD",
                "namespace", namespace, "connectTimeoutSeconds", 10L));
        try (PostgresStateStore store = (PostgresStateStore) factory.create(settings,
                BuildContext.defaults(java.nio.file.Path.of(".")).withEnv(env::get))) {
            assertTrue(store.putIfAbsent("receipt", "ok", NOW.plusSeconds(60)));
        }
    }

    private PostgresStateStore open() throws Exception {
        return new PostgresStateStore(url, user, password, namespace, 10, Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
