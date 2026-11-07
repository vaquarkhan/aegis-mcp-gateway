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

/**
 * Observes every final governance outcome (metrics, alerting, a SIEM bridge). Called synchronously
 * after the outcome is decided and audited, on the request thread; implementations must be fast
 * and must not block. A listener cannot change a decision, and an exception thrown by a listener is
 * logged and ignored, so observability can never weaken or break enforcement.
 *
 * <p>Events carry no arguments, results or credentials: only names, codes and timings.
 */
@FunctionalInterface
public interface DecisionListener {

    void onDecision(Event event);

    /**
     * The span this request occupies, for a listener that exports traces. Separate from the rest of the
     * event because most listeners (metrics, alerting) do not need it.
     *
     * @param spanId           this gateway's own span id, 16 hex characters
     * @param parentSpanId     the caller's span id when it sent a {@code traceparent}, else null
     * @param startEpochMillis when the gateway began handling the request
     */
    record Span(String spanId, String parentSpanId, long startEpochMillis) {}

    /**
     * @param requestId  gateway request id (same as in audit)
     * @param traceId    W3C trace id of the request
     * @param method     the MCP operation: exposed tool name for tools/call, else the method
     *                   ({@code resources/read}, {@code prompts/get}, {@code completion/complete}, lists)
     * @param kind       {@code tool} or {@code content}
     * @param toolClass  READ, MUTATE or DESTRUCTIVE; empty when the tool was unknown
     * @param sourceId   backend source id; empty when not resolved
     * @param outcome    {@code ALLOWED} or the stable deny code
     * @param step       deny step, 0 when allowed
     * @param reachedBackend whether the backend was called
     * @param durationMillis gateway time for the whole request
     * @param span       span identity and start time, or null when the producer does not track spans
     */
    record Event(String requestId, String traceId, String method, String kind, String toolClass, String sourceId,
                 String outcome, int step, boolean reachedBackend, long durationMillis, Span span) {

        /** Without span identity: for embedders and tests that only observe outcomes. */
        public Event(String requestId, String traceId, String method, String kind, String toolClass, String sourceId,
                     String outcome, int step, boolean reachedBackend, long durationMillis) {
            this(requestId, traceId, method, kind, toolClass, sourceId, outcome, step, reachedBackend, durationMillis, null);
        }

        public boolean allowed() {
            return "ALLOWED".equals(outcome);
        }
    }
}
