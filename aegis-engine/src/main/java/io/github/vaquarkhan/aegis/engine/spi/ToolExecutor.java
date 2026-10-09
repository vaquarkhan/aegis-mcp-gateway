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

import io.github.vaquarkhan.aegis.engine.model.ToolResult;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Performs a governed call against a backend. Implementations contribute capability only; they
 * never make policy decisions (same rule as 0.1 adapters). The engine calls {@link #execute} only
 * after every control has passed and the intent has been durably audited.
 */
public interface ToolExecutor {

    /**
     * Network destinations this call will contact, derived from the backend arguments. The egress
     * control checks every one BEFORE execution. Return an empty list for executors that make no
     * network call from the gateway (for example a stdio subprocess started by the operator).
     */
    List<URI> egressTargets(Map<String, Object> backendArguments);

    /**
     * Executes the call. Must honour {@link ExecutionContext#timeoutMillis()} and must bound the
     * size of anything it reads from the backend. An executor must distinguish an availability
     * failure that warrants opening a shared circuit breaker from an application-level rejection
     * of one otherwise healthy request.
     *
     * @throws ExecutionException for backend failures
     */
    ToolResult execute(ExecutionContext context) throws ExecutionException;

    /**
     * True only when this executor runs the tool named by {@link ExecutionContext#upstreamToolName()}
     * (an MCP proxy does). Executors bound to one fixed operation must return false, so the same
     * executor can never be reused as a "preview" that would actually run the real operation.
     */
    default boolean dispatchesByUpstreamName() {
        return false;
    }

    /** Backend failure raised by an executor. The message must not contain secrets. */
    final class ExecutionException extends Exception {
        private static final long serialVersionUID = 1L;
        private final boolean timeout;
        private final boolean availabilityFailure;

        /**
         * Creates a failure with an explicit breaker classification. Existing source executors use
         * the compatibility constructor below, which conservatively treats failures as availability
         * failures until the source can identify a request-specific rejection.
         */
        public ExecutionException(String message, boolean timeout, boolean availabilityFailure, Throwable cause) {
            super(message, cause);
            this.timeout = timeout;
            this.availabilityFailure = availabilityFailure;
        }

        public ExecutionException(String message, boolean timeout, Throwable cause) {
            this(message, timeout, true, cause);
        }

        public ExecutionException(String message) {
            this(message, false, true, null);
        }

        public boolean timeout() {
            return timeout;
        }

        /** True only when the source's availability, not this request, should affect the breaker. */
        public boolean availabilityFailure() {
            return availabilityFailure;
        }
    }
}
