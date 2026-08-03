package io.github.vaquarkhan.aegis.core.interceptor;

import io.github.vaquarkhan.aegis.core.spi.CallContext;

/**
 * Interceptor that may rewrite the call context, for example to sanitize arguments or to attach a
 * resolved outbound credential. Mutators run first and atomically, before any validator, and they
 * cannot deny; a mutator that wants to deny must also be registered as a {@link Validator}.
 *
 * @author Viquar Khan
 */
public interface Mutator extends Interceptor {

    CallContext mutate(CallContext ctx);

    @Override
    default Decision apply(CallContext ctx) {
        return Decision.allow();
    }

    @Override
    default Phase phase() {
        return Phase.MUTATION;
    }

    static Mutator of(String name, int step, java.util.function.UnaryOperator<CallContext> fn) {
        return new Mutator() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public int step() {
                return step;
            }

            @Override
            public CallContext mutate(CallContext ctx) {
                return fn.apply(ctx);
            }
        };
    }
}
