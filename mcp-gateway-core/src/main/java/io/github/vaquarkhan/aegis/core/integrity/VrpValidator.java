package io.github.vaquarkhan.aegis.core.integrity;

import io.github.vaquarkhan.aegis.core.spi.CallContext;
import io.github.vaquarkhan.aegis.core.spi.ToolClass;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Step 9. Validate-run-promote for destructive operations.
 *
 * <p>A destructive call must be preceded by an identical call with {@code dryRun=true}. The dry run
 * returns a receipt bound to the caller, the tool and a fingerprint of the arguments; the real run
 * must present that receipt. When a signing secret is configured, the receipt id is an HMAC so a
 * forged identifier cannot pass verification even if the in-memory map is empty (fail-closed).
 *
 * @author Viquar Khan
 */
public final class VrpValidator {

    public static final String ARG_DRY_RUN = "dryRun";
    public static final String ARG_RECEIPT = "dryRunReceipt";

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final boolean enabled;
    private final long receiptTtlMillis;
    private final byte[] signingKey;
    private final SecureRandom rng = new SecureRandom();
    private final ConcurrentHashMap<String, Receipt> receipts = new ConcurrentHashMap<>();

    public VrpValidator(boolean enabled, long receiptTtlMillis) {
        this(enabled, receiptTtlMillis, null);
    }

    public VrpValidator(boolean enabled, long receiptTtlMillis, String signingSecret) {
        this.enabled = enabled;
        this.receiptTtlMillis = receiptTtlMillis <= 0 ? 900_000L : receiptTtlMillis;
        this.signingKey = (signingSecret == null || signingSecret.isBlank())
                ? null
                : signingSecret.getBytes(StandardCharsets.UTF_8);
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean signedReceipts() {
        return signingKey != null;
    }

    /** Only destructive tools are subject to validate-run-promote. */
    public boolean applies(ToolClass cls) {
        return enabled && cls == ToolClass.DESTRUCTIVE;
    }

    /** True when this call is the dry-run phase. */
    public static boolean isDryRun(CallContext ctx) {
        return ctx.argBool(ARG_DRY_RUN);
    }

    /** Issues a receipt for a completed dry run and returns its identifier. */
    public String recordDryRun(CallContext ctx) {
        String caller = ctx.caller() == null ? "-" : ctx.caller().callerId();
        String fp = fingerprint(ctx);
        long expiresAt = System.currentTimeMillis() + receiptTtlMillis;
        String id;
        if (signingKey == null) {
            byte[] raw = new byte[12];
            rng.nextBytes(raw);
            id = B64.encodeToString(raw);
        } else {
            id = "vrp1." + B64.encodeToString(hmac((caller + "|" + ctx.toolName() + "|" + fp + "|" + expiresAt)
                    .getBytes(StandardCharsets.UTF_8)));
        }
        receipts.put(id, new Receipt(caller, ctx.toolName(), fp, expiresAt));
        return id;
    }

    /** Verifies and consumes the receipt presented by a destructive call. */
    public boolean verifyReceipt(CallContext ctx) {
        if (!applies(ctx.cls())) {
            return true;
        }
        if (isDryRun(ctx)) {
            return true;
        }
        String id = ctx.arg(ARG_RECEIPT);
        if (id == null) {
            return false;
        }
        evictExpired();
        Receipt r = receipts.get(id);
        if (r == null || r.expiresAt < System.currentTimeMillis()) {
            receipts.remove(id);
            return false;
        }
        String caller = ctx.caller() == null ? "-" : ctx.caller().callerId();
        if (!r.callerId.equals(caller) || !r.toolName.equals(ctx.toolName())) {
            return false;
        }
        if (!r.fingerprint.equals(fingerprint(ctx))) {
            return false;
        }
        if (signingKey != null && id.startsWith("vrp1.")) {
            String expected = "vrp1." + B64.encodeToString(hmac((r.callerId + "|" + r.toolName + "|"
                    + r.fingerprint + "|" + r.expiresAt).getBytes(StandardCharsets.UTF_8)));
            if (!MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8), id.getBytes(StandardCharsets.UTF_8))) {
                return false;
            }
        }
        receipts.remove(id);
        return true;
    }

    public int outstandingReceipts() {
        evictExpired();
        return receipts.size();
    }

    public static String fingerprint(CallContext ctx) {
        Map<String, Object> sorted = new TreeMap<>(ctx.arguments());
        sorted.remove(ARG_DRY_RUN);
        sorted.remove(ARG_RECEIPT);
        sorted.remove("approvalToken");
        StringBuilder sb = new StringBuilder(ctx.toolName());
        for (Map.Entry<String, Object> e : sorted.entrySet()) {
            sb.append('\n').append(e.getKey()).append('=').append(e.getValue());
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private byte[] hmac(byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private void evictExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Receipt>> it = receipts.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expiresAt < now) {
                it.remove();
            }
        }
    }

    private record Receipt(String callerId, String toolName, String fingerprint, long expiresAt) {}
}
