package io.github.vaquarkhan.aegis.core.transport;

import io.github.vaquarkhan.aegis.core.observability.AuditLog;
import io.github.vaquarkhan.aegis.core.observability.Metrics;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Operational endpoints: {@code /healthz}, {@code /readyz}, {@code /metrics} and
 * {@code /audit/verify}.
 *
 * <p>These sit outside the MCP authentication filter so an orchestrator can probe the process
 * without holding a bearer token. Nothing sensitive is exposed: liveness and readiness are
 * booleans, metrics are counters, and audit verify returns only chain integrity.
 *
 * @author Viquar Khan
 */
public final class OpsServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private final transient Metrics metrics;
    private final transient BooleanSupplier readyCheck;
    private final transient AuditLog audit;
    private final AtomicBoolean live = new AtomicBoolean(true);

    public OpsServlet(Metrics metrics, BooleanSupplier readyCheck) {
        this(metrics, readyCheck, null);
    }

    public OpsServlet(Metrics metrics, BooleanSupplier readyCheck, AuditLog audit) {
        this.metrics = metrics == null ? new Metrics() : metrics;
        this.readyCheck = readyCheck == null ? () -> true : readyCheck;
        this.audit = audit;
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
            case "/audit/verify" -> {
                if (audit == null) {
                    write(resp, 200, "application/json",
                            "{\"ok\":true,\"durable\":false,\"note\":\"in-memory only\"}");
                } else {
                    boolean ok = audit.verifyDurableChain();
                    boolean durable = audit.durableFile() != null;
                    write(resp, ok ? 200 : 503, "application/json",
                            "{\"ok\":" + ok + ",\"durable\":" + durable + ",\"size\":" + audit.size() + "}");
                }
            }
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
