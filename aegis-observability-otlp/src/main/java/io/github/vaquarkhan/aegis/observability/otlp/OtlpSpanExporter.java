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

import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.spi.DecisionListener;
import io.github.vaquarkhan.aegis.mcp.http.BoundedHttp;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Exports one OpenTelemetry span per governed request over OTLP/HTTP with a JSON body.
 *
 * <p><b>Why hand-rolled.</b> The engine has no third-party dependencies so it can be dropped onto a
 * host classpath (Knox, an engine's own process) without conflicts, and pulling in the OpenTelemetry
 * SDK would put a second, heavier dependency graph in front of every deployment. OTLP over HTTP with a
 * JSON body is a documented wire format, so one span document plus an HTTP POST is the whole exporter.
 * A deployment that wants the full SDK can write its own {@link DecisionListener} instead.
 *
 * <p><b>It cannot slow down or break a request.</b> {@code onDecision} only offers the span to a bounded
 * queue and returns; a background daemon thread batches and posts. When the queue is full spans are
 * <b>dropped</b> and counted, because losing telemetry is always better than adding latency to a
 * governed call or failing one. The drop count is exported as a gauge on the Prometheus endpoint by the
 * caller if it wants it, and logged on close.
 *
 * <p><b>What is in a span.</b> Trace and span ids come from the request's W3C trace context, so the
 * span joins the caller's trace. Attributes carry the request id, the MCP method, the tool class, the
 * source, the outcome and step, and whether the backend was reached. <b>No caller identity, arguments
 * or results are exported</b>: correlate a span to the audit journal by {@code aegis.request_id} when
 * you need who and what. That keeps a tracing backend out of scope for PII review.
 *
 * <p><b>A caveat about the wire format.</b> The OTLP specification says trace and span ids are
 * hex-encoded strings in OTLP/JSON, explicitly departing from the standard protobuf JSON mapping, which
 * would base64-encode them. That divergence has been a long-standing inconsistency across the
 * OpenTelemetry SDKs, several of which emitted base64 for years. This exporter emits <b>hex</b>, per the
 * specification. A local HTTP test endpoint validates the document shape; no live collector run is
 * claimed, so confirm collector compatibility in the deployment environment.
 */
public final class OtlpSpanExporter implements DecisionListener, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(OtlpSpanExporter.class.getName());

    /** OTLP span kind SERVER: the gateway is handling an inbound request. */
    private static final long SPAN_KIND_SERVER = 2L;
    /** OTLP status UNSET / ERROR. */
    private static final long STATUS_UNSET = 0L;
    private static final long STATUS_ERROR = 2L;

    private final URI endpoint;
    private final Duration timeout;
    private final Map<String, String> headers;
    private final String serviceName;
    private final int batchSize;
    private final ArrayBlockingQueue<DecisionListener.Event> queue;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong exported = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final HttpClient http;
    private final Thread worker;
    private final long flushMillis;
    private volatile boolean running = true;

    public OtlpSpanExporter(URI endpoint, Duration timeout, Map<String, String> headers, String serviceName,
                            int maxQueue, int batchSize, long flushMillis) {
        if (endpoint == null) {
            throw new IllegalArgumentException("an OTLP exporter needs an endpoint");
        }
        this.endpoint = endpoint;
        this.timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
        this.serviceName = serviceName == null || serviceName.isBlank() ? "aegis" : serviceName;
        this.batchSize = Math.max(1, batchSize);
        this.queue = new ArrayBlockingQueue<>(Math.max(this.batchSize, maxQueue));
        this.http = HttpClient.newBuilder().connectTimeout(this.timeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
        this.flushMillis = flushMillis <= 0 ? 2000 : flushMillis;
        long flush = this.flushMillis;
        this.worker = new Thread(() -> run(flush), "aegis-otlp-exporter");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /** The endpoint, so the gateway can check it against the egress allow list before serving traffic. */
    public URI endpoint() {
        return endpoint;
    }

    public long droppedSpans() {
        return dropped.get();
    }

    public long exportedSpans() {
        return exported.get();
    }

    public long failedBatches() {
        return failed.get();
    }

    @Override
    public void onDecision(DecisionListener.Event event) {
        if (event == null || event.span() == null) {
            return; // a producer that does not track spans has nothing to export
        }
        if (!queue.offer(event)) {
            dropped.incrementAndGet();
        }
    }

    private void run(long flushMillis) {
        List<DecisionListener.Event> batch = new ArrayList<>(batchSize);
        while (running || !queue.isEmpty()) {
            try {
                DecisionListener.Event first = queue.poll(flushMillis, TimeUnit.MILLISECONDS);
                if (first != null) {
                    batch.add(first);
                    queue.drainTo(batch, batchSize - 1);
                }
                if (!batch.isEmpty()) {
                    send(batch);
                    batch.clear();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // The exporter must never die and take telemetry silently with it.
                failed.incrementAndGet();
                batch.clear();
                LOG.log(System.Logger.Level.WARNING, "OTLP export failed", e);
            }
        }
    }

    private void send(List<DecisionListener.Event> batch) {
        byte[] body = Json.writeBytes(document(batch, serviceName));
        HttpRequest.Builder b = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(b::header);
        try {
            BoundedHttp.Result r = BoundedHttp.send(http, b.build(), 64 * 1024, timeout.toMillis());
            if (r.status() >= 200 && r.status() < 300) {
                exported.addAndGet(batch.size());
            } else {
                failed.incrementAndGet();
                LOG.log(System.Logger.Level.DEBUG, () -> "OTLP collector returned HTTP " + r.status());
            }
        } catch (Exception e) {
            failed.incrementAndGet();
            LOG.log(System.Logger.Level.DEBUG, () -> "OTLP export failed: " + e.getClass().getSimpleName());
        }
    }

    /** Builds one {@code ExportTraceServiceRequest} document. Package-visible so a test can assert its shape. */
    static Map<String, Object> document(List<DecisionListener.Event> batch, String serviceName) {
        List<Object> spans = new ArrayList<>();
        for (DecisionListener.Event e : batch) {
            spans.add(span(e));
        }
        Map<String, Object> scopeSpans = new LinkedHashMap<>();
        scopeSpans.put("scope", Map.of("name", "io.github.vaquarkhan.aegis", "version", "2.0.0"));
        scopeSpans.put("spans", spans);
        Map<String, Object> resourceSpans = new LinkedHashMap<>();
        resourceSpans.put("resource", Map.of("attributes", List.of(attribute("service.name", serviceName))));
        resourceSpans.put("scopeSpans", List.of(scopeSpans));
        return Map.of("resourceSpans", List.of(resourceSpans));
    }

    private static Map<String, Object> span(DecisionListener.Event e) {
        long endMillis = e.span().startEpochMillis() + Math.max(0, e.durationMillis());
        List<Object> attrs = new ArrayList<>();
        attrs.add(attribute("aegis.request_id", e.requestId()));
        attrs.add(attribute("aegis.kind", e.kind()));
        attrs.add(attribute("aegis.outcome", e.outcome()));
        attrs.add(attribute("mcp.method.name", "tool".equals(e.kind()) ? "tools/call" : e.method()));
        if ("tool".equals(e.kind())) {
            attrs.add(attribute("aegis.tool", e.method()));
        }
        if (!e.toolClass().isEmpty()) {
            attrs.add(attribute("aegis.tool_class", e.toolClass()));
        }
        if (!e.sourceId().isEmpty()) {
            attrs.add(attribute("aegis.source", e.sourceId()));
        }
        attrs.add(intAttribute("aegis.step", e.step()));
        attrs.add(boolAttribute("aegis.reached_backend", e.reachedBackend()));

        Map<String, Object> span = new LinkedHashMap<>();
        // Hex, per the OTLP specification's deliberate departure from the protobuf JSON mapping.
        span.put("traceId", e.traceId());
        span.put("spanId", e.span().spanId());
        if (e.span().parentSpanId() != null && !e.span().parentSpanId().isEmpty()) {
            span.put("parentSpanId", e.span().parentSpanId());
        }
        span.put("name", "tool".equals(e.kind()) ? "tools/call " + e.method() : e.method());
        span.put("kind", SPAN_KIND_SERVER);
        // 64-bit values go out as strings: a JSON number would lose precision in some readers.
        span.put("startTimeUnixNano", Long.toString(e.span().startEpochMillis() * 1_000_000L));
        span.put("endTimeUnixNano", Long.toString(endMillis * 1_000_000L));
        span.put("attributes", attrs);
        span.put("status", e.allowed() ? Map.of("code", STATUS_UNSET)
                : Map.of("code", STATUS_ERROR, "message", e.outcome()));
        return span;
    }

    private static Map<String, Object> attribute(String key, String value) {
        return Map.of("key", key, "value", Map.of("stringValue", value == null ? "" : value));
    }

    private static Map<String, Object> intAttribute(String key, long value) {
        return Map.of("key", key, "value", Map.of("intValue", Long.toString(value)));
    }

    private static Map<String, Object> boolAttribute(String key, boolean value) {
        return Map.of("key", key, "value", Map.of("boolValue", value));
    }

    /**
     * Drains what is queued, then stops the worker. The worker is <b>not</b> interrupted first: an
     * interrupt lands in the queue poll and would abandon exactly the spans this is trying to flush.
     * Clearing the running flag lets the loop finish the queue and exit on its own, and the interrupt is
     * only a fallback for a worker still stuck in a send after the join deadline.
     */
    @Override
    public void close() {
        running = false;
        try {
            worker.join(timeout.toMillis() + 2 * flushMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (worker.isAlive()) {
            worker.interrupt();
        }
        if (dropped.get() > 0) {
            LOG.log(System.Logger.Level.INFO, "OTLP exporter dropped " + dropped.get()
                    + " spans because the queue was full; raise tracing.otlp.maxQueue or lower the load");
        }
    }
}
