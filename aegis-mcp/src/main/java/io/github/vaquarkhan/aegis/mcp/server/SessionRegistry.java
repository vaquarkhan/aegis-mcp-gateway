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
package io.github.vaquarkhan.aegis.mcp.server;

import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Legacy-era (2025) MCP sessions. A session id is a 256-bit random value AND is bound to the
 * caller that created it: presenting someone else's session id is answered exactly like an
 * unknown id (404), so a leaked id cannot be used by a different principal (session hijack).
 *
 * <p>Sessions hold only protocol state (the negotiated version). Governance state never hangs
 * off a session, so the same controls hold in the stateless 2026-07-28 era.
 */
public final class SessionRegistry {

    public record Session(String id, String principalKey, String protocolVersion, long createdAt, long lastSeen) {}

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final long idleTtlMillis;
    private final int maxSessions;

    public SessionRegistry(Clock clock, long idleTtlMillis, int maxSessions) {
        this.clock = clock;
        this.idleTtlMillis = idleTtlMillis;
        this.maxSessions = maxSessions;
    }

    /** Live sessions one principal may hold, so one caller cannot exhaust the registry for everyone. */
    public static final int MAX_SESSIONS_PER_CALLER = 32;

    /** @return the new session, or empty when the registry (or this caller's share) is full (caller answers 503) */
    public synchronized Optional<Session> create(CallerIdentity caller, String version) {
        long now = clock.millis();
        String pk = caller.principalKey();
        if (sessions.size() >= maxSessions) {
            sessions.values().removeIf(s -> now - s.lastSeen() > idleTtlMillis);
        }
        long mine = sessions.values().stream().filter(s -> s.principalKey().equals(pk) && now - s.lastSeen() <= idleTtlMillis).count();
        if (mine >= MAX_SESSIONS_PER_CALLER) {
            // Evict this caller's least recently used session rather than refusing a reconnect.
            sessions.values().stream().filter(s -> s.principalKey().equals(pk))
                    .min(java.util.Comparator.comparingLong(Session::lastSeen))
                    .ifPresent(s -> sessions.remove(s.id(), s));
        }
        if (sessions.size() >= maxSessions) {
            return Optional.empty();
        }
        byte[] b = new byte[32];
        random.nextBytes(b);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        Session s = new Session(id, caller.principalKey(), version, now, now);
        sessions.put(id, s);
        return Optional.of(s);
    }

    /** Finds a live session owned by this caller; refreshes its idle timer. */
    public Optional<Session> find(String id, CallerIdentity caller) {
        if (id == null) {
            return Optional.empty();
        }
        Session s = sessions.get(id);
        long now = clock.millis();
        if (s == null) {
            return Optional.empty();
        }
        if (now - s.lastSeen() > idleTtlMillis) {
            sessions.remove(id, s);
            return Optional.empty();
        }
        if (!s.principalKey().equals(caller.principalKey())) {
            return Optional.empty();
        }
        Session touched = new Session(s.id(), s.principalKey(), s.protocolVersion(), s.createdAt(), now);
        sessions.replace(id, s, touched);
        return Optional.of(touched);
    }

    public boolean delete(String id, CallerIdentity caller) {
        Optional<Session> s = find(id, caller);
        return s.isPresent() && sessions.remove(id) != null;
    }

    public int size() {
        return sessions.size();
    }
}
