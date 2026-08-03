package io.github.vaquarkhan.aegis.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.core.auth.CallerIdentity;
import io.github.vaquarkhan.aegis.core.integrity.VrpValidator;
import io.github.vaquarkhan.aegis.core.spi.CallContext;
import io.github.vaquarkhan.aegis.core.spi.ToolClass;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * @author Viquar Khan
 */
class VrpValidatorTest {

    private static CallerIdentity ops() {
        return new CallerIdentity("ops", Set.of("*"), false);
    }

    private static CallContext ctx(Map<String, Object> args) {
        return new CallContext("expire_snapshots", ToolClass.DESTRUCTIVE, args, ops(), "t1", Optional.empty());
    }

    @Test
    void signedReceiptsUseHmacPrefix() {
        VrpValidator vrp = new VrpValidator(true, 60_000L, "vrp-signing-secret");
        assertTrue(vrp.signedReceipts());
        Map<String, Object> dry = new HashMap<>();
        dry.put("table", "analytics_gold");
        dry.put("dryRun", true);
        String id = vrp.recordDryRun(ctx(dry));
        assertTrue(id.startsWith("vrp1."));

        Map<String, Object> promote = new HashMap<>();
        promote.put("table", "analytics_gold");
        promote.put("dryRunReceipt", id);
        assertTrue(vrp.verifyReceipt(ctx(promote)));
        assertFalse(vrp.verifyReceipt(ctx(promote)));
    }

    @Test
    void forgedSignedReceiptIsRejected() {
        VrpValidator vrp = new VrpValidator(true, 60_000L, "vrp-signing-secret");
        Map<String, Object> args = Map.of(
                "table", "analytics_gold",
                "dryRunReceipt", "vrp1.not-a-real-mac");
        assertFalse(vrp.verifyReceipt(ctx(args)));
    }

    @Test
    void unsignedModeStillIssuesOpaqueReceipts() {
        VrpValidator vrp = new VrpValidator(true, 60_000L);
        assertFalse(vrp.signedReceipts());
        Map<String, Object> dry = Map.of("table", "t", "dryRun", true);
        String id = vrp.recordDryRun(ctx(dry));
        assertFalse(id.startsWith("vrp1."));
        assertEquals(1, vrp.outstandingReceipts());
        assertTrue(vrp.verifyReceipt(ctx(Map.of("table", "t", "dryRunReceipt", id))));
    }
}
