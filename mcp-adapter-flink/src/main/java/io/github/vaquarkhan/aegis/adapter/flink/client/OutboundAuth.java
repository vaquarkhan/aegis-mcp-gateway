package io.github.vaquarkhan.aegis.adapter.flink.client;

import io.github.vaquarkhan.aegis.core.auth.CallerContext;
import io.github.vaquarkhan.aegis.core.auth.CallerIdentity;
import io.github.vaquarkhan.aegis.core.spi.CallContext;
import io.github.vaquarkhan.aegis.core.spi.OutboundCredential;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the Authorization value sent to Flink REST and the Flink SQL Gateway.
 *
 * <p>Resolution order is the per-call {@link OutboundCredential} carried by the current
 * {@link CallContext} (bound by the tool factory for the duration of a backend invocation),
 * then the static server-wide header from adapter configuration. The gateway core owns
 * credential resolution; this class only applies whatever the core handed down.
 *
 * @author Viquar Khan
 */
public final class OutboundAuth {

    private static final Logger LOG = LoggerFactory.getLogger(OutboundAuth.class);

    private static final ThreadLocal<String> BOUND = new ThreadLocal<>();

    private OutboundAuth() {}

    /**
     * Runs {@code body} with the outbound credential of {@code ctx} bound to the current thread.
     * Backends run on the gateway backend pool, so the binding is always cleared afterwards.
     */
    public static <T> T withCallContext(CallContext ctx, Supplier<T> body) {
        String previous = BOUND.get();
        bind(ctx);
        try {
            return body.get();
        } finally {
            if (previous == null) {
                BOUND.remove();
            } else {
                BOUND.set(previous);
            }
        }
    }

    public static String resolveFlink(String staticFallback) {
        return resolve(staticFallback);
    }

    public static String resolveGateway(String staticFallback) {
        return resolve(staticFallback);
    }

    /** Normalize to a full {@code Authorization} header value. */
    public static String toAuthorizationValue(String authHeader) {
        if (!present(authHeader)) {
            return null;
        }
        int sp = authHeader.indexOf(' ');
        if (sp > 0) {
            return authHeader;
        }
        return "Bearer " + authHeader;
    }

    private static void bind(CallContext ctx) {
        String header = null;
        if (ctx != null && ctx.outboundCredential() != null) {
            header = ctx.outboundCredential()
                    .map(OutboundCredential::authorizationHeader)
                    .filter(OutboundAuth::present)
                    .orElse(null);
        }
        if (header == null) {
            BOUND.remove();
            Optional<CallerIdentity> caller = CallerContext.current();
            if (caller.isPresent()) {
                LOG.debug("no outbound credential bound for current caller; using static adapter credential");
            }
        } else {
            BOUND.set(header);
        }
    }

    private static String resolve(String staticFallback) {
        String bound = BOUND.get();
        return present(bound) ? bound : blankToNull(staticFallback);
    }

    private static boolean present(String v) {
        return v != null && !v.isBlank();
    }

    private static String blankToNull(String v) {
        return present(v) ? v : null;
    }
}
