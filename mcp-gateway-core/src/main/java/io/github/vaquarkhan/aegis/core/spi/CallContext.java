package io.github.vaquarkhan.aegis.core.spi;

import io.github.vaquarkhan.aegis.core.auth.CallerIdentity;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable per-call carrier handed to interceptors and to the adapter backend function.
 *
 * @author Viquar Khan
 */
public record CallContext(
        String toolName,
        ToolClass cls,
        Map<String, Object> arguments,
        CallerIdentity caller,
        String traceId,
        Optional<OutboundCredential> outboundCredential) {

    public CallContext {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName required");
        }
        if (cls == null) {
            throw new IllegalArgumentException("cls required");
        }
        arguments = arguments == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
        outboundCredential = outboundCredential == null ? Optional.empty() : outboundCredential;
    }

    public static CallContext of(
            String toolName, ToolClass cls, Map<String, Object> arguments, CallerIdentity caller, String traceId) {
        return new CallContext(toolName, cls, arguments, caller, traceId, Optional.empty());
    }

    /** Returns a copy carrying the resolved outbound credential. */
    public CallContext withOutboundCredential(Optional<OutboundCredential> credential) {
        return new CallContext(toolName, cls, arguments, caller, traceId, credential);
    }

    /** Returns a copy carrying replacement arguments (used by mutators). */
    public CallContext withArguments(Map<String, Object> replacement) {
        return new CallContext(toolName, cls, replacement, caller, traceId, outboundCredential);
    }

    /** Argument as a string, or {@code null} when absent or blank. */
    public String arg(String key) {
        Object v = arguments.get(key);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v);
        return s.isBlank() ? null : s;
    }

    /** Argument as a string, or {@code fallback} when absent. */
    public String argOr(String key, String fallback) {
        String v = arg(key);
        return v == null ? fallback : v;
    }

    /** Argument as a boolean, {@code false} when absent or not parseable as true. */
    public boolean argBool(String key) {
        Object v = arguments.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(v));
    }
}
