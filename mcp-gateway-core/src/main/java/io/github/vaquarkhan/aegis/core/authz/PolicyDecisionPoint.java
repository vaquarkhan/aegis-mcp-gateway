package io.github.vaquarkhan.aegis.core.authz;

import io.github.vaquarkhan.aegis.core.auth.CallerIdentity;
import io.github.vaquarkhan.aegis.core.spi.CallContext;
import java.util.Map;

/**
 * Step 3 of the interceptor chain. Implementations decide whether a fully identified call is
 * permitted by organisational policy.
 *
 * <p>The decision signature is {@code allows(caller, tool, args)} as in LLD section 5, so an
 * external engine such as Cedar or OPA can be handed exactly the principal, action and resource
 * triple it expects without depending on the gateway's own call carrier.
 *
 * <p>Implementations must fail closed: if the policy source cannot be read or evaluated, deny.
 *
 * @author Viquar Khan
 */
@FunctionalInterface
public interface PolicyDecisionPoint {

    boolean allows(CallerIdentity caller, String tool, Map<String, Object> args);

    /** Convenience overload used by the interceptor chain. */
    default boolean allows(CallContext ctx) {
        return ctx != null && allows(ctx.caller(), ctx.toolName(), ctx.arguments());
    }

    /** Permissive decision point used only when no policy source is configured. */
    static PolicyDecisionPoint allowAll() {
        return (caller, tool, args) -> true;
    }

    /** Deny everything. Used when a configured policy source is unusable. */
    static PolicyDecisionPoint denyAll() {
        return (caller, tool, args) -> false;
    }
}
