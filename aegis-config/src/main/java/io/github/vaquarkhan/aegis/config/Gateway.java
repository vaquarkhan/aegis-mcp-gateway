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
package io.github.vaquarkhan.aegis.config;

import io.github.vaquarkhan.aegis.observability.otlp.OtlpSpanExporter;
import io.github.vaquarkhan.aegis.engine.GovernanceEngine;
import io.github.vaquarkhan.aegis.engine.observe.DecisionMetrics;
import io.github.vaquarkhan.aegis.mcp.admin.AdminApi;
import io.github.vaquarkhan.aegis.mcp.server.McpServerHandler;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A fully built gateway: the governance engine plus the host-neutral MCP handler and admin API.
 * Both front doors (the gateway's own server, the Knox extension) hold one of these; neither contains
 * governance logic of its own.
 */
public final class Gateway implements AutoCloseable {

    private final GovernanceEngine engine;
    private final McpServerHandler mcp;
    private final AdminApi admin;
    private final Map<String, Object> hostSections;
    private final ScheduledExecutorService refresher;
    private final DecisionMetrics metrics;
    private final OtlpSpanExporter tracer;
    private final AutoCloseable stateResource;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final java.util.function.Function<String, String> secrets;

    /**
     * The secret lookup selected by {@code secrets.source}; a host parses its own section with it so a
     * {@code tokenEnv} in {@code host} resolves from the same provider as the rest of the configuration.
     */
    public java.util.function.Function<String, String> secrets() {
        return secrets;
    }

    Gateway(GovernanceEngine engine, McpServerHandler mcp, AdminApi admin, Map<String, Object> hostSections,
            ScheduledExecutorService refresher, DecisionMetrics metrics, OtlpSpanExporter tracer, AutoCloseable stateResource,
            java.util.function.Function<String, String> secrets) {
        this.secrets = secrets;
        this.metrics = metrics;
        this.tracer = tracer;
        this.engine = engine;
        this.mcp = mcp;
        this.admin = admin;
        this.hostSections = Map.copyOf(hostSections);
        this.refresher = refresher;
        this.stateResource = stateResource;
    }

    public GovernanceEngine engine() {
        return engine;
    }

    public McpServerHandler mcp() {
        return mcp;
    }

    public AdminApi admin() {
        return admin;
    }

    /** Decision metrics ({@code metrics.enabled}, default true); null when disabled. */
    public DecisionMetrics metrics() {
        return metrics;
    }

    /** OTLP span exporter ({@code tracing.otlp.endpoint}); null when tracing is not configured. */
    public OtlpSpanExporter tracer() {
        return tracer;
    }

    /** Raw host-specific section ({@code host} or {@code knox}); the host parses it strictly. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> hostSection(String name) {
        Object v = hostSections.get(name);
        return v instanceof Map ? (Map<String, Object>) v : Map.of();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (refresher != null) {
            refresher.shutdownNow();
        }
        engine.close();
        if (tracer != null) {
            // Last, so spans for the final requests still have somewhere to go.
            tracer.close();
        }
        if (stateResource != null) {
            try {
                stateResource.close();
            } catch (Exception e) {
                throw new IllegalStateException("could not close governance state store", e);
            }
        }
    }
}
