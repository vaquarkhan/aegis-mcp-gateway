package io.github.vaquarkhan.aegis.core.interceptor;

import io.github.vaquarkhan.aegis.core.spi.CallContext;

/**
 * One governance step. Implementations must be side-effect free apart from their own accounting,
 * because the chain may stop before later steps run.
 *
 * @author Viquar Khan
 */
public interface Interceptor {

    String name();

    default Phase phase() {
        return Phase.VALIDATION;
    }

    /** Relative order within a phase, lowest first. Ties keep registration order. */
    default int priority() {
        return 100;
    }

    /** Step number this interceptor reports on denial. */
    int step();

    Decision apply(CallContext ctx);
}
