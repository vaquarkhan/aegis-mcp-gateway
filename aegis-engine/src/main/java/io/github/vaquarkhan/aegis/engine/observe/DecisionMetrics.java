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
package io.github.vaquarkhan.aegis.engine.observe;

import io.github.vaquarkhan.aegis.engine.spi.DecisionListener;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Decision counters and a latency histogram, rendered in the Prometheus text exposition format
 * (version 0.0.4). Zero dependencies, so it can live in the engine and run inside Knox.
 *
 * <p>Series:
 * <pre>
 *   aegis_decisions_total{kind,method,source,outcome}        counter
 *   aegis_backend_calls_total{kind,source}                   counter (requests that reached a backend)
 *   aegis_decision_duration_seconds_bucket{kind,le}          histogram (+ _sum, _count)
 * </pre>
 * Label cardinality is bounded by configuration: {@code method} is an exposed tool name or one of
 * the fixed MCP methods, {@code source} is a configured source id, {@code outcome} is a stable deny
 * code. Callers and resources are deliberately NOT labels (unbounded, and personal data). Methods the
 * catalog does not know are folded into {@code method="_unknown"} so a client cannot grow the series.
 */
public final class DecisionMetrics implements DecisionListener {

    /** Upper bounds in seconds (a gateway hop plus a backend call). */
    public static final List<Double> BUCKETS = List.of(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0);
    private static final int MAX_SERIES = 10_000;

    private final Map<List<String>, LongAdder> decisions = new ConcurrentHashMap<>();
    private final Map<List<String>, LongAdder> backendCalls = new ConcurrentHashMap<>();
    private final Map<String, Histogram> durations = new ConcurrentHashMap<>();

    private static final class Histogram {
        final LongAdder[] buckets = new LongAdder[BUCKETS.size()];
        final LongAdder count = new LongAdder();
        final LongAdder sumMicros = new LongAdder();

        Histogram() {
            for (int i = 0; i < buckets.length; i++) {
                buckets[i] = new LongAdder();
            }
        }
    }

    @Override
    public void onDecision(Event e) {
        // An unknown tool is the only client-chosen label value; fold it so series stay bounded.
        String method = e.toolClass().isEmpty() && "tool".equals(e.kind()) ? "_unknown" : e.method();
        List<String> key = List.of(e.kind(), method, e.sourceId(), e.outcome());
        if (decisions.size() < MAX_SERIES || decisions.containsKey(key)) {
            decisions.computeIfAbsent(key, k -> new LongAdder()).increment();
        }
        if (e.reachedBackend()) {
            backendCalls.computeIfAbsent(List.of(e.kind(), e.sourceId()), k -> new LongAdder()).increment();
        }
        Histogram h = durations.computeIfAbsent(e.kind(), k -> new Histogram());
        double seconds = Math.max(0, e.durationMillis()) / 1000.0;
        for (int i = 0; i < BUCKETS.size(); i++) {
            if (seconds <= BUCKETS.get(i)) {
                h.buckets[i].increment();
            }
        }
        h.count.increment();
        h.sumMicros.add(Math.max(0, e.durationMillis()) * 1000);
    }

    /** Count of decisions for one outcome across all labels (for tests and health checks). */
    public long total(String outcome) {
        long n = 0;
        for (Map.Entry<List<String>, LongAdder> en : decisions.entrySet()) {
            if (en.getKey().get(3).equals(outcome)) {
                n += en.getValue().sum();
            }
        }
        return n;
    }

    /** Prometheus text exposition format 0.0.4 ({@code text/plain; version=0.0.4}). */
    public String renderPrometheus() {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("# HELP aegis_decisions_total Governance outcomes by operation, source and outcome (ALLOWED or deny code).\n");
        sb.append("# TYPE aegis_decisions_total counter\n");
        for (Map.Entry<List<String>, LongAdder> en : sorted(decisions).entrySet()) {
            List<String> k = en.getKey();
            sb.append("aegis_decisions_total{kind=\"").append(esc(k.get(0))).append("\",method=\"").append(esc(k.get(1)))
                    .append("\",source=\"").append(esc(k.get(2))).append("\",outcome=\"").append(esc(k.get(3))).append("\"} ")
                    .append(en.getValue().sum()).append('\n');
        }
        sb.append("# HELP aegis_backend_calls_total Governed requests that reached a backend.\n");
        sb.append("# TYPE aegis_backend_calls_total counter\n");
        for (Map.Entry<List<String>, LongAdder> en : sorted(backendCalls).entrySet()) {
            sb.append("aegis_backend_calls_total{kind=\"").append(esc(en.getKey().get(0))).append("\",source=\"")
                    .append(esc(en.getKey().get(1))).append("\"} ").append(en.getValue().sum()).append('\n');
        }
        sb.append("# HELP aegis_decision_duration_seconds Gateway time per governed request, backend included.\n");
        sb.append("# TYPE aegis_decision_duration_seconds histogram\n");
        for (Map.Entry<String, Histogram> en : new TreeMap<>(durations).entrySet()) {
            String kind = esc(en.getKey());
            Histogram h = en.getValue();
            for (int i = 0; i < BUCKETS.size(); i++) {
                sb.append("aegis_decision_duration_seconds_bucket{kind=\"").append(kind).append("\",le=\"")
                        .append(BUCKETS.get(i)).append("\"} ").append(h.buckets[i].sum()).append('\n');
            }
            long count = h.count.sum();
            sb.append("aegis_decision_duration_seconds_bucket{kind=\"").append(kind).append("\",le=\"+Inf\"} ").append(count).append('\n');
            sb.append("aegis_decision_duration_seconds_sum{kind=\"").append(kind).append("\"} ")
                    .append(h.sumMicros.sum() / 1_000_000.0).append('\n');
            sb.append("aegis_decision_duration_seconds_count{kind=\"").append(kind).append("\"} ").append(count).append('\n');
        }
        return sb.toString();
    }

    private static <V> TreeMap<List<String>, V> sorted(Map<List<String>, V> m) {
        TreeMap<List<String>, V> t = new TreeMap<>((a, b) -> String.join("\u0000", a).compareTo(String.join("\u0000", b)));
        t.putAll(m);
        return t;
    }

    /** Label value escaping per the exposition format: backslash, double quote and newline. */
    static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
