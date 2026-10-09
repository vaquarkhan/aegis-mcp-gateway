/*
 * Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vaquarkhan.aegis.engine.approval;

import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.json.JsonException;
import io.github.vaquarkhan.aegis.engine.json.JsonLimits;
import io.github.vaquarkhan.aegis.engine.model.Digests;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Compact HMAC-SHA256 tokens: {@code <typ>.<base64url(canonical JSON payload)>.<base64url(mac)>}.
 * Each token type uses its own key derived from the master key, so an approval token can never be
 * replayed as a dry-run receipt or the reverse. Tokens are self-contained, so any replica with the
 * same key can verify them; single use is enforced separately through the state store.
 */
public final class SignedTokens {

    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();
    private static final JsonLimits TOKEN_LIMITS = JsonLimits.DEFAULT.withMaxBytes(8 * 1024).withMaxDepth(4);

    private final String type;
    private final byte[] key;

    /**
     * @param type      token type prefix, e.g. {@code aat1} (approval) or {@code vrp1} (receipt)
     * @param masterKey at least 32 bytes of secret key material
     */
    public SignedTokens(String type, byte[] masterKey) {
        if (masterKey == null || masterKey.length < 32) {
            throw new IllegalArgumentException("signing key must be at least 32 bytes");
        }
        this.type = type;
        this.key = hmac(masterKey, ("aegis-token-key:" + type).getBytes(StandardCharsets.UTF_8));
    }

    public String sign(Map<String, Object> payload) {
        String body = ENC.encodeToString(Json.canonical(payload).getBytes(StandardCharsets.UTF_8));
        String signingInput = type + "." + body;
        return signingInput + "." + ENC.encodeToString(hmac(key, signingInput.getBytes(StandardCharsets.UTF_8)));
    }

    /** @return the payload when the token is well-formed, of this type, and the MAC verifies; else null */
    public Map<String, Object> verify(String token) {
        if (token == null || token.length() > 8 * 1024) {
            return null;
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || !parts[0].equals(type)) {
            return null;
        }
        String signingInput = parts[0] + "." + parts[1];
        String expected = ENC.encodeToString(hmac(key, signingInput.getBytes(StandardCharsets.UTF_8)));
        if (!Digests.constantTimeEquals(expected, parts[2])) {
            return null;
        }
        try {
            return Json.parseObject(DEC.decode(parts[1]), TOKEN_LIMITS);
        } catch (IllegalArgumentException | JsonException e) {
            return null;
        }
    }

    static byte[] hmac(byte[] k, byte[] msg) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(k, "HmacSHA256"));
            return mac.doFinal(msg);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is required by every Java SE platform", e);
        }
    }
}
