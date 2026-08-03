package io.github.vaquarkhan.aegis.core.interceptor;

import io.github.vaquarkhan.aegis.core.spi.CallContext;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Inbound sanitizing mutator: trims surrounding whitespace and strips control characters from every
 * string argument.
 *
 * <p>This runs before any validator so that scope, approval and injection checks see the same bytes
 * the backend will see. A trailing newline smuggled into a job id must not be able to pass a scope
 * check and then reach the engine as a different value.
 *
 * @author Viquar Khan
 */
public final class ArgumentSanitizeMutator implements Mutator {

    @Override
    public String name() {
        return "argument-sanitize";
    }

    @Override
    public int step() {
        return 0;
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public CallContext mutate(CallContext ctx) {
        if (ctx == null || ctx.arguments().isEmpty()) {
            return ctx;
        }
        Map<String, Object> cleaned = new LinkedHashMap<>();
        boolean changed = false;
        for (Map.Entry<String, Object> e : ctx.arguments().entrySet()) {
            Object value = e.getValue();
            if (value instanceof String s) {
                String sanitized = sanitize(s);
                changed |= !sanitized.equals(s);
                cleaned.put(e.getKey(), sanitized);
            } else {
                cleaned.put(e.getKey(), value);
            }
        }
        return changed ? ctx.withArguments(cleaned) : ctx;
    }

    static String sanitize(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            // Tab, carriage return and newline are legitimate inside SQL bodies; other control
            // characters have no place in a tool argument.
            if (c >= 0x20 || c == '\t' || c == '\r' || c == '\n') {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }
}
