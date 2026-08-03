package io.github.vaquarkhan.aegis.core.observability;

import io.github.vaquarkhan.aegis.core.interceptor.Decision;
import io.github.vaquarkhan.aegis.core.interceptor.Observer;
import io.github.vaquarkhan.aegis.core.spi.CallContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Emits OpenTelemetry GenAI-shaped span attributes as structured log lines.
 *
 * <p>No OTel SDK is bundled in 0.1.x: collectors that scrape application logs (or a later OTel
 * bridge) can map {@code gen_ai.*} keys. This is not a substitute for a full GenAI tracer, but it
 * puts allow/deny outcomes on the execute-tool path with stable attribute names.
 *
 * @author Viquar Khan
 */
public final class GenAiSpanObserver implements Observer {

    private static final Logger LOG = LoggerFactory.getLogger(GenAiSpanObserver.class);

    @Override
    public void onOutcome(CallContext ctx, Decision decision, long elapsedMillis) {
        String tool = ctx == null || ctx.toolName() == null ? "-" : ctx.toolName();
        String callId = ctx == null || ctx.traceId() == null ? "-" : ctx.traceId();
        String outcome = decision == null ? "-" : decision.auditOutcome();
        int step = decision == null ? -1 : decision.step();
        LOG.info(
                "span=gen_ai.execute_tool gen_ai.operation.name=execute_tool gen_ai.tool.name={} "
                        + "gen_ai.tool.call.id={} gen_ai.gateway.decision={} gen_ai.gateway.step={} "
                        + "duration_ms={}",
                tool, callId, outcome, step, elapsedMillis);
    }
}
