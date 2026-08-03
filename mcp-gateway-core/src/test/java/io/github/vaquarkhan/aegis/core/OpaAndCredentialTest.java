package io.github.vaquarkhan.aegis.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.core.auth.CallerIdentity;
import io.github.vaquarkhan.aegis.core.authz.OpaPdp;
import io.github.vaquarkhan.aegis.core.spi.PassThroughCredentialResolver;
import io.github.vaquarkhan.aegis.core.spi.OutboundCredential;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * @author Viquar Khan
 */
class OpaAndCredentialTest {

    @Test
    void opaParsesBooleanResult() {
        assertTrue(OpaPdp.parseAllow("{\"result\":true}"));
        assertTrue(OpaPdp.parseAllow("{\"result\":{\"allow\":true}}"));
        assertFalse(OpaPdp.parseAllow("{\"result\":false}"));
        assertFalse(OpaPdp.parseAllow(""));
    }

    @Test
    void opaBuildInputIncludesPrincipalAndAction() {
        CallerIdentity caller = new CallerIdentity("ops", Set.of("*"), false);
        String body = OpaPdp.buildInput(caller, "list_jobs", Map.of());
        assertTrue(body.contains("\"id\":\"ops\""));
        assertTrue(body.contains("\"name\":\"list_jobs\""));
    }

    @Test
    void opaWithoutHttpUrlDenies() {
        assertFalse(new OpaPdp("file:///tmp/policy.rego").allows(
                new CallerIdentity("ops", Set.of("*"), false), "list_jobs", Map.of()));
        assertFalse(new OpaPdp(null).allows(
                new CallerIdentity("ops", Set.of("*"), false), "list_jobs", Map.of()));
    }

    @Test
    void passThroughPropagatesOutboundHeader() {
        CallerIdentity with = new CallerIdentity("ops", Set.of("*"), false)
                .withOutboundAuth("Bearer caller-token");
        Optional<OutboundCredential> resolved =
                PassThroughCredentialResolver.INSTANCE.resolve(with, "flink");
        assertTrue(resolved.isPresent());
        assertEquals("Bearer caller-token", resolved.get().authorizationHeader());

        CallerIdentity bare = new CallerIdentity("ops", Set.of("*"), false);
        assertTrue(PassThroughCredentialResolver.INSTANCE.resolve(bare, "flink").isEmpty());
    }
}
