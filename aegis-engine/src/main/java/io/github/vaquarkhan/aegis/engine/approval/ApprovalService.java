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
import io.github.vaquarkhan.aegis.engine.json.JsonValues;
import io.github.vaquarkhan.aegis.engine.model.Action;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.model.Digests;
import io.github.vaquarkhan.aegis.engine.spi.AuditSink;
import io.github.vaquarkhan.aegis.engine.spi.StateStore;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Step 4. Per-call human approval for write tools.
 *
 * <p>Flow: a write call without a token is denied with {@code APPROVAL_REQUIRED} and an approval
 * request id. An approver (a member of an approver group, and never the requester) approves the
 * request; the requester fetches the token and retries the SAME call.
 *
 * <p>What is bound. The token is bound to the caller and to the action binding: a digest over the
 * gateway name, tool, effective class, source, typed resources, canonical argument digest and the
 * pinned tool definition. An approval therefore cannot be replayed on another gateway that shares
 * the signing key, against another source, or after the tool definition it was granted for changed.
 * It expires and is single-use.
 *
 * <p>Informed approval. The request carries evidence for the human: the redacted, size-bounded
 * arguments and, when the call was dry run first, the redacted preview result. An approver decides
 * on what the call would do, not on a digest.
 *
 * <p>Separation of duties. An approver must be in an approver group, must not be a delegated
 * identity, and must not match the actor, the subject acted for, or any party in the delegation
 * chain. Names are compared after Unicode NFKC normalisation and case folding, so {@code Alice} and
 * {@code ALICE} (the same LDAP account) are treated as one person. The comparison errs towards
 * refusing: two different people whose names differ only in case cannot approve each other.
 *
 * <p>Accountability. With a journal configured, every decision is written to the audit journal
 * before it takes effect; if the journal cannot record it, the decision is refused. Refused attempts
 * (self-approval, a delegated approver, a non-approver) are journaled best-effort.
 *
 * <p>Verification ({@link #verify}) does not consume; the engine consumes the nonce only when every
 * control has passed ({@link #consume}), so a call that is later rate-limited does not burn the
 * approval. Consumption is an atomic put-if-absent, fixing the 0.1 get-then-remove race.
 */
public final class ApprovalService {

    public enum Status { PENDING, APPROVED, DENIED }

    private static final String REQ = "approval-req:";
    private static final String OPEN = "approval-open:";
    private static final String DECIDED = "approval-decided:";
    private static final String USED = "approval-used:";
    /** New approval requests one caller may open per hour. */
    public static final long MAX_NEW_REQUESTS_PER_HOUR = 100;

    private static final System.Logger LOG = System.getLogger(ApprovalService.class.getName());

    private final SignedTokens tokens;
    private final StateStore store;
    private final Set<String> approverGroups;
    private final Duration requestTtl;
    private final Duration tokenTtl;
    private final Clock clock;
    private final AuditSink journal;
    private final String gatewayName;
    private final SecureRandom random = new SecureRandom();

    /** Approval without a decision journal (unit tests and embedding hosts that journal elsewhere). */
    public ApprovalService(byte[] signingKey, StateStore store, Set<String> approverGroups,
                           Duration requestTtl, Duration tokenTtl, Clock clock) {
        this(signingKey, store, approverGroups, requestTtl, tokenTtl, clock, null, "");
    }

    /**
     * @param signingKey  null means approval is not configured: every write call is then denied
     *                    (fail-closed) with a reason telling the operator what is missing
     * @param journal     audit journal for decisions; null disables decision journaling
     * @param gatewayName recorded on journal entries
     */
    public ApprovalService(byte[] signingKey, StateStore store, Set<String> approverGroups,
                           Duration requestTtl, Duration tokenTtl, Clock clock, AuditSink journal, String gatewayName) {
        this.tokens = signingKey == null ? null : new SignedTokens("aat1", signingKey);
        this.store = store;
        this.approverGroups = approverGroups == null ? Set.of() : Set.copyOf(approverGroups);
        this.requestTtl = requestTtl;
        this.tokenTtl = tokenTtl;
        this.clock = clock;
        this.journal = journal;
        this.gatewayName = gatewayName == null ? "" : gatewayName;
    }

    public boolean configured() {
        return tokens != null && !approverGroups.isEmpty();
    }

    /**
     * Identity key used for separation-of-duties comparisons: NFKC-normalised, trimmed, case-folded.
     * Deliberately coarser than {@link CallerIdentity#principalKey()}, so that visually or
     * case-different spellings of one account cannot pass as two people.
     */
    public static String identityKey(String name) {
        if (name == null) {
            return "";
        }
        return Normalizer.normalize(name, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
    }

    /** Result of verifying a presented token. */
    public record Check(boolean ok, String reason, String nonce, Instant expiresAt, String approver) {
        static Check fail(String reason) {
            return new Check(false, reason, null, null, null);
        }
    }

    /**
     * @param binding the action binding digest the engine computed for this exact call
     *                (see {@link Action#binding(String, String)})
     */
    public Check verify(CallerIdentity caller, Action action, String binding, String token) {
        if (!configured()) {
            return Check.fail("approval is not configured (approval signing key and approver groups are required)");
        }
        Map<String, Object> p = tokens.verify(token);
        if (p == null) {
            return Check.fail("approval token is invalid");
        }
        Long exp = JsonValues.integral(p.get("exp"));
        Instant now = clock.instant();
        if (exp == null || now.getEpochSecond() > exp) {
            return Check.fail("approval token has expired");
        }
        if (!caller.principalKey().equals(p.get("caller"))) {
            return Check.fail("approval token was issued to a different caller");
        }
        if (!action.tool().equals(p.get("tool")) || !action.argsDigest().equals(p.get("digest"))) {
            return Check.fail("approval token was issued for a different action");
        }
        if (binding == null || !binding.equals(p.get("binding"))) {
            return Check.fail("approval token was issued for a different gateway, source, resource set or tool definition");
        }
        String nonce = JsonValues.string(p, "nonce");
        if (nonce == null || store.get(USED + nonce).isPresent()) {
            return Check.fail("approval token has already been used");
        }
        return new Check(true, "approved", nonce, Instant.ofEpochSecond(exp), JsonValues.string(p, "approver"));
    }

    /** Atomically marks the token used. False when another request consumed it first. */
    public boolean consume(Check check, CallerIdentity caller, String binding) {
        boolean first = store.putIfAbsent(USED + check.nonce(), "1", check.expiresAt().plus(Duration.ofMinutes(5)));
        if (first) {
            // The next identical call needs a fresh approval, so close the dedupe index.
            store.take(OPEN + openId(caller, binding));
        } else if (store.get(USED + check.nonce()).isEmpty()) {
            // Not a replay: the store refused the write (full). Deny, but say what happened.
            throw new IllegalStateException("governance state store is full");
        }
        return first;
    }

    /**
     * Opens (or returns the already-open) approval request for this caller and action.
     * Deduplicated while pending or approved-but-unused, so a retrying agent does not flood approvers.
     *
     * @param evidence what the approver is shown: {@code arguments} (redacted and bounded by the
     *                 caller of this method) and optionally {@code preview} (the redacted dry-run
     *                 result). A preview arriving for an already-pending request is added to it.
     */
    public Map<String, Object> request(CallerIdentity caller, Action action, String binding, Map<String, Object> evidence) {
        String openKey = OPEN + openId(caller, binding);
        Optional<String> existing = store.get(openKey);
        if (existing.isPresent()) {
            Optional<Map<String, Object>> rec = load(existing.get());
            if (rec.isPresent() && !Status.DENIED.name().equals(rec.get().get("status"))) {
                Map<String, Object> r = rec.get();
                if (Status.PENDING.name().equals(r.get("status")) && evidence != null && evidence.containsKey("preview")
                        && !(r.get("evidence") instanceof Map<?, ?> ev && ev.containsKey("preview"))) {
                    Map<String, Object> merged = new LinkedHashMap<>(r);
                    Map<String, Object> ev = new LinkedHashMap<>();
                    if (r.get("evidence") instanceof Map<?, ?> old) {
                        old.forEach((k, v) -> ev.put(String.valueOf(k), v));
                    }
                    ev.put("preview", evidence.get("preview"));
                    merged.put("evidence", ev);
                    Instant exp = Instant.parse((String) r.get("expiresAt"));
                    store.put(REQ + r.get("id"), Json.write(merged), exp);
                    return publicView(merged, false);
                }
                return publicView(r, false);
            }
        }
        Instant now = clock.instant();
        // Bound new requests per caller, so one caller cannot fill shared state with distinct
        // argument sets and starve everyone else's approvals.
        if (!store.tryAcquire("approval-rate:" + caller.principalKey(), 1, MAX_NEW_REQUESTS_PER_HOUR, Duration.ofHours(1), now)) {
            throw new ApprovalException(429, "too many new approval requests from this caller; retry later");
        }
        Instant exp = now.plus(requestTtl);
        String id = "apr_" + randomHex(16);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id);
        r.put("status", Status.PENDING.name());
        r.put("caller", caller.principalKey());
        r.put("actor", caller.actor());
        r.put("effectiveSubject", caller.effectiveSubject());
        r.put("issuer", caller.issuer());
        r.put("actorChain", caller.actorChain());
        r.put("tool", action.tool());
        r.put("toolClass", action.toolClass().name());
        r.put("source", action.sourceId());
        r.put("resources", action.resources());
        r.put("argsDigest", action.argsDigest());
        r.put("binding", binding);
        if (evidence != null && !evidence.isEmpty()) {
            r.put("evidence", new LinkedHashMap<>(evidence));
        }
        r.put("createdAt", now.toString());
        r.put("expiresAt", exp.toString());
        store.put(REQ + id, Json.write(r), exp);
        store.put(openKey, id, exp);
        return publicView(r, false);
    }

    /** Approver decision. Returns the updated request (never the token). */
    public Map<String, Object> decide(String id, CallerIdentity approver, boolean approve) {
        if (!configured()) {
            throw new ApprovalException(503, "approval is not configured");
        }
        if (!approver.inAnyGroup(approverGroups)) {
            throw refused(id, approver, approve, 403, "caller is not in an approver group");
        }
        // Approvals are a human decision: a delegated caller (an agent acting for someone, or an
        // identity-asserted subject) may not approve at all.
        if (!approver.actorChain().isEmpty()
                || !identityKey(approver.actor()).equals(identityKey(approver.effectiveSubject()))) {
            throw refused(id, approver, approve, 403, "approvals must be made by a non-delegated identity");
        }
        Map<String, Object> r = load(id).orElseThrow(() -> new ApprovalException(404, "approval request not found or expired"));
        // Separation of duties across every identity involved in the request: the actor, the subject
        // acted for, and each party in the delegation chain.
        Set<String> requesterIds = new HashSet<>();
        requesterIds.add(identityKey(String.valueOf(r.get("actor"))));
        requesterIds.add(identityKey(String.valueOf(r.get("effectiveSubject"))));
        if (r.get("actorChain") instanceof List<?> chain) {
            for (Object o : chain) {
                requesterIds.add(identityKey(String.valueOf(o)));
            }
        }
        if (requesterIds.contains(identityKey(approver.actor())) || approver.principalKey().equals(r.get("caller"))) {
            throw refused(id, approver, approve, 403, "separation of duties: a requester cannot approve their own request");
        }
        Instant now = clock.instant();
        Instant reqExp = Instant.parse((String) r.get("expiresAt"));
        if (now.isAfter(reqExp)) {
            throw new ApprovalException(410, "approval request has expired");
        }
        String verdict = approve ? Status.APPROVED.name() : Status.DENIED.name();
        if (!store.putIfAbsent(DECIDED + id, verdict, reqExp)) {
            throw new ApprovalException(409, "approval request was already decided");
        }
        // The decision is journaled before it takes effect: no token exists yet, so undoing the
        // decided-marker on a journal failure leaves nothing usable behind.
        if (journal != null) {
            try {
                journal.append(decisionRecord("APPROVAL_DECISION", id, r, approver, verdict, null));
            } catch (Exception e) {
                store.take(DECIDED + id);
                LOG.log(System.Logger.Level.ERROR, "audit journal unavailable; approval decision " + id + " refused", e);
                throw new ApprovalException(503, "the audit journal could not record this decision, so it was not made");
            }
        }
        Map<String, Object> updated = new LinkedHashMap<>(r);
        updated.put("decidedBy", approver.actor());
        updated.put("decidedAt", now.toString());
        if (approve) {
            Instant tokenExp = now.plus(tokenTtl);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("rid", id);
            payload.put("caller", r.get("caller"));
            payload.put("tool", r.get("tool"));
            payload.put("digest", r.get("argsDigest"));
            payload.put("binding", r.get("binding"));
            payload.put("exp", tokenExp.getEpochSecond());
            payload.put("nonce", randomHex(16));
            payload.put("approver", approver.actor());
            updated.put("status", Status.APPROVED.name());
            updated.put("token", tokens.sign(payload));
            updated.put("tokenExpiresAt", tokenExp.toString());
        } else {
            updated.put("status", Status.DENIED.name());
        }
        store.put(REQ + id, Json.write(updated), reqExp.isAfter(now.plus(tokenTtl)) ? reqExp : now.plus(tokenTtl));
        return publicView(updated, false); // the token is fetched by the requester, never handed to the approver
    }

    /** Requester or approver view of one request; the token is shown only to the requester. */
    public Map<String, Object> get(String id, CallerIdentity viewer) {
        Map<String, Object> r = load(id).orElseThrow(() -> new ApprovalException(404, "approval request not found or expired"));
        boolean requester = viewer.principalKey().equals(r.get("caller"));
        if (!requester && !viewer.inAnyGroup(approverGroups)) {
            throw new ApprovalException(404, "approval request not found or expired");
        }
        return publicView(r, requester);
    }

    public List<Map<String, Object>> pending(CallerIdentity approver) {
        if (!approver.inAnyGroup(approverGroups)) {
            throw new ApprovalException(403, "caller is not in an approver group");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String v : store.scan(REQ, 500).values()) {
            Map<String, Object> r = Json.parseObject(v);
            if (Status.PENDING.name().equals(r.get("status"))) {
                out.add(publicView(r, false));
            }
        }
        return out;
    }

    /** Journals a refused decision attempt (best effort) and returns the exception to throw. */
    private ApprovalException refused(String id, CallerIdentity approver, boolean approve, int status, String reason) {
        if (journal != null) {
            Map<String, Object> r = load(id).orElse(Map.of());
            try {
                journal.append(decisionRecord("APPROVAL_REFUSED", id, r, approver,
                        approve ? Status.APPROVED.name() : Status.DENIED.name(), reason));
            } catch (Exception e) {
                LOG.log(System.Logger.Level.ERROR, "audit journal unavailable for refused approval attempt on " + id, e);
            }
        }
        return new ApprovalException(status, reason);
    }

    private Map<String, Object> decisionRecord(String event, String id, Map<String, Object> r, CallerIdentity approver,
                                               String verdict, String reason) {
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("ts", clock.instant().toString());
        rec.put("gateway", gatewayName);
        rec.put("event", event);
        rec.put("approvalRequestId", id == null ? "" : id);
        rec.put("approver", approver.actor());
        rec.put("approverIssuer", approver.issuer());
        rec.put("approverAuthMethod", approver.authMethod());
        rec.put("decision", verdict);
        for (String k : List.of("actor", "effectiveSubject", "issuer", "actorChain", "tool", "toolClass", "source",
                "resources", "argsDigest", "binding")) {
            if (r.containsKey(k)) {
                rec.put(k.equals("actor") ? "requester" : k.equals("issuer") ? "requesterIssuer" : k, r.get(k));
            }
        }
        if (reason != null) {
            rec.put("reason", reason);
        }
        return rec;
    }

    private Optional<Map<String, Object>> load(String id) {
        if (id == null || !id.matches("apr_[0-9a-f]{32}")) {
            return Optional.empty();
        }
        return store.get(REQ + id).map(Json::parseObject);
    }

    private static Map<String, Object> publicView(Map<String, Object> r, boolean includeToken) {
        Map<String, Object> v = new LinkedHashMap<>(r);
        v.remove("caller");
        if (!includeToken) {
            v.remove("token");
        }
        return v;
    }

    private static String openId(CallerIdentity caller, String binding) {
        return Digests.sha256Hex(Json.canonical(List.of(caller.principalKey(), binding == null ? "" : binding)));
    }

    private String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** Approval API error with an HTTP-style status for hosts to map. */
    public static final class ApprovalException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int status;

        public ApprovalException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
