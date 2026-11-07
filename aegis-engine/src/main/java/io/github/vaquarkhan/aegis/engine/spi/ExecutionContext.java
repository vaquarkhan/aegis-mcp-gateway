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

import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import java.util.Map;

/**
 * Everything an executor may use for one call.
 *
 * @param requestId        correlation id (also written to audit)
 * @param upstreamToolName the tool name as the backend knows it (may differ from the exposed name)
 * @param arguments        backend arguments: governance control values already removed
 * @param caller           the governed caller, for backend-side correlation only; it carries no credential
 * @param credentials      headers resolved by the {@link CredentialProvider} for this source, after
 *                         governance; never the caller's own token
 * @param timeoutMillis    budget for the whole backend interaction
 * @param dryRun           true when the engine asks for a native preview (dry-run mode ARGUMENT/TOOL)
 * @param traceparent      W3C {@code traceparent} for this hop; executors that call a backend over
 *                         HTTP or MCP forward it so the backend joins the caller's trace (never null)
 * @param roundTrip        MCP 2026-07-28 multi round-trip retry data to forward (MCP executors only; never null)
 */
public record ExecutionContext(
        String requestId,
        String upstreamToolName,
        Map<String, Object> arguments,
        CallerIdentity caller,
        Map<String, String> credentials,
        long timeoutMillis,
        boolean dryRun,
        String traceparent,
        io.github.vaquarkhan.aegis.engine.model.RoundTrip roundTrip) {

    /** Context without multi round-trip data. */
    public ExecutionContext(String requestId, String upstreamToolName, Map<String, Object> arguments, CallerIdentity caller,
                            Map<String, String> credentials, long timeoutMillis, boolean dryRun, String traceparent) {
        this(requestId, upstreamToolName, arguments, caller, credentials, timeoutMillis, dryRun, traceparent,
                io.github.vaquarkhan.aegis.engine.model.RoundTrip.NONE);
    }

    /** Context with a fresh root trace (for executors used outside the engine, e.g. tests). */
    public ExecutionContext(String requestId, String upstreamToolName, Map<String, Object> arguments, CallerIdentity caller,
                            Map<String, String> credentials, long timeoutMillis, boolean dryRun) {
        this(requestId, upstreamToolName, arguments, caller, credentials, timeoutMillis, dryRun,
                io.github.vaquarkhan.aegis.engine.model.TraceContext.newRoot().header(),
                io.github.vaquarkhan.aegis.engine.model.RoundTrip.NONE);
    }

    public ExecutionContext {
        traceparent = traceparent == null ? io.github.vaquarkhan.aegis.engine.model.TraceContext.newRoot().header() : traceparent;
        roundTrip = roundTrip == null ? io.github.vaquarkhan.aegis.engine.model.RoundTrip.NONE : roundTrip;
        arguments = arguments == null ? Map.of() : arguments;
        credentials = credentials == null ? Map.of() : Map.copyOf(credentials);
    }

    @Override
    public String toString() {
        // Never print credential values.
        return "ExecutionContext[requestId=" + requestId + ", tool=" + upstreamToolName
                + ", credentials=" + credentials.keySet() + ", dryRun=" + dryRun + ", traceparent=" + traceparent + "]";
    }
}
