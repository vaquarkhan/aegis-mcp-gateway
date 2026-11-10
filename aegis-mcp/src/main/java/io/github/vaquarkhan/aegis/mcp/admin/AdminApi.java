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
package io.github.vaquarkhan.aegis.mcp.admin;

import io.github.vaquarkhan.aegis.engine.GovernanceEngine;
import io.github.vaquarkhan.aegis.engine.approval.ApprovalService;
import io.github.vaquarkhan.aegis.engine.audit.AuditVerifier;
import io.github.vaquarkhan.aegis.engine.catalog.ToolCatalog;
import io.github.vaquarkhan.aegis.engine.content.ContentGovernor;
import io.github.vaquarkhan.aegis.engine.control.TokenRevocations;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.mcp.http.McpHttpRequest;
import io.github.vaquarkhan.aegis.mcp.http.McpHttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Human-facing governance API, mounted by each host under its own path ({@code /aegis/v1/...} on
 * the gateway's own server, {@code /gateway/<topology>/aegis-mcp/v1/admin/...} in Knox). Every
 * route requires an authenticated caller; approval decisions and catalog acceptance require an
 * approver group and are refused for the requester (separation of duties).
 *
 * <pre>
 *   GET  /approvals                      pending requests (approvers)
 *   GET  /approvals/{id}                 one request (requester sees the token once approved)
 *   POST /approvals/{id}/approve         approver decision
 *   POST /approvals/{id}/deny            approver decision
 *   GET  /audit/verify                    fixed configured journal only (audit verifier group)
 *   GET  /catalog                        tools with integrity status and digests (approvers)
 *   POST /catalog/{tool}/accept          accept the current definition of a quarantined tool (approvers)
 *   GET  /prompts                        prompts with integrity status and digests (approvers; when prompts are enabled)
 *   POST /prompts/{prompt}/accept        accept the current definition of a quarantined prompt (approvers)
 * </pre>
 *
 * Browser-originated requests are refused unless the Origin is allowed (CSRF defence for the
 * state-changing routes), and POST bodies are ignored.
 */
public final class AdminApi {

  private final GovernanceEngine engine;
  private final ContentGovernor content;
  private final Set<String> approverGroups;
  private final Set<String> auditVerifierGroups;
  private final AuditVerifier auditVerifier;
  private final Set<String> allowedOrigins;
  private final Set<String> revokerGroups;
  private final TokenRevocations revocations;
  private final java.time.Duration revocationRetention;
  private final Set<String> resultReleaseGroups;
  private final java.time.Clock clock;

  public AdminApi(GovernanceEngine engine, Set<String> approverGroups, Set<String> allowedOrigins) {
    this(engine, null, approverGroups, Set.of(), null, allowedOrigins);
  }

  /**
   * @param content prompt catalog for {@code /prompts} routes; null when no prompts are configured
   */
  public AdminApi(
      GovernanceEngine engine,
      ContentGovernor content,
      Set<String> approverGroups,
      Set<String> allowedOrigins) {
    this(engine, content, approverGroups, Set.of(), null, allowedOrigins);
  }

  /**
   * @param auditVerifierGroups callers allowed to learn only whether the fixed configured journal
   *     is intact
   * @param auditVerifier fixed-journal verifier; null disables the route (404)
   */
  public AdminApi(
      GovernanceEngine engine,
      ContentGovernor content,
      Set<String> approverGroups,
      Set<String> auditVerifierGroups,
      AuditVerifier auditVerifier,
      Set<String> allowedOrigins) {
    this(engine, content, approverGroups, auditVerifierGroups, auditVerifier, allowedOrigins, null);
  }

  /**
   * @param clock the gateway clock (O-13); null uses the system clock
   */
  public AdminApi(
      GovernanceEngine engine,
      ContentGovernor content,
      Set<String> approverGroups,
      Set<String> auditVerifierGroups,
      AuditVerifier auditVerifier,
      Set<String> allowedOrigins,
      java.time.Clock clock) {
    this.clock = clock == null ? java.time.Clock.systemUTC() : clock;
    this.engine = engine;
    this.content = content;
    this.approverGroups = Set.copyOf(approverGroups);
    this.auditVerifierGroups =
        auditVerifierGroups == null ? Set.of() : Set.copyOf(auditVerifierGroups);
    this.auditVerifier = auditVerifier;
    this.allowedOrigins = allowedOrigins == null ? Set.of() : Set.copyOf(allowedOrigins);
    this.revokerGroups = Set.of();
    this.revocations = null;
    this.revocationRetention = java.time.Duration.ofHours(24);
    this.resultReleaseGroups = Set.of();
  }

  private AdminApi(
      AdminApi base,
      Set<String> revokerGroups,
      TokenRevocations revocations,
      java.time.Duration retention) {
    this(base, revokerGroups, revocations, retention, base.resultReleaseGroups);
  }

  private AdminApi(
      AdminApi base,
      Set<String> revokerGroups,
      TokenRevocations revocations,
      java.time.Duration retention,
      Set<String> resultReleaseGroups) {
    this.clock = base.clock;
    this.engine = base.engine;
    this.content = base.content;
    this.approverGroups = base.approverGroups;
    this.auditVerifierGroups = base.auditVerifierGroups;
    this.auditVerifier = base.auditVerifier;
    this.allowedOrigins = base.allowedOrigins;
    this.revokerGroups = revokerGroups == null ? Set.of() : Set.copyOf(revokerGroups);
    this.revocations = revocations;
    this.revocationRetention =
        retention == null || retention.isNegative() || retention.isZero()
            ? java.time.Duration.ofHours(24)
            : retention;
    this.resultReleaseGroups =
        resultReleaseGroups == null ? Set.of() : Set.copyOf(resultReleaseGroups);
  }

  /**
   * Same API plus handle-bound result release for {@code outputPolicy: HANDLE_ONLY}:
   *
   * <pre>
   *   POST /results/{handle}/release   take-once release of the redacted result (result release groups)
   * </pre>
   *
   * The identity that produced the handle can never release it (the model's caller must not be able
   * to read what the policy withheld from it), the releaser must be non-delegated, and the intent
   * is journaled before the data leaves the store.
   */
  public AdminApi withResultRelease(Set<String> groups) {
    return new AdminApi(this, revokerGroups, revocations, revocationRetention, groups);
  }

  /**
   * Same API plus the token revocation routes:
   *
   * <pre>
   *   POST /revocations/subject/{subject}   refuse every token for subject issued until now (revokers)
   *   POST /revocations/token/{jti}         refuse one token id (revokers)
   * </pre>
   *
   * The issuer is the caller's verified issuer unless one {@code Aegis-Token-Issuer} header names
   * another. An empty {@code revokerGroups} disables the routes (403): revocation is never open to
   * every authenticated caller.
   */
  public AdminApi withRevocations(
      Set<String> revokerGroups, TokenRevocations revocations, java.time.Duration retention) {
    return new AdminApi(this, revokerGroups, revocations, retention);
  }

  public McpHttpResponse handle(McpHttpRequest req, CallerIdentity caller) {
    String origin = req.header("Origin");
    if (origin != null && !allowedOrigins.contains(origin)) {
      return McpHttpResponse.json(403, Map.of("error", "origin not allowed"));
    }
    if (caller == null) {
      return McpHttpResponse.json(401, Map.of("error", "authentication required"));
    }
    String[] parts = req.path().replaceAll("^/+", "").replaceAll("/+$", "").split("/");
    ApprovalService approvals = engine.approvals();
    try {
      if (parts.length >= 1 && "approvals".equals(parts[0])) {
        if (parts.length == 1 && "GET".equals(req.method())) {
          return McpHttpResponse.json(200, Map.of("pending", approvals.pending(caller)));
        }
        if (parts.length == 2 && "GET".equals(req.method())) {
          return McpHttpResponse.json(200, approvals.get(parts[1], caller));
        }
        if (parts.length == 3
            && "POST".equals(req.method())
            && ("approve".equals(parts[2]) || "deny".equals(parts[2]))) {
          return McpHttpResponse.json(
              200, approvals.decide(parts[1], caller, "approve".equals(parts[2])));
        }
      }
      if (parts.length == 2
          && "audit".equals(parts[0])
          && "verify".equals(parts[1])
          && "GET".equals(req.method())
          && auditVerifier != null) {
        if (!caller.inAnyGroup(auditVerifierGroups)) {
          return McpHttpResponse.json(
              403, Map.of("error", "caller is not in an audit verifier group"));
        }
        try {
          return auditVerifier.verify()
              ? McpHttpResponse.json(200, Map.of("verified", true))
              : McpHttpResponse.json(409, Map.of("verified", false));
        } catch (java.io.IOException e) {
          // The endpoint intentionally withholds the configured file name and verifier diagnostics.
          return McpHttpResponse.json(503, Map.of("error", "audit verification unavailable"));
        }
      }
      if (parts.length >= 3
          && "revocations".equals(parts[0])
          && revocations != null
          && "POST".equals(req.method())
          && ("subject".equals(parts[1]) || "token".equals(parts[1]))) {
        return revoke(
            req,
            caller,
            parts[1],
            String.join("/", java.util.Arrays.copyOfRange(parts, 2, parts.length)));
      }
      if (parts.length == 3
          && "results".equals(parts[0])
          && "release".equals(parts[2])
          && "POST".equals(req.method())
          && engine.resultHandles() != null) {
        return releaseResult(caller, parts[1]);
      }
      if (parts.length >= 1 && "catalog".equals(parts[0])) {
        if (!caller.inAnyGroup(approverGroups)) {
          return McpHttpResponse.json(403, Map.of("error", "caller is not in an approver group"));
        }
        ToolCatalog catalog = engine.catalog();
        if (parts.length == 1 && "GET".equals(req.method())) {
          List<Map<String, Object>> tools = new ArrayList<>();
          for (ToolCatalog.Entry e : catalog.entries()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", e.tool().exposedName());
            m.put("source", e.tool().sourceId());
            m.put("toolClass", e.tool().toolClass().name());
            m.put("status", e.status().name());
            m.put("reason", e.reason());
            m.put("digest", e.digest());
            tools.add(m);
          }
          return McpHttpResponse.json(200, Map.of("tools", tools));
        }
        if (parts.length == 3 && "POST".equals(req.method()) && "accept".equals(parts[2])) {
          String expectedDigest = req.header("Aegis-Definition-Digest");
          if (req.headerCount("Aegis-Definition-Digest") != 1
              || expectedDigest == null
              || expectedDigest.isBlank()) {
            return McpHttpResponse.json(
                428, Map.of("error", "Aegis-Definition-Digest is required"));
          }
          Map<String, Object> details = new LinkedHashMap<>();
          details.put("tool", parts[1]);
          details.put("expectedDigest", expectedDigest);
          catalog
              .lookup(parts[1])
              .ifPresent(
                  e -> {
                    details.put("source", e.tool().sourceId());
                    details.put("toolClass", e.tool().toolClass().name());
                    details.put("digest", e.digest());
                    details.put("quarantineReason", e.reason());
                  });
          return acceptDefinition(
              "CATALOG_ACCEPT",
              caller,
              details,
              () -> catalog.accept(parts[1], expectedDigest),
              "tool is not quarantined, unknown, pinned in configuration, or no longer has the reviewed digest");
        }
      }
      if (parts.length >= 1
          && "prompts".equals(parts[0])
          && content != null
          && content.offersPrompts()) {
        if (!caller.inAnyGroup(approverGroups)) {
          return McpHttpResponse.json(403, Map.of("error", "caller is not in an approver group"));
        }
        if (parts.length == 1 && "GET".equals(req.method())) {
          List<Map<String, Object>> prompts = new ArrayList<>();
          for (ContentGovernor.PromptEntry e : content.promptEntries()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", e.exposedName());
            m.put("source", e.sourceId());
            m.put("status", e.active() ? "ACTIVE" : "QUARANTINED");
            m.put("reason", e.reason());
            m.put("digest", e.digest());
            prompts.add(m);
          }
          return McpHttpResponse.json(200, Map.of("prompts", prompts));
        }
        if (parts.length == 3 && "POST".equals(req.method()) && "accept".equals(parts[2])) {
          String expectedDigest = req.header("Aegis-Definition-Digest");
          if (req.headerCount("Aegis-Definition-Digest") != 1
              || expectedDigest == null
              || expectedDigest.isBlank()) {
            return McpHttpResponse.json(
                428, Map.of("error", "Aegis-Definition-Digest is required"));
          }
          Map<String, Object> details = new LinkedHashMap<>();
          details.put("prompt", parts[1]);
          details.put("expectedDigest", expectedDigest);
          for (ContentGovernor.PromptEntry e : content.promptEntries()) {
            if (e.exposedName().equals(parts[1])) {
              details.put("source", e.sourceId());
              details.put("digest", e.digest());
              details.put("quarantineReason", e.reason());
            }
          }
          return acceptDefinition(
              "PROMPT_ACCEPT",
              caller,
              details,
              () -> content.acceptPrompt(parts[1], expectedDigest),
              "prompt is not quarantined, unknown, pinned in configuration, or no longer has the reviewed digest");
        }
      }
      return McpHttpResponse.json(404, Map.of("error", "not found"));
    } catch (ApprovalService.ApprovalException e) {
      return McpHttpResponse.json(e.status(), Map.of("error", e.getMessage()));
    } catch (Exception e) {
      return McpHttpResponse.json(
          500, Map.of("error", "admin operation failed (" + e.getClass().getSimpleName() + ")"));
    }
  }

  private McpHttpResponse releaseResult(CallerIdentity caller, String handle) {
    if (resultReleaseGroups.isEmpty() || !caller.inAnyGroup(resultReleaseGroups)) {
      return McpHttpResponse.json(403, Map.of("error", "caller is not in a result release group"));
    }
    if (!caller.actorChain().isEmpty()
        || !ApprovalService.identityKey(caller.actor())
            .equals(ApprovalService.identityKey(caller.effectiveSubject()))) {
      return McpHttpResponse.json(
          403, Map.of("error", "result release must be made by a non-delegated identity"));
    }
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("handle", handle);
    try {
      engine.journalAdministrative("RESULT_RELEASE_INTENT", caller, details);
    } catch (Exception e) {
      return McpHttpResponse.json(
          503,
          Map.of("error", "the audit journal could not record this release, so it was not made"));
    }
    if (engine.resultHandles().producer(handle).map(caller.principalKey()::equals).orElse(false)) {
      details.put("refused", "the producer of a handle cannot release it");
      try {
        engine.journalAdministrative("RESULT_RELEASE_REFUSED", caller, details);
      } catch (Exception ignored) {
        // refusal stands
      }
      return McpHttpResponse.json(
          403, Map.of("error", "the identity that produced this handle cannot release it"));
    }
    java.util.Optional<io.github.vaquarkhan.aegis.engine.control.ResultHandleStore.Entry> taken =
        engine.resultHandles().take(handle);
    if (taken.isEmpty()) {
      return McpHttpResponse.json(
          404, Map.of("error", "unknown, expired or already released handle"));
    }
    io.github.vaquarkhan.aegis.engine.control.ResultHandleStore.Entry e = taken.get();
    details.put("tool", e.tool());
    details.put("requestId", e.requestId());
    try {
      engine.journalAdministrative("RESULT_RELEASE_RESULT", caller, details);
    } catch (Exception ignored) {
      // intent already journaled
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("handle", handle);
    out.put("tool", e.tool());
    out.put("requestId", e.requestId());
    out.put("result", e.body());
    return McpHttpResponse.json(200, out);
  }

  /**
   * Token revocation: only revoker groups, only a non-delegated identity, and the intent is
   * journaled before the entry is written (if the journal is down, nothing is revoked and the
   * operator is told, so they can retry or revoke at the identity provider).
   */
  private McpHttpResponse revoke(
      McpHttpRequest req, CallerIdentity caller, String kind, String value) {
    if (revokerGroups.isEmpty() || !caller.inAnyGroup(revokerGroups)) {
      return McpHttpResponse.json(403, Map.of("error", "caller is not in a revoker group"));
    }
    if (!caller.actorChain().isEmpty()
        || !ApprovalService.identityKey(caller.actor())
            .equals(ApprovalService.identityKey(caller.effectiveSubject()))) {
      return McpHttpResponse.json(
          403, Map.of("error", "revocation must be made by a non-delegated identity"));
    }
    if (value == null || value.isBlank() || value.length() > 1024) {
      return McpHttpResponse.json(400, Map.of("error", kind + " is required"));
    }
    String issuer = caller.issuer();
    if (req.headerCount("Aegis-Token-Issuer") > 1) {
      return McpHttpResponse.json(400, Map.of("error", "at most one Aegis-Token-Issuer header"));
    }
    if (req.header("Aegis-Token-Issuer") != null && !req.header("Aegis-Token-Issuer").isBlank()) {
      issuer = req.header("Aegis-Token-Issuer").trim();
    }
    if (!revocations.acceptsIssuer(issuer)) {
      // O-7: refuse a revocation no verifier would ever consult (for example a static-token admin
      // revoking under issuer aegis-static) instead of answering 200 and revoking nothing useful.
      return McpHttpResponse.json(
          400,
          Map.of(
              "error",
              "no token verifier on this gateway consults issuer '"
                  + issuer
                  + "'; name the JWT issuer in Aegis-Token-Issuer"));
    }
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("kind", kind);
    details.put("issuer", issuer);
    // The value is journaled as a digest: a token id or subject is an identifier, not an audit
    // secret,
    // but the digest is enough to correlate with the identity provider and keeps the journal
    // minimal.
    details.put("valueDigest", io.github.vaquarkhan.aegis.engine.model.Digests.sha256Hex(value));
    try {
      engine.journalAdministrative("TOKEN_REVOKE_INTENT", caller, details);
    } catch (Exception e) {
      return McpHttpResponse.json(
          503,
          Map.of(
              "error", "the audit journal could not record this revocation, so it was not made"));
    }
    try {
      if ("subject".equals(kind)) {
        java.time.Instant cutoff = revocations.revokeSubject(issuer, value, revocationRetention);
        details.put("issuedAtOrBefore", cutoff.toString());
      } else {
        revocations.revokeToken(issuer, value, clock.instant().plus(revocationRetention));
      }
    } catch (IllegalArgumentException e) {
      return McpHttpResponse.json(400, Map.of("error", e.getMessage()));
    }
    try {
      engine.journalAdministrative("TOKEN_REVOKE_RESULT", caller, details);
    } catch (Exception e) {
      // The intent record already names the operator and the entry; the result is best effort.
    }
    return McpHttpResponse.json(200, Map.of("revoked", kind, "issuer", issuer));
  }

  /**
   * Accepting a quarantined definition re-trusts content the model will read, so it is treated like
   * an approval: only a non-delegated human identity may do it, and the intent is journaled before
   * the change is made. If the journal cannot record the intent, the definition stays quarantined.
   */
  private McpHttpResponse acceptDefinition(
      String event,
      CallerIdentity caller,
      Map<String, Object> details,
      java.util.concurrent.Callable<Boolean> accept,
      String conflict)
      throws Exception {
    if (!caller.actorChain().isEmpty()
        || !ApprovalService.identityKey(caller.actor())
            .equals(ApprovalService.identityKey(caller.effectiveSubject()))) {
      Map<String, Object> refused = new LinkedHashMap<>(details);
      refused.put("reason", "definition acceptance must be made by a non-delegated identity");
      try {
        engine.journalAdministrative(event + "_REFUSED", caller, refused);
      } catch (Exception journalDown) {
        // Refusal stands either way; nothing changed.
      }
      return McpHttpResponse.json(
          403, Map.of("error", "definition acceptance must be made by a non-delegated identity"));
    }
    try {
      engine.journalAdministrative(event + "_INTENT", caller, details);
    } catch (Exception e) {
      return McpHttpResponse.json(
          503,
          Map.of("error", "the audit journal could not record this change, so it was not made"));
    }
    boolean ok = accept.call();
    Map<String, Object> result = new LinkedHashMap<>(details);
    result.put("accepted", ok);
    try {
      engine.journalAdministrative(event + "_RESULT", caller, result);
    } catch (Exception e) {
      // The intent record already names the operator and the definition; the result is best effort.
    }
    String name = String.valueOf(details.getOrDefault("tool", details.get("prompt")));
    return ok
        ? McpHttpResponse.json(200, Map.of("accepted", name))
        : McpHttpResponse.json(409, Map.of("error", conflict));
  }
}
