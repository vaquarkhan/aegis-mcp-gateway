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

import io.github.vaquarkhan.aegis.engine.json.JsonValues;
import io.github.vaquarkhan.aegis.engine.model.Action;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.spi.StateStore;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Step 9. Validate-run-promote (VRP) for DESTRUCTIVE tools, same contract as 0.1: the real call
 * must present a receipt from a dry run of the identical call by the same caller.
 *
 * <p>2.0 changes: receipts are HMAC-signed, self-contained tokens (verifiable on any replica),
 * bound to the caller and to the action binding ({@link Action#binding(String, String)}: gateway, tool,
 * class, source, resources, canonical argument digest and pinned tool definition; 0.1 fingerprinted
 * {@code toString()} of the values), and consumed with an atomic put-if-absent only when every control
 * has passed. A receipt from one gateway therefore cannot promote a call on another gateway that shares
 * the signing key, nor survive a change of the tool definition between preview and promotion.
 */
public final class VrpService {

    private static final String USED = "vrp-used:";

    private final SignedTokens tokens;
    private final StateStore store;
    private final Duration ttl;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public VrpService(byte[] signingKey, StateStore store, Duration ttl, Clock clock) {
        this.tokens = signingKey == null ? null : new SignedTokens("vrp1", signingKey);
        this.store = store;
        this.ttl = ttl;
        this.clock = clock;
    }

    public boolean configured() {
        return tokens != null;
    }

    public record Receipt(String token, Instant expiresAt) {}

    public Receipt issue(CallerIdentity caller, Action action, String binding) {
        if (tokens == null) {
            throw new IllegalStateException("VRP signing key is not configured");
        }
        Instant exp = clock.instant().plus(ttl);
        byte[] n = new byte[16];
        random.nextBytes(n);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("caller", caller.principalKey());
        p.put("tool", action.tool());
        p.put("digest", action.argsDigest());
        p.put("binding", binding);
        p.put("exp", exp.getEpochSecond());
        p.put("nonce", HexFormat.of().formatHex(n));
        return new Receipt(tokens.sign(p), exp);
    }

    public record Check(boolean ok, String reason, String nonce, Instant expiresAt) {}

    public Check verify(CallerIdentity caller, Action action, String binding, String receipt) {
        if (tokens == null) {
            return new Check(false, "VRP is not configured (signing key required)", null, null);
        }
        if (receipt == null || receipt.isBlank()) {
            return new Check(false, "destructive call requires a dry-run receipt: run it first with dryRun=true", null, null);
        }
        Map<String, Object> p = tokens.verify(receipt);
        if (p == null) {
            return new Check(false, "dry-run receipt is invalid", null, null);
        }
        Long exp = JsonValues.integral(p.get("exp"));
        if (exp == null || clock.instant().getEpochSecond() > exp) {
            return new Check(false, "dry-run receipt has expired", null, null);
        }
        if (!caller.principalKey().equals(p.get("caller"))) {
            return new Check(false, "dry-run receipt was issued to a different caller", null, null);
        }
        if (!action.tool().equals(p.get("tool")) || !action.argsDigest().equals(p.get("digest"))) {
            return new Check(false, "dry-run receipt does not match this call's arguments", null, null);
        }
        if (binding == null || !binding.equals(p.get("binding"))) {
            return new Check(false, "dry-run receipt was issued for a different gateway, source, resource set or tool definition", null, null);
        }
        String nonce = JsonValues.string(p, "nonce");
        if (nonce == null || store.get(USED + nonce).isPresent()) {
            return new Check(false, "dry-run receipt has already been used", null, null);
        }
        return new Check(true, "receipt valid", nonce, Instant.ofEpochSecond(exp));
    }

    public boolean consume(Check check) {
        return store.putIfAbsent(USED + check.nonce(), "1", check.expiresAt().plus(Duration.ofMinutes(5)));
    }
}
