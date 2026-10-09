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
package io.github.vaquarkhan.aegis.engine.spi;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Governance state that must outlive one request: single-use approval and receipt nonces, pending
 * approval requests, rate and budget windows, and trust-on-first-use tool pins.
 *
 * <p>Why the engine owns this state: in MCP 2025 revisions state could hang off {@code Mcp-Session-Id};
 * the 2026-07-28 revision removes protocol sessions (SEP-2567, SEP-2575). Governance state is
 * cross-request by nature, so it is keyed by caller identity and action, not by transport session.
 *
 * <p>Every mutating operation MUST be atomic. The bundled implementation is in-memory (one replica).
 * A multi-replica deployment plugs in a shared store with the same atomic semantics.
 */
public interface StateStore {

    /** Atomically stores the value only if the key is absent or expired. True when stored. */
    boolean putIfAbsent(String key, String value, Instant expiresAt);

    /** Stores or replaces a value. */
    void put(String key, String value, Instant expiresAt);

    Optional<String> get(String key);

    /** Removes and returns the value atomically (take-once semantics). */
    Optional<String> take(String key);

    /** Up to {@code limit} live entries whose key starts with {@code prefix}. */
    Map<String, String> scan(String prefix, int limit);

    /**
     * Atomically adds {@code cost} to the counter for the current fixed window when the result
     * stays within {@code capacity}. On refusal the counter is NOT changed (0.1 over-incremented
     * on refusal, so a throttled caller stayed throttled).
     *
     * @return true when the cost was admitted
     */
    boolean tryAcquire(String key, long cost, long capacity, Duration window, Instant now);
}
