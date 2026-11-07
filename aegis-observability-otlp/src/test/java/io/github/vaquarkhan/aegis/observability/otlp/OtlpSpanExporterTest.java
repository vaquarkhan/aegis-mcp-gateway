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
package io.github.vaquarkhan.aegis.observability.otlp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.json.JsonValues;
import io.github.vaquarkhan.aegis.engine.spi.DecisionListener;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The OTLP/HTTP JSON span exporter, against a local HTTP test endpoint for a collector.
 *
 * <p>What this proves: the exported document has the shape the OTLP trace service expects, the span
 * joins the caller's trace, no caller identity or arguments leave the gateway, and the exporter neither
 * blocks nor throws on the request thread even when the collector is broken, slow or absent.
 *
 * <p>What it does not prove: acceptance by a specific collector build. See the class javadoc on
 * {@link OtlpSpanExporter} for the hex versus base64 id-encoding divergence in the ecosystem.
 */
class OtlpSpanExporterTest {

    static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    static final String SPAN = "00f067aa0ba902b7";
    static final String PARENT = "a1b2c3d4e5f60718";

    static DecisionListener.Event toolEvent(String outcome, int step) {
        return new DecisionListener.Event("req_abc", TRACE, "delete_topic", "tool", "DESTRUCTIVE", "kafka",
                outcome, step, false, 42, new DecisionListener.Span(SPAN, PARENT, 1_700_000_000_000L));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> firstSpan(Map<String, Object> document) {
        List<Object> rs = JsonValues.asList(document.get("resourceSpans"));
        Map<String, Object> ss = JsonValues.asObject(JsonValues.asList(
                JsonValues.asObject(rs.get(0)).get("scopeSpans")).get(0));
        return JsonValues.asObject(JsonValues.asList(ss.get("spans")).get(0));
    }

    static String attribute(Map<String, Object> span, String key) {
        for (Object o : JsonValues.asList(span.get("attributes"))) {
            Map<String, Object> a = JsonValues.asObject(o);
            if (key.equals(a.get("key"))) {
                Map<String, Object> v = JsonValues.asObject(a.get("value"));
                Object first = v.values().iterator().next();
                return String.valueOf(first);
            }
        }
        return null;
    }

    @Test
    void theDocumentHasTheShapeTheTraceServiceExpects() {
        Map<String, Object> doc = OtlpSpanExporter.document(List.of(toolEvent("ALLOWED", 0)), "aegis-kafka");
        String json = Json.write(doc);

        // resourceSpans -> resource.attributes[service.name], scopeSpans -> scope + spans
        assertTrue(json.contains("\"resourceSpans\""), json);
        assertTrue(json.contains("\"service.name\"") && json.contains("aegis-kafka"), json);
        assertTrue(json.contains("\"scopeSpans\"") && json.contains("io.github.vaquarkhan.aegis"), json);

        Map<String, Object> span = firstSpan(doc);
        // Ids are hex, per the OTLP specification's departure from the protobuf JSON mapping.
        assertEquals(TRACE, span.get("traceId"));
        assertEquals(SPAN, span.get("spanId"));
        assertEquals(PARENT, span.get("parentSpanId"), "the span joins the caller's trace");
        assertEquals("tools/call delete_topic", span.get("name"));
        assertEquals(2L, span.get("kind"), "SERVER");
        // Nanosecond timestamps go out as strings so no reader loses precision.
        assertEquals("1700000000000000000", span.get("startTimeUnixNano"));
        // start 1_700_000_000_000 ms plus a 42 ms duration, in nanoseconds
        assertEquals("1700000000042000000", span.get("endTimeUnixNano"), "start plus the duration");
        assertEquals(0L, JsonValues.asObject(span.get("status")).get("code"), "UNSET when allowed");

        assertEquals("req_abc", attribute(span, "aegis.request_id"));
        assertEquals("tools/call", attribute(span, "mcp.method.name"));
        assertEquals("delete_topic", attribute(span, "aegis.tool"));
        assertEquals("DESTRUCTIVE", attribute(span, "aegis.tool_class"));
        assertEquals("kafka", attribute(span, "aegis.source"));
        assertEquals("ALLOWED", attribute(span, "aegis.outcome"));
        assertEquals("0", attribute(span, "aegis.step"));
        assertEquals("false", attribute(span, "aegis.reached_backend"));
    }

    @Test
    void aDenialBecomesAnErrorSpanCarryingTheStableDenyCode() {
        Map<String, Object> span = firstSpan(
                OtlpSpanExporter.document(List.of(toolEvent("APPROVAL_REQUIRED", 4)), "aegis"));
        Map<String, Object> status = JsonValues.asObject(span.get("status"));
        assertEquals(2L, status.get("code"), "ERROR");
        assertEquals("APPROVAL_REQUIRED", status.get("message"));
        assertEquals("4", attribute(span, "aegis.step"));
    }

    @Test
    void noCallerIdentityArgumentsOrResultsAreExported() {
        // The whole document is checked as text: correlate to the audit journal by request id instead.
        String json = Json.write(OtlpSpanExporter.document(List.of(
                new DecisionListener.Event("req_1", TRACE, "read_file", "content", "READ", "docs", "ALLOWED", 0, true, 5,
                        new DecisionListener.Span(SPAN, null, 1L))), "aegis"));
        for (String mustNotAppear : new String[] {"actor", "caller", "subject", "arguments", "issuer", "result",
                "authorization", "token"}) {
            assertFalse(json.toLowerCase(java.util.Locale.ROOT).contains(mustNotAppear), mustNotAppear + " in " + json);
        }
        // A root span (no traceparent from the caller) omits parentSpanId rather than sending an empty one.
        assertNull(firstSpan(OtlpSpanExporter.document(List.of(
                new DecisionListener.Event("r", TRACE, "m", "content", "READ", "s", "ALLOWED", 0, true, 1,
                        new DecisionListener.Span(SPAN, null, 1L))), "aegis")).get("parentSpanId"));
        // A content operation names the MCP method rather than inventing a tool name.
        Map<String, Object> content = firstSpan(OtlpSpanExporter.document(List.of(
                new DecisionListener.Event("r", TRACE, "resources/read", "content", "READ", "docs", "ALLOWED", 0, true, 1,
                        new DecisionListener.Span(SPAN, null, 1L))), "aegis"));
        assertEquals("resources/read", content.get("name"));
        assertEquals("resources/read", attribute(content, "mcp.method.name"));
        assertNull(attribute(content, "aegis.tool"));
    }

    @Test
    void spansReachACollectorAndTheExporterDrainsOnClose() throws Exception {
        List<String> bodies = new CopyOnWriteArrayList<>();
        List<String> contentTypes = new CopyOnWriteArrayList<>();
        List<String> authorizations = new CopyOnWriteArrayList<>();
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // The default executor runs the handler on the accept thread. Reading the request body there
        // deadlocks on Windows: the selector cannot pull the rest of the POST while the handler is
        // blocked in readAllBytes, so the exporter times out and counts zero spans.
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "otlp-test-collector");
            t.setDaemon(true);
            return t;
        });
        s.setExecutor(executor);
        s.createContext("/v1/traces", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            contentTypes.add(ex.getRequestHeaders().getFirst("Content-Type"));
            authorizations.add(ex.getRequestHeaders().getFirst("Authorization"));
            // A fixed length completes the exchange. -1 leaves some JDKs on chunked encoding with
            // no terminal chunk, and the client then waits until its deadline.
            byte[] ok = "{}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, ok.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(ok);
            }
            ex.close();
        });
        s.start();
        try {
            OtlpSpanExporter x = new OtlpSpanExporter(URI.create("http://127.0.0.1:" + s.getAddress().getPort() + "/v1/traces"),
                    Duration.ofSeconds(2), Map.of("Authorization", "Bearer t"), "aegis-test", 64, 8, 50);
            for (int i = 0; i < 5; i++) {
                x.onDecision(toolEvent("ALLOWED", 0));
            }
            x.close();   // drains what is queued
            assertEquals(5, x.exportedSpans(), "every span reached the collector");
            assertEquals(0, x.droppedSpans());
            assertEquals(0, x.failedBatches());
            assertFalse(bodies.isEmpty());
            assertEquals("application/json", contentTypes.get(0));
            assertEquals("Bearer t", authorizations.get(0));
            assertTrue(bodies.get(0).contains("\"resourceSpans\""), bodies.get(0));
        } finally {
            s.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void aBrokenOrAbsentCollectorNeverAffectsTheRequestThread() throws Exception {
        // 500 from the collector: counted as a failed batch, nothing thrown.
        AtomicInteger hits = new AtomicInteger();
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/v1/traces", ex -> {
            hits.incrementAndGet();
            byte[] b = "no".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(500, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
            ex.close();
        });
        s.start();
        try {
            OtlpSpanExporter x = new OtlpSpanExporter(URI.create("http://127.0.0.1:" + s.getAddress().getPort() + "/v1/traces"),
                    Duration.ofSeconds(2), Map.of(), "aegis", 64, 4, 50);
            x.onDecision(toolEvent("ALLOWED", 0));
            x.close();
            assertTrue(hits.get() >= 1);
            assertEquals(0, x.exportedSpans());
            assertTrue(x.failedBatches() >= 1, "a rejected batch is counted, not thrown");
        } finally {
            s.stop(0);
        }

        // Nothing listening at all: still no exception on the request thread.
        OtlpSpanExporter dead = new OtlpSpanExporter(URI.create("http://127.0.0.1:1/v1/traces"),
                Duration.ofMillis(300), Map.of(), "aegis", 8, 2, 20);
        dead.onDecision(toolEvent("ALLOWED", 0));
        dead.close();
        assertEquals(0, dead.exportedSpans());
    }

    @Test
    void aFullQueueDropsSpansAndCountsThemRatherThanBlocking() {
        // A collector that never answers, and a queue of 1: the second span has nowhere to go.
        OtlpSpanExporter x = new OtlpSpanExporter(URI.create("http://127.0.0.1:1/v1/traces"),
                Duration.ofMillis(200), Map.of(), "aegis", 1, 1, 60_000);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        List<Long> droppedOverTime = new ArrayList<>();
        while (x.droppedSpans() == 0 && System.nanoTime() < deadline) {
            x.onDecision(toolEvent("ALLOWED", 0));   // must return immediately, every time
            droppedOverTime.add(x.droppedSpans());
        }
        assertTrue(x.droppedSpans() > 0, "a full queue drops rather than blocking the caller");
        x.close();
    }

    @Test
    void anEventWithoutSpanIdentityIsIgnoredAndAnEndpointIsRequired() {
        OtlpSpanExporter x = new OtlpSpanExporter(URI.create("http://127.0.0.1:1/v1/traces"),
                Duration.ofMillis(200), null, null, 8, 2, 50);
        assertEquals(URI.create("http://127.0.0.1:1/v1/traces"), x.endpoint());
        // The 10-argument Event constructor carries no span, so there is nothing to export.
        x.onDecision(new DecisionListener.Event("r", TRACE, "m", "tool", "READ", "s", "ALLOWED", 0, true, 1));
        x.onDecision(null);
        x.close();
        assertEquals(0, x.exportedSpans());
        assertEquals(0, x.droppedSpans());
        assertThrows(IllegalArgumentException.class,
                () -> new OtlpSpanExporter(null, null, null, null, 1, 1, 1));
        assertNotNull(x.toString());
    }
}
