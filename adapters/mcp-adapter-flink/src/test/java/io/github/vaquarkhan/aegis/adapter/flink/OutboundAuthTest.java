package io.github.vaquarkhan.aegis.adapter.flink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.vaquarkhan.aegis.adapter.flink.client.OutboundAuth;
import io.github.vaquarkhan.aegis.core.spi.CallContext;
import io.github.vaquarkhan.aegis.core.spi.OutboundCredential;
import io.github.vaquarkhan.aegis.core.spi.ToolClass;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** @author Viquar Khan */
class OutboundAuthTest {

    private static CallContext ctx(Optional<OutboundCredential> credential) {
        return new CallContext("list_jobs", ToolClass.READ, Map.of(), null, "test-trace", credential);
    }

    @Test
    void bareTokenBecomesBearer() {
        assertEquals("Bearer abc", OutboundAuth.toAuthorizationValue("abc"));
    }

    @Test
    void schemedHeaderIsPassedThrough() {
        assertEquals("Basic dXNlcjpwdw==", OutboundAuth.toAuthorizationValue("Basic dXNlcjpwdw=="));
    }

    @Test
    void blankHeaderResolvesToNull() {
        assertNull(OutboundAuth.toAuthorizationValue(null));
        assertNull(OutboundAuth.toAuthorizationValue("   "));
    }

    @Test
    void perCallCredentialWinsOverStaticFallback() {
        String resolved = OutboundAuth.withCallContext(
                ctx(Optional.of(new OutboundCredential("Bearer per-call"))),
                () -> OutboundAuth.resolveFlink("static"));
        assertEquals("Bearer per-call", resolved);
    }

    @Test
    void staticFallbackAppliesWithoutPerCallCredential() {
        String resolved = OutboundAuth.withCallContext(
                ctx(Optional.empty()),
                () -> OutboundAuth.resolveGateway("static"));
        assertEquals("static", resolved);
    }

    @Test
    void bindingIsClearedAfterTheCall() {
        OutboundAuth.withCallContext(
                ctx(Optional.of(new OutboundCredential("Bearer per-call"))),
                () -> OutboundAuth.resolveFlink(null));
        assertNull(OutboundAuth.resolveFlink(null));
    }
}
