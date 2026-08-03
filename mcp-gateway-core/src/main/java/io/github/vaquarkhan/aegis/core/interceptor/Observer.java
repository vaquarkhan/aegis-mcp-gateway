package io.github.vaquarkhan.aegis.core.interceptor;

import io.github.vaquarkhan.aegis.core.spi.CallContext;

/**
 * Telemetry sink invoked once per call after the decision is final. Observers cannot change the
 * outcome, and an observer that throws never fails a call that governance already allowed.
 *
 * @author Viquar Khan
 */
@FunctionalInterface
public interface Observer {

    void onOutcome(CallContext ctx, Decision decision, long elapsedMillis);

    /** LLD section 4 name, for observers that do not care about latency. */
    default void observe(CallContext ctx, Decision decision) {
        onOutcome(ctx, decision, 0L);
    }

    default Phase phase() {
        return Phase.OBSERVATION;
    }
}
