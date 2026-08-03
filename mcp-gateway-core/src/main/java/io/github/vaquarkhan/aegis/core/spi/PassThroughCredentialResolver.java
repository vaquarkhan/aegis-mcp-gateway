package io.github.vaquarkhan.aegis.core.spi;

import io.github.vaquarkhan.aegis.core.auth.CallerIdentity;
import java.util.Optional;

/**
 * Default outbound credential resolver: propagates the caller's {@code outboundAuthHeader} so
 * downstream engines see a per-caller Authorization value when one was bound at admission
 * (tokenfile column, OAuth exchange result, or {@link CallerIdentity#withOutboundAuth}).
 *
 * <p>When the caller has no outbound header, returns empty so the adapter falls back to its
 * shared service credential. That preserves fail-open-to-service-identity for deployments that
 * have not configured per-caller headers, without inventing credentials.
 *
 * @author Viquar Khan
 */
public final class PassThroughCredentialResolver implements CredentialResolver {

    public static final PassThroughCredentialResolver INSTANCE = new PassThroughCredentialResolver();

    private PassThroughCredentialResolver() {}

    @Override
    public Optional<OutboundCredential> resolve(CallerIdentity caller, String resource) {
        if (caller == null) {
            return Optional.empty();
        }
        String header = caller.outboundAuthHeader();
        if (header == null || header.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new OutboundCredential(header));
    }
}
