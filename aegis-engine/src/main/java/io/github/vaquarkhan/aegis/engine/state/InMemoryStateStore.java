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
package io.github.vaquarkhan.aegis.engine.state;

import io.github.vaquarkhan.aegis.engine.spi.HighWaterStateStore;
import io.github.vaquarkhan.aegis.engine.spi.StateStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single-replica {@link StateStore}. All mutations use {@link ConcurrentHashMap#compute} so each is
 * atomic. Bounded: when {@code maxEntries} is reached, expired entries are evicted and, if still
 * full, new keys are refused (put-if-absent returns false, which callers treat as a denial), so
 * an attacker cannot grow memory without bound.
 */
public final class InMemoryStateStore implements HighWaterStateStore {

    private record Entry(String value, Instant expiresAt) {
        boolean live(Instant now) {
            return expiresAt == null || now.isBefore(expiresAt);
        }
    }

    private record Window(long start, long used) {}

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int maxEntries;
    private final AtomicInteger writesSinceSweep = new AtomicInteger();

    public InMemoryStateStore(Clock clock, int maxEntries) {
        this.clock = clock;
        this.maxEntries = maxEntries <= 0 ? 1_000_000 : maxEntries;
    }

    public InMemoryStateStore() {
        this(Clock.systemUTC(), 1_000_000);
    }

    @Override
    public boolean putIfAbsent(String key, String value, Instant expiresAt) {
        Instant now = clock.instant();
        if (!capacityFor(key, now)) {
            return false;
        }
        AtomicBoolean stored = new AtomicBoolean(false);
        entries.compute(key, (k, cur) -> {
            if (cur != null && cur.live(now)) {
                return cur;
            }
            stored.set(true);
            return new Entry(value, expiresAt);
        });
        return stored.get();
    }

    @Override
    public void put(String key, String value, Instant expiresAt) {
        Instant now = clock.instant();
        if (!capacityFor(key, now)) {
            throw new IllegalStateException("state store is full");
        }
        entries.put(key, new Entry(value, expiresAt));
    }

    @Override
    public Optional<String> get(String key) {
        Entry e = entries.get(key);
        if (e == null) {
            return Optional.empty();
        }
        if (!e.live(clock.instant())) {
            entries.remove(key, e);
            return Optional.empty();
        }
        return Optional.of(e.value());
    }

    @Override
    public Optional<String> take(String key) {
        Entry e = entries.remove(key);
        if (e == null || !e.live(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(e.value());
    }

    @Override
    public Map<String, String> scan(String prefix, int limit) {
        Instant now = clock.instant();
        TreeMap<String, String> out = new TreeMap<>();
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            if (e.getKey().startsWith(prefix) && e.getValue().live(now)) {
                out.put(e.getKey(), e.getValue().value());
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }

    @Override
    public long updateHighWaterMark(String key, long candidate) {
        if (candidate < 0) {
            throw new IllegalArgumentException("high-water candidate must not be negative");
        }
        Instant now = clock.instant();
        AtomicLong result = new AtomicLong();
        entries.compute(key, (k, current) -> {
            long existing = current == null || !current.live(now) ? 0 : parseHighWater(current.value());
            long next = Math.max(existing, candidate);
            if (next > 0) {
                result.set(next);
                return new Entry(Long.toString(next), null);
            }
            result.set(0);
            return current != null && current.live(now) ? current : null;
        });
        return result.get();
    }

    private static long parseHighWater(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 1) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalStateException("state store holds an unreadable high-water mark", e);
        }
    }

    @Override
    public boolean tryAcquire(String key, long cost, long capacity, Duration window, Instant now) {
        long w = window.toMillis();
        long nowMs = now.toEpochMilli();
        long start = nowMs - Math.floorMod(nowMs, w);
        if (!windows.containsKey(key) && windows.size() >= maxEntries) {
            sweepWindows(nowMs, w);
            if (windows.size() >= maxEntries) {
                return false;
            }
        }
        AtomicBoolean admitted = new AtomicBoolean(false);
        windows.compute(key, (k, cur) -> {
            Window base = (cur == null || cur.start() != start) ? new Window(start, 0) : cur;
            if (base.used() + cost > capacity) {
                return base;
            }
            admitted.set(true);
            return new Window(start, base.used() + cost);
        });
        return admitted.get();
    }

    private boolean capacityFor(String key, Instant now) {
        if (writesSinceSweep.incrementAndGet() >= 1024) {
            writesSinceSweep.set(0);
            sweep(now);
        }
        if (entries.size() < maxEntries || entries.containsKey(key)) {
            return true;
        }
        sweep(now);
        return entries.size() < maxEntries;
    }

    private void sweep(Instant now) {
        Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            if (!it.next().getValue().live(now)) {
                it.remove();
            }
        }
    }

    private void sweepWindows(long nowMs, long w) {
        windows.entrySet().removeIf(e -> e.getValue().start() + w <= nowMs);
    }

    public int size() {
        return entries.size();
    }
}
