package io.github.vaquarkhan.aegis.core.governance;

import io.github.vaquarkhan.aegis.core.auth.CallerIdentity;
import io.github.vaquarkhan.aegis.core.spi.CallContext;
import java.util.List;
import java.util.Map;

/**
 * Step 2 resource scoping. Extracts the resource a call targets and checks it against the caller's
 * allow lists.
 *
 * <p>Engine specific argument names are normalised here so the rest of the gateway stays
 * engine agnostic. The first argument key present wins, in the order below. Job and jar targets
 * are checked against the caller's dedicated allow lists per LLD section 5; every other resource
 * goes through the general scope set.
 *
 * @author Viquar Khan
 */
public final class Scope {

    /** Argument names, most specific first, that identify the resource a call targets. */
    private static final List<String> RESOURCE_KEYS = List.of(
            "resource",
            "jobId",
            "jarId",
            "applicationId",
            "topic",
            "consumerGroup",
            "table",
            "namespace",
            "database",
            "catalog",
            "cluster");

    private Scope() {}

    /** The resource this call targets, or {@code null} when the call is not resource scoped. */
    public static String resourceOf(CallContext ctx) {
        return ctx == null ? null : resourceOf(ctx.arguments());
    }

    /** The resource a raw argument map targets, or {@code null} when the call is not scoped. */
    public static String resourceOf(Map<String, Object> arguments) {
        if (arguments == null) {
            return null;
        }
        for (String key : RESOURCE_KEYS) {
            String v = str(arguments.get(key));
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /** Scope key used for approval token binding. Unscoped calls bind to the wildcard. */
    public static String approvalScopeOf(CallContext ctx) {
        String resource = resourceOf(ctx);
        return resource == null ? "*" : resource;
    }

    /** True when the caller is permitted to touch the resource this call targets. */
    public static boolean allowed(CallerIdentity caller, CallContext ctx) {
        if (caller == null) {
            return false;
        }
        if (ctx == null) {
            return true;
        }
        String jobId = ctx.arg("jobId");
        if (jobId != null) {
            return caller.jobAllowed(jobId);
        }
        String jarId = ctx.arg("jarId");
        if (jarId != null) {
            return caller.jarAllowed(jarId);
        }
        return caller.scopeAllowed(resourceOf(ctx));
    }

    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v);
        return s.isBlank() ? null : s;
    }
}
