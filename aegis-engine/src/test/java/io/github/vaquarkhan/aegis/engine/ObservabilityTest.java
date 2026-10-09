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
package io.github.vaquarkhan.aegis.engine;

import static io.github.vaquarkhan.aegis.engine.EngineFixture.ANALYST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.model.DenyCodes;
import io.github.vaquarkhan.aegis.engine.model.ToolCall;
import io.github.vaquarkhan.aegis.engine.model.TraceContext;
import io.github.vaquarkhan.aegis.engine.observe.DecisionMetrics;
import io.github.vaquarkhan.aegis.engine.spi.DecisionListener;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** W3C trace context propagation, decision listeners and Prometheus metrics. */
class ObservabilityTest {

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String PARENT = "00f067aa0ba902b7";
    private static final String INBOUND = "00-" + TRACE + "-" + PARENT + "-01";

    @Test
    void traceparentParsingFollowsW3cRules() {
        TraceContext c = TraceContext.continueFrom(INBOUND);
        assertEquals(TRACE, c.traceId());
        assertEquals(PARENT, c.parentSpanId());
        assertNotEquals(PARENT, c.spanId(), "the gateway is its own hop");
        assertTrue(c.sampled());
        assertEquals("00-" + TRACE + "-" + c.spanId() + "-01", c.header());

        for (String bad : new String[] {
                null, "", "garbage",
                "00-" + "0".repeat(32) + "-" + PARENT + "-01",            // all-zero trace id
                "00-" + TRACE + "-" + "0".repeat(16) + "-01",             // all-zero span id
                "ff-" + TRACE + "-" + PARENT + "-01",                     // version ff is invalid
                "00-" + TRACE.toUpperCase() + "-" + PARENT + "-01",       // upper-case hex
                "00-" + TRACE + "-" + PARENT + "-01-extra",               // version 00 has exactly 4 fields
                "00-" + TRACE.substring(1) + "-" + PARENT + "-01"}) {     // wrong length
            TraceContext r = TraceContext.continueFrom(bad);
            assertNull(r.parentSpanId(), "invalid inbound context starts a new root: " + bad);
            assertNotEquals(TRACE, r.traceId());
            assertEquals(32, r.traceId().length());
        }
        // A future version may carry more fields; the first four are still used.
        assertEquals(TRACE, TraceContext.continueFrom("01-" + TRACE + "-" + PARENT + "-01-future").traceId());
        // _meta wins over the header; an invalid _meta value falls back to the header.
        String other = "00-" + "1".repeat(32) + "-" + PARENT + "-00";
        assertEquals(TRACE, TraceContext.from(Map.of("traceparent", INBOUND), other).traceId());
        assertEquals("1".repeat(32), TraceContext.from(Map.of("traceparent", "nope"), other).traceId());
    }

    @Test
    void traceIsAuditedAndForwardedToTheBackend() throws Exception {
        EngineFixture f = new EngineFixture();
        CallOutcome o = f.engine.call(ANALYST, new ToolCall("1", "list_topics", Map.of(),
                Map.of("traceparent", INBOUND), "2026-07-28"));
        assertTrue(o.allowed(), o.decision().toString());
        String forwarded = f.executor.calls.get(0).traceparent();
        assertTrue(forwarded.startsWith("00-" + TRACE + "-"), forwarded);
        assertFalse(forwarded.contains(PARENT), "the backend's parent is the gateway span, not the client's");
        for (Map<String, Object> r : f.audit.records()) {
            assertEquals(TRACE, r.get("traceId"));
            assertEquals(PARENT, r.get("parentSpanId"));
            assertEquals(forwarded.substring(36, 52), r.get("spanId"));
        }
        assertTrue(Json.write(o.toMcpResult()).contains(TRACE), "the client can correlate from the result _meta");
    }

    @Test
    void listenersSeeEveryOutcomeAndCannotBreakEnforcement() throws Exception {
        EngineFixture f = new EngineFixture();
        assertTrue(f.engine.call(ANALYST, new ToolCall("1", "list_topics", Map.of(), Map.of(), "")).allowed());
        assertEquals(DenyCodes.READONLY_CALLER,
                f.engine.call(ANALYST, new ToolCall("2", "create_topic", Map.of("topic", "t"), Map.of(), "")).decision().code());
        assertEquals(DenyCodes.NOT_EXPOSED,
                f.engine.call(ANALYST, new ToolCall("3", "no_such_tool_" + "x".repeat(50), Map.of(), Map.of(), "")).decision().code());

        assertEquals(3, f.decisions.size(), "the throwing listener did not stop the others or the calls");
        DecisionListener.Event allowed = f.decisions.get(0);
        assertEquals("ALLOWED", allowed.outcome());
        assertTrue(allowed.reachedBackend());
        assertEquals("list_topics", allowed.method());
        DecisionListener.Event denied = f.decisions.get(1);
        assertEquals(DenyCodes.READONLY_CALLER, denied.outcome());
        assertEquals(1, denied.step());
        assertFalse(denied.reachedBackend());

        String text = f.metrics.renderPrometheus();
        assertTrue(text.contains("aegis_decisions_total{kind=\"tool\",method=\"list_topics\",source=\"engine\",outcome=\"ALLOWED\"} 1"), text);
        assertTrue(text.contains("outcome=\"READONLY_CALLER\"} 1"), text);
        assertTrue(text.contains("method=\"_unknown\""), "unknown tool names never become label values");
        assertFalse(text.contains("no_such_tool"), text);
        assertTrue(text.contains("aegis_backend_calls_total{kind=\"tool\",source=\"engine\"} 1"), text);
        assertTrue(text.contains("aegis_decision_duration_seconds_count{kind=\"tool\"} 3"), text);
        assertTrue(text.contains("aegis_decision_duration_seconds_bucket{kind=\"tool\",le=\"+Inf\"} 3"), text);
        assertEquals(1, f.metrics.total("ALLOWED"));
    }

    @Test
    void labelValuesAreEscaped() {
        DecisionMetrics m = new DecisionMetrics();
        m.onDecision(new DecisionListener.Event("r", "t", "a\"b\\c\nd", "content", "READ", "s", "ALLOWED", 0, false, 3));
        assertTrue(m.renderPrometheus().contains("method=\"a\\\"b\\\\c\\nd\""), m.renderPrometheus());
    }
}
