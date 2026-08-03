/*
 * Licensed to the Aegis MCP Gateway project under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vaquarkhan.aegis.core.observability;

import io.github.vaquarkhan.aegis.core.interceptor.Decision;
import io.github.vaquarkhan.aegis.core.interceptor.Observer;
import io.github.vaquarkhan.aegis.core.spi.CallContext;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-process counters and latency reservoirs, rendered as JSON or Prometheus text.
 *
 * <p>Denials are counted by code rather than by tool so that one alert rule covers every engine.
 *
 * @author Viquar Khan
 */
public final class Metrics implements Observer {

    private static final int RESERVOIR = 1024;

    private final ConcurrentHashMap<String, AtomicLong> allowedByTool = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> deniedByCode = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LatencyRing> latencyByTool = new ConcurrentHashMap<>();
    private final AtomicLong totalCalls = new AtomicLong();
    private final AtomicLong bytesIn = new AtomicLong();
    private final AtomicLong bytesOut = new AtomicLong();
    private final long startNanos = System.nanoTime();

    @Override
    public void onOutcome(CallContext ctx, Decision decision, long elapsedMillis) {
        if (decision.allowed()) {
            recordAllowed(ctx.toolName(), elapsedMillis);
        } else {
            recordDenied(ctx.toolName(), decision.code());
        }
    }

    public void recordAllowed(String tool, long latencyMs) {
        totalCalls.incrementAndGet();
        allowedByTool.computeIfAbsent(tool, k -> new AtomicLong()).incrementAndGet();
        latencyByTool.computeIfAbsent(tool, k -> new LatencyRing()).add(latencyMs);
    }

    public void recordDenied(String tool, String code) {
        totalCalls.incrementAndGet();
        deniedByCode.computeIfAbsent(code, k -> new AtomicLong()).incrementAndGet();
    }

    public void addBytesIn(long n) {
        bytesIn.addAndGet(n);
    }

    public void addBytesOut(long n) {
        bytesOut.addAndGet(n);
    }

    public long totalCalls() {
        return totalCalls.get();
    }

    public long allowedFor(String tool) {
        AtomicLong v = allowedByTool.get(tool);
        return v == null ? 0L : v.get();
    }

    public long deniedFor(String code) {
        AtomicLong v = deniedByCode.get(code);
        return v == null ? 0L : v.get();
    }

    public String toJson() {
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        long uptime = (System.nanoTime() - startNanos) / 1_000_000L;
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"total_calls\":").append(totalCalls.get()).append(',');
        sb.append("\"uptime_ms\":").append(uptime).append(',');
        sb.append("\"bandwidth\":{\"bytes_in\":").append(bytesIn.get())
                .append(",\"bytes_out\":").append(bytesOut.get()).append("},");
        sb.append("\"jvm\":{\"heap_used_bytes\":").append(mem.getHeapMemoryUsage().getUsed())
                .append(",\"heap_max_bytes\":").append(mem.getHeapMemoryUsage().getMax())
                .append(",\"available_processors\":").append(Runtime.getRuntime().availableProcessors())
                .append("},");
        sb.append("\"allowed_by_tool\":{");
        appendLongMap(sb, allowedByTool);
        sb.append("},\"denied_by_code\":{");
        appendLongMap(sb, deniedByCode);
        sb.append("},\"latency_ms_by_tool\":{");
        boolean first = true;
        for (Map.Entry<String, LatencyRing> e : latencyByTool.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            long[] p = e.getValue().percentiles();
            sb.append('"').append(esc(e.getKey())).append("\":{\"p50\":").append(p[0])
                    .append(",\"p95\":").append(p[1]).append(",\"p99\":").append(p[2]).append('}');
        }
        sb.append("}}");
        return sb.toString();
    }

    public String toPrometheus() {
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        StringBuilder sb = new StringBuilder();
        sb.append("# HELP aegis_mcp_calls_total Total governed tool calls\n");
        sb.append("# TYPE aegis_mcp_calls_total counter\n");
        sb.append("aegis_mcp_calls_total ").append(totalCalls.get()).append('\n');
        sb.append("# HELP aegis_mcp_bytes_in_total Bytes received from backends\n");
        sb.append("# TYPE aegis_mcp_bytes_in_total counter\n");
        sb.append("aegis_mcp_bytes_in_total ").append(bytesIn.get()).append('\n');
        sb.append("# HELP aegis_mcp_bytes_out_total Bytes sent to backends\n");
        sb.append("# TYPE aegis_mcp_bytes_out_total counter\n");
        sb.append("aegis_mcp_bytes_out_total ").append(bytesOut.get()).append('\n');
        sb.append("# HELP aegis_mcp_heap_used_bytes JVM heap used\n");
        sb.append("# TYPE aegis_mcp_heap_used_bytes gauge\n");
        sb.append("aegis_mcp_heap_used_bytes ").append(mem.getHeapMemoryUsage().getUsed()).append('\n');
        sb.append("# TYPE aegis_mcp_tool_allowed_total counter\n");
        for (Map.Entry<String, AtomicLong> e : allowedByTool.entrySet()) {
            sb.append("aegis_mcp_tool_allowed_total{tool=\"").append(esc(e.getKey())).append("\"} ")
                    .append(e.getValue().get()).append('\n');
        }
        sb.append("# TYPE aegis_mcp_denied_total counter\n");
        for (Map.Entry<String, AtomicLong> e : deniedByCode.entrySet()) {
            sb.append("aegis_mcp_denied_total{code=\"").append(esc(e.getKey())).append("\"} ")
                    .append(e.getValue().get()).append('\n');
        }
        sb.append("# TYPE aegis_mcp_tool_latency_ms summary\n");
        for (Map.Entry<String, LatencyRing> e : latencyByTool.entrySet()) {
            sb.append("aegis_mcp_tool_latency_ms{tool=\"").append(esc(e.getKey()))
                    .append("\",quantile=\"0.99\"} ").append(e.getValue().percentiles()[2]).append('\n');
        }
        return sb.toString();
    }

    private static void appendLongMap(StringBuilder sb, Map<String, AtomicLong> map) {
        boolean first = true;
        for (Map.Entry<String, AtomicLong> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(esc(e.getKey())).append("\":").append(e.getValue().get());
        }
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class LatencyRing {
        private final long[] buf = new long[RESERVOIR];
        private int size;
        private int idx;

        synchronized void add(long v) {
            buf[idx] = v;
            idx = (idx + 1) % RESERVOIR;
            if (size < RESERVOIR) {
                size++;
            }
        }

        synchronized long[] percentiles() {
            if (size == 0) {
                return new long[] {0, 0, 0};
            }
            long[] copy = Arrays.copyOf(buf, size);
            Arrays.sort(copy);
            return new long[] {percentile(copy, 0.50), percentile(copy, 0.95), percentile(copy, 0.99)};
        }

        private static long percentile(long[] sorted, double p) {
            int i = (int) Math.ceil(p * sorted.length) - 1;
            if (i < 0) {
                i = 0;
            }
            if (i >= sorted.length) {
                i = sorted.length - 1;
            }
            return sorted[i];
        }
    }
}
