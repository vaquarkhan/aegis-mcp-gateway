package io.github.vaquarkhan.aegis.core.spi;

/**
 * Outbound credential the gateway presents to a backend on behalf of a caller.
 *
 * <p>The value is the complete {@code Authorization} header content, for example
 * {@code Bearer eyJ...} or {@code Basic dXNlcjpwYXNz}. It must never be echoed into tool output;
 * output redaction treats bearer headers as secrets.
 *
 * @author Viquar Khan
 */
public record OutboundCredential(String authorizationHeader) {

    public OutboundCredential {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            throw new IllegalArgumentException("authorizationHeader required");
        }
    }

    /** Never render the secret. */
    @Override
    public String toString() {
        return "OutboundCredential[redacted]";
    }
}
