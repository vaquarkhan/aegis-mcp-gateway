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

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Collects, for one request, the action scopes a call was refused for ({@code SCOPE_DENIED} with
 * {@code missingActionScopes}), so a front door that can run the OAuth step-up flow can answer with
 * an {@code insufficient_scope} challenge naming them (RFC 6750 section 3, MCP authorization scope
 * challenge).
 *
 * <p>The front door opens a scope around the request, on the thread that runs the governance chain
 * (the chain is synchronous, as for {@link SubjectToken}). The engine adds the scopes the tool
 * requires when it refuses a call for a missing one. With no scope open, nothing is recorded, so
 * hosts that do not run step-up (Knox, stdio, embedding) see no change. The decision itself is the
 * same either way: the call is denied and journaled.
 */
public final class ScopeChallenge {

    private static final ThreadLocal<Set<String>> CURRENT = new ThreadLocal<>();

    private ScopeChallenge() {}

    /** Starts collecting for the current request; the previous collector is restored on close. */
    public static Scope open() {
        Scope s = new Scope(CURRENT.get());
        CURRENT.set(new LinkedHashSet<>());
        return s;
    }

    /** Records scopes a refused call requires. A no-op when no front door opened a scope. */
    public static void require(Collection<String> scopes) {
        Set<String> current = CURRENT.get();
        if (current != null && scopes != null) {
            current.addAll(scopes);
        }
    }

    /** The open collector; use with try-with-resources. */
    public static final class Scope implements AutoCloseable {
        private final Set<String> previous;
        private boolean closed;

        private Scope(Set<String> previous) {
            this.previous = previous;
        }

        /** The scopes recorded so far in this request, in the order first seen. */
        public List<String> required() {
            Set<String> current = CURRENT.get();
            return closed || current == null ? List.of() : List.copyOf(current);
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
