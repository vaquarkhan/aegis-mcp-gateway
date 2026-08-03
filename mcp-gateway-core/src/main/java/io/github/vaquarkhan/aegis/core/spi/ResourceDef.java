package io.github.vaquarkhan.aegis.core.spi;

import java.util.function.Function;

/**
 * Engine contributed MCP resource. Read-only by construction.
 *
 * @author Viquar Khan
 */
public record ResourceDef(
        String uri,
        String name,
        String mimeType,
        Function<CallContext, String> read,
        boolean redact) {

    public ResourceDef {
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("resource uri required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("resource name required for " + uri);
        }
        if (read == null) {
            throw new IllegalArgumentException("read function required for " + uri);
        }
        mimeType = (mimeType == null || mimeType.isBlank()) ? "text/plain" : mimeType;
    }

    public static ResourceDef json(String uri, String name, Function<CallContext, String> read) {
        return new ResourceDef(uri, name, "application/json", read, true);
    }

    public static ResourceDef text(String uri, String name, Function<CallContext, String> read) {
        return new ResourceDef(uri, name, "text/plain", read, true);
    }
}
