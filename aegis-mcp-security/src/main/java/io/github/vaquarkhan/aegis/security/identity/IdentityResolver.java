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
package io.github.vaquarkhan.aegis.security.identity;

import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.mcp.http.McpHttpRequest;
import java.util.Set;

/**
 * Authenticates an inbound request when the gateway serves its own front door. (In Knox mode
 * this is Knox's provider
 * chain; Aegis only reads the resulting Subject.) Returns null when the request is not
 * authenticated; the reason is logged by the implementation, never returned to the client.
 */
@FunctionalInterface
public interface IdentityResolver {

    /**
     * A verified caller plus route-admission scopes. The immutable scope set also lives on
     * {@link CallerIdentity} so the governance engine can enforce trusted action scopes without
     * carrying raw tokens or claim material.
     */
    record Authenticated(CallerIdentity caller, Set<String> scopes) {
        public Authenticated {
            scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
        }
    }

    CallerIdentity resolve(McpHttpRequest request);

    /** Authenticates and returns the granted scopes; resolvers without scopes return an empty set. */
    default Authenticated authenticate(McpHttpRequest request) {
        CallerIdentity caller = resolve(request);
        return caller == null ? null : new Authenticated(caller, Set.of());
    }

    /** True when {@link #authenticate} can report granted scopes (an OAuth access token). */
    default boolean carriesScopes() {
        return false;
    }

    /** Extracts a bearer token, or null. */
    static String bearer(McpHttpRequest request) {
        if (request.headerCount("Authorization") != 1) {
            return null;
        }
        String h = request.header("Authorization");
        if (h == null || h.length() < 8 || !h.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String t = h.substring(7).trim();
        return t.isEmpty() ? null : t;
    }
}
