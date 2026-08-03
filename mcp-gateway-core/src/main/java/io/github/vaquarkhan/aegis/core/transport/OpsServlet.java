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
package io.github.vaquarkhan.aegis.core.transport;

import io.github.vaquarkhan.aegis.core.observability.Metrics;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Operational endpoints: {@code /healthz}, {@code /readyz} and {@code /metrics}.
 *
 * <p>These sit outside the MCP authentication filter so an orchestrator can probe the process
 * without holding a bearer token. Nothing sensitive is exposed: liveness and readiness are
 * booleans, and the metrics surface is counters and latencies only.
 *
 * @author Viquar Khan
 */
public final class OpsServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private final transient Metrics metrics;
    private final transient BooleanSupplier readyCheck;
    private final AtomicBoolean live = new AtomicBoolean(true);

    public OpsServlet(Metrics metrics, BooleanSupplier readyCheck) {
        this.metrics = metrics == null ? new Metrics() : metrics;
        this.readyCheck = readyCheck == null ? () -> true : readyCheck;
    }

    /** Marks the process as not live, used by the shutdown hook to fail probes during drain. */
    public void markNotLive() {
        live.set(false);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getServletPath();
        switch (path) {
            case "/healthz" -> write(resp, live.get() ? 200 : 503, "application/json",
                    live.get() ? "{\"status\":\"UP\"}" : "{\"status\":\"DOWN\"}");
            case "/readyz" -> {
                boolean ready = live.get() && readyCheck.getAsBoolean();
                write(resp, ready ? 200 : 503, "application/json",
                        ready ? "{\"status\":\"READY\"}" : "{\"status\":\"NOT_READY\"}");
            }
            case "/metrics" -> write(resp, 200, "text/plain; version=0.0.4; charset=utf-8",
                    metrics.toPrometheus());
            default -> resp.setStatus(404);
        }
    }

    private static void write(HttpServletResponse resp, int code, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        resp.setStatus(code);
        resp.setContentType(type);
        resp.setContentLength(bytes.length);
        resp.getOutputStream().write(bytes);
    }
}
