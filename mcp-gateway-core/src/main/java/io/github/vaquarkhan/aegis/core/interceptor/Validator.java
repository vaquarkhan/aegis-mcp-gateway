package io.github.vaquarkhan.aegis.core.interceptor;

import io.github.vaquarkhan.aegis.core.spi.CallContext;

/**
 * Interceptor that only inspects a call and returns a decision. This is the shape of every step
 * from 1 through 9.
 *
 * @author Viquar Khan
 */
public interface Validator extends Interceptor {

    @Override
    default Phase phase() {
        return Phase.VALIDATION;
    }

    /** LLD section 4 name for {@link #apply(CallContext)}. */
    default Decision validate(CallContext ctx) {
        return apply(ctx);
    }

    /** Convenience factory for lambda based validators. */
    static Validator of(String name, int step, java.util.function.Function<CallContext, Decision> fn) {
        return new Validator() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public int step() {
                return step;
            }

            @Override
            public Decision apply(CallContext ctx) {
                return fn.apply(ctx);
            }
        };
    }
}
