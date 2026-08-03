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
package io.github.vaquarkhan.aegis.core.finops;

import io.github.vaquarkhan.aegis.core.spi.CallContext;
import io.github.vaquarkhan.aegis.core.spi.ToolClass;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Bounded in-memory result cache for read tools.
 *
 * <p>Only {@link ToolClass#READ} calls are cacheable. Caching a mutating call would let a second
 * caller receive a result for an operation that never ran, which is a correctness and audit
 * problem, not just a staleness problem.
 *
 * <p>Entries are keyed by caller, tool and a normalised argument map, so one caller's scoped result
 * can never be served to another. This is an in-process cache only; a shared cache is out of scope
 * for 0.1.0.
 *
 * @author Viquar Khan
 */
public final class SemanticCache {

    private static final int MAX_ENTRIES = 512;

    private final long ttlMillis;
    private final Map<String, Entry> entries;
    private long hits;
    private long misses;

    public SemanticCache(long ttlMillis) {
        this.ttlMillis = Math.max(0L, ttlMillis);
        this.entries = new LinkedHashMap<>(64, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return size() > MAX_ENTRIES;
            }
        };
    }

    public boolean enabled() {
        return ttlMillis > 0;
    }

    public long ttlMillis() {
        return ttlMillis;
    }

    public boolean cacheable(CallContext ctx) {
        return enabled() && ctx != null && ctx.cls() == ToolClass.READ;
    }

    public Optional<String> get(CallContext ctx) {
        if (!cacheable(ctx)) {
            return Optional.empty();
        }
        String key = keyOf(ctx);
        synchronized (entries) {
            Entry e = entries.get(key);
            if (e == null) {
                misses++;
                return Optional.empty();
            }
            if (e.expiresAt < System.currentTimeMillis()) {
                entries.remove(key);
                misses++;
                return Optional.empty();
            }
            hits++;
            return Optional.of(e.body);
        }
    }

    public void put(CallContext ctx, String body) {
        if (!cacheable(ctx) || body == null) {
            return;
        }
        synchronized (entries) {
            entries.put(keyOf(ctx), new Entry(body, System.currentTimeMillis() + ttlMillis));
        }
    }

    public void invalidate(CallContext ctx) {
        synchronized (entries) {
            entries.remove(keyOf(ctx));
        }
    }

    public void clear() {
        synchronized (entries) {
            entries.clear();
        }
    }

    public int size() {
        synchronized (entries) {
            return entries.size();
        }
    }

    public long hits() {
        synchronized (entries) {
            return hits;
        }
    }

    public long misses() {
        synchronized (entries) {
            return misses;
        }
    }

    static String keyOf(CallContext ctx) {
        String caller = ctx.caller() == null ? "-" : ctx.caller().callerId();
        StringBuilder sb = new StringBuilder(caller).append('|').append(ctx.toolName());
        for (Map.Entry<String, Object> e : new TreeMap<>(ctx.arguments()).entrySet()) {
            sb.append('|').append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private record Entry(String body, long expiresAt) {}
}
