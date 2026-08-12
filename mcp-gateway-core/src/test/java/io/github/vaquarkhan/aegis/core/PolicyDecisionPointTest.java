package io.github.vaquarkhan.aegis.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.core.auth.CallerIdentity;
import io.github.vaquarkhan.aegis.core.authz.BuiltinPolicyEngine;
import io.github.vaquarkhan.aegis.core.authz.CedarPdp;
import io.github.vaquarkhan.aegis.core.authz.OpaPdp;
import io.github.vaquarkhan.aegis.core.authz.PolicyDecisionPoint;
import io.github.vaquarkhan.aegis.core.spi.CallContext;
import io.github.vaquarkhan.aegis.core.spi.ToolClass;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * @author Viquar Khan
 */
class PolicyDecisionPointTest {

    private static CallerIdentity ops() {
        return new CallerIdentity("ops", Set.of("*"), false);
    }

    private static CallContext ctx(String tool, Map<String, Object> args) {
        return CallContext.of(tool, ToolClass.READ, args, ops(), "trace-0");
    }

    @Test
    void callContextOverloadDelegatesToTheLldSignature() {
        PolicyDecisionPoint pdp = BuiltinPolicyEngine.parse(List.of("deny tool list_*"));
        assertFalse(pdp.allows(ctx("list_jobs", Map.of())));
        assertFalse(pdp.allows(ops(), "list_jobs", Map.of()));
        assertTrue(pdp.allows(ops(), "get_job", Map.of()));
    }

    @Test
    void builtinEngineDeniesByToolCallerAndResource() {
        PolicyDecisionPoint pdp = BuiltinPolicyEngine.parse(List.of(
                "deny caller robot-*",
                "deny resource prod.*"));
        assertTrue(pdp.allows(ops(), "get_job", Map.of("table", "dev.events")));
        assertFalse(pdp.allows(ops(), "get_job", Map.of("table", "prod.events")));
        assertFalse(pdp.allows(
                new CallerIdentity("robot-7", Set.of("*"), true), "get_job", Map.of()));
    }

    @Test
    void unreadablePolicyFileFailsClosed() {
        BuiltinPolicyEngine engine = BuiltinPolicyEngine.load("no/such/policy.rules");
        assertTrue(engine.failClosed());
        assertFalse(engine.allows(ops(), "get_job", Map.of()));
    }

    @Test
    void cedarLiteAndOpaFailClosedWithoutLiveBackend() {
        assertFalse(new CedarPdp("policy.cedar").allows(ops(), "list_jobs", Map.of()));
        assertFalse(new OpaPdp("http://127.0.0.1:9/v1/data/aegis/allow")
                .allows(ops(), "list_jobs", Map.of()));
        assertFalse(new CedarPdp(null).allows(ctx("list_jobs", Map.of())));
    }

    @Test
    void cedarLiteDenyFileAllowsUnlessMatched() throws Exception {
        Path file = Files.createTempFile("cedar-lite", ".rules");
        Files.writeString(file, "deny robot-* *\n");
        try {
            CedarPdp pdp = new CedarPdp(file.toString());
            assertTrue(pdp.allows(ops(), "list_jobs", Map.of()));
            assertFalse(pdp.allows(
                    new CallerIdentity("robot-7", Set.of("*"), true), "list_jobs", Map.of()));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void nullContextIsDeniedEvenByAPermissiveEngine() {
        assertFalse(PolicyDecisionPoint.allowAll().allows(null));
        assertTrue(PolicyDecisionPoint.allowAll().allows(ops(), "anything", Map.of()));
    }
}
