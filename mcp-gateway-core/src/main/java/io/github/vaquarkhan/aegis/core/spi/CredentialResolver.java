package io.github.vaquarkhan.aegis.core.spi;

import io.github.vaquarkhan.aegis.core.auth.CallerIdentity;
import java.util.Optional;

/**
 * On-behalf-of credential exchange. Lets the gateway present a caller specific outbound credential
 * instead of a single shared service account.
 *
 * <p>An empty result means "no caller specific credential", not "deny". Denial is a policy concern.
 *
 * @author Viquar Khan
 */
@FunctionalInterface
public interface CredentialResolver {

    Optional<OutboundCredential> resolve(CallerIdentity caller, String resource);
}
