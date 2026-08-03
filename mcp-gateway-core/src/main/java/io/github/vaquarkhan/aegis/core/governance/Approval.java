package io.github.vaquarkhan.aegis.core.governance;

import io.github.vaquarkhan.aegis.core.auth.NonceStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Step 4. HMAC-SHA256 approval tokens for mutating and destructive calls.
 *
 * <p>Token layout is {@code base64url(tool|scope|expiryMillis|nonce) + "." + base64url(hmac)}.
 * A token is bound to one tool, one resource scope and one expiry, and the nonce makes it single
 * use, so capturing a token from a transcript does not let an attacker repeat the operation.
 *
 * <p>Verification fails closed when no secret is configured, which is why enabling writes without
 * {@code MCP_GW_APPROVAL_SECRET} is rejected at startup rather than silently allowing everything.
 *
 * @author Viquar Khan
 */
public final class Approval {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final byte[] secret;
    private final NonceStore nonces;
    private final SecureRandom rng = new SecureRandom();

    public Approval(String secret, NonceStore nonces) {
        this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        this.nonces = nonces == null ? new NonceStore() : nonces;
    }

    public String mint(String tool, String scope, long ttlMillis) {
        String sc = (scope == null || scope.isBlank()) ? "*" : scope;
        byte[] nonceBytes = new byte[16];
        rng.nextBytes(nonceBytes);
        String nonce = B64.encodeToString(nonceBytes);
        long exp = System.currentTimeMillis() + ttlMillis;
        String payload = tool + "|" + sc + "|" + exp + "|" + nonce;
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        return B64.encodeToString(payloadBytes) + "." + B64.encodeToString(hmac(payloadBytes));
    }

    /**
     * Verifies signature, tool binding, scope binding, expiry and single use, in that order.
     * A wildcard scope inside the token matches any required scope; the reverse is not true.
     */
    public boolean verify(String token, String tool, String requiredScope) {
        if (token == null || secret.length == 0) {
            return false;
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot >= token.length() - 1) {
            return false;
        }
        byte[] payloadBytes;
        byte[] providedSig;
        try {
            payloadBytes = B64D.decode(token.substring(0, dot));
            providedSig = B64D.decode(token.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (!MessageDigest.isEqual(hmac(payloadBytes), providedSig)) {
            return false;
        }
        String[] parts = new String(payloadBytes, StandardCharsets.UTF_8).split("\\|", -1);
        if (parts.length != 4) {
            return false;
        }
        if (!parts[0].equals(tool)) {
            return false;
        }
        String tokenScope = parts[1];
        String req = requiredScope == null ? "" : requiredScope;
        if (!(tokenScope.isEmpty() || "*".equals(tokenScope) || tokenScope.equals(req))) {
            return false;
        }
        long exp;
        try {
            exp = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            return false;
        }
        if (System.currentTimeMillis() > exp) {
            return false;
        }
        return nonces.useOnce(parts[3], exp);
    }

    public boolean configured() {
        return secret.length > 0;
    }

    private byte[] hmac(byte[] payload) {
        try {
            // SecretKeySpec rejects empty keys, but mint may be called before a secret exists.
            // Verification always fails in that case, so the substitute key is never trusted.
            byte[] key = secret.length == 0 ? new byte[] {0} : secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /**
     * Mint CLI. The token is the only thing written to stdout so it can be captured with a shell
     * substitution; usage and diagnostics go to stderr.
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: Approval <secret> <tool> [scope=*] [ttlSeconds=300]");
            System.exit(1);
        }
        String secret = args[0];
        String tool = args[1];
        String scope = "*";
        long ttlSeconds = 300L;
        if (args.length >= 3) {
            if (args[2].chars().allMatch(Character::isDigit)) {
                ttlSeconds = Long.parseLong(args[2]);
            } else {
                scope = args[2];
                if (args.length >= 4) {
                    ttlSeconds = Long.parseLong(args[3]);
                }
            }
        }
        Approval approval = new Approval(secret, new NonceStore());
        System.out.println(approval.mint(tool, scope, ttlSeconds * 1000L));
    }
}
