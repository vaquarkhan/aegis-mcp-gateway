package io.github.vaquarkhan.aegis.core.auth;

import java.util.Optional;

/**
 * Thread-local caller identity set by the inbound auth filter and propagated onto backend worker
 * threads by the timeout executor.
 *
 * @author Viquar Khan
 */
public final class CallerContext {

    private static final ThreadLocal<CallerIdentity> CURRENT = new ThreadLocal<>();

    private CallerContext() {}

    public static void set(CallerIdentity identity) {
        CURRENT.set(identity);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static Optional<CallerIdentity> current() {
        return Optional.ofNullable(CURRENT.get());
    }
}
