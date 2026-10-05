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

import java.util.Optional;

/**
 * The caller's verified inbound access token, for the duration of one request, so that a
 * {@link CredentialProvider} can exchange it (RFC 8693) for a backend token issued to the same user.
 *
 * <p>Why a request-scoped holder and not a field: {@link io.github.vaquarkhan.aegis.engine.model.CallerIdentity}
 * is copied into audit records, hook requests, policy input and approval views. Keeping the token out
 * of it means none of those can ever carry it. The token is bound by the front door after it verified
 * the token, on the thread that runs the governance chain (the chain is synchronous), and unbound when
 * the request ends.
 *
 * <p>Rules for readers: only a credential provider reads it, only to send it to the identity provider
 * that issued it, never to a backend (that would be token passthrough), and never into a log, an
 * exception message or an audit record. {@link #toString()} of the scope never prints it.
 */
public final class SubjectToken {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private SubjectToken() {}

    /** Binds {@code token} until the returned scope is closed; the previous value is restored. */
    public static Scope bind(String token) {
        String previous = CURRENT.get();
        if (token == null || token.isBlank()) {
            CURRENT.remove();
        } else {
            CURRENT.set(token);
        }
        return new Scope(previous);
    }

    /** The token bound for the current request, if the front door verified one. */
    public static Optional<String> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** Restores what was bound before; use with try-with-resources. */
    public static final class Scope implements AutoCloseable {
        private final String previous;
        private boolean closed;

        private Scope(String previous) {
            this.previous = previous;
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

        @Override
        public String toString() {
            return "SubjectToken.Scope";
        }
    }
}
