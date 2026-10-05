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

import io.github.vaquarkhan.aegis.engine.audit.AuditVerifier;
import io.github.vaquarkhan.aegis.engine.GovernanceEngine;
import io.github.vaquarkhan.aegis.engine.approval.ApprovalService;
import io.github.vaquarkhan.aegis.engine.catalog.ToolCatalog;
import io.github.vaquarkhan.aegis.engine.content.ContentGovernor;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.mcp.http.McpHttpRequest;
import io.github.vaquarkhan.aegis.mcp.http.McpHttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Human-facing governance API, mounted by each host under its own path
 * ({@code /aegis/v1/...} on the gateway's own server, {@code /gateway/<topology>/aegis-mcp/v1/admin/...} in Knox).
 * Every route requires an authenticated caller; approval decisions and catalog acceptance require
 * an approver group and are refused for the requester (separation of duties).
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

    public AdminApi(GovernanceEngine engine, Set<String> approverGroups, Set<String> allowedOrigins) {
        this(engine, null, approverGroups, Set.of(), null, allowedOrigins);
    }

    /** @param content prompt catalog for {@code /prompts} routes; null when no prompts are configured */
    public AdminApi(GovernanceEngine engine, ContentGovernor content, Set<String> approverGroups, Set<String> allowedOrigins) {
        this(engine, content, approverGroups, Set.of(), null, allowedOrigins);
    }

    /**
     * @param auditVerifierGroups callers allowed to learn only whether the fixed configured journal is intact
     * @param auditVerifier fixed-journal verifier; null disables the route (404)
     */
    public AdminApi(GovernanceEngine engine, ContentGovernor content, Set<String> approverGroups,
            Set<String> auditVerifierGroups, AuditVerifier auditVerifier, Set<String> allowedOrigins) {
        this.engine = engine;
        this.content = content;
        this.approverGroups = Set.copyOf(approverGroups);
        this.auditVerifierGroups = auditVerifierGroups == null ? Set.of() : Set.copyOf(auditVerifierGroups);
        this.auditVerifier = auditVerifier;
        this.allowedOrigins = allowedOrigins == null ? Set.of() : Set.copyOf(allowedOrigins);
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
                if (parts.length == 3 && "POST".equals(req.method()) && ("approve".equals(parts[2]) || "deny".equals(parts[2]))) {
                    return McpHttpResponse.json(200, approvals.decide(parts[1], caller, "approve".equals(parts[2])));
                }
            }
            if (parts.length == 2 && "audit".equals(parts[0]) && "verify".equals(parts[1])
                    && "GET".equals(req.method()) && auditVerifier != null) {
                if (!caller.inAnyGroup(auditVerifierGroups)) {
                    return McpHttpResponse.json(403, Map.of("error", "caller is not in an audit verifier group"));
                }
                try {
                    return auditVerifier.verify() ? McpHttpResponse.json(200, Map.of("verified", true))
                            : McpHttpResponse.json(409, Map.of("verified", false));
                } catch (java.io.IOException e) {
                    // The endpoint intentionally withholds the configured file name and verifier diagnostics.
                    return McpHttpResponse.json(503, Map.of("error", "audit verification unavailable"));
                }
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
                    if (req.headerCount("Aegis-Definition-Digest") != 1 || expectedDigest == null || expectedDigest.isBlank()) {
                        return McpHttpResponse.json(428, Map.of("error", "Aegis-Definition-Digest is required"));
                    }
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("tool", parts[1]);
                    details.put("expectedDigest", expectedDigest);
                    catalog.lookup(parts[1]).ifPresent(e -> {
                        details.put("source", e.tool().sourceId());
                        details.put("toolClass", e.tool().toolClass().name());
                        details.put("digest", e.digest());
                        details.put("quarantineReason", e.reason());
                    });
                    return acceptDefinition("CATALOG_ACCEPT", caller, details, () -> catalog.accept(parts[1], expectedDigest),
                            "tool is not quarantined, unknown, pinned in configuration, or no longer has the reviewed digest");
                }
            }
            if (parts.length >= 1 && "prompts".equals(parts[0]) && content != null && content.offersPrompts()) {
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
                    if (req.headerCount("Aegis-Definition-Digest") != 1 || expectedDigest == null || expectedDigest.isBlank()) {
                        return McpHttpResponse.json(428, Map.of("error", "Aegis-Definition-Digest is required"));
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
                    return acceptDefinition("PROMPT_ACCEPT", caller, details, () -> content.acceptPrompt(parts[1], expectedDigest),
                            "prompt is not quarantined, unknown, pinned in configuration, or no longer has the reviewed digest");
                }
            }
            return McpHttpResponse.json(404, Map.of("error", "not found"));
        } catch (ApprovalService.ApprovalException e) {
            return McpHttpResponse.json(e.status(), Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return McpHttpResponse.json(500, Map.of("error", "admin operation failed (" + e.getClass().getSimpleName() + ")"));
        }
    }

    /**
     * Accepting a quarantined definition re-trusts content the model will read, so it is treated like
     * an approval: only a non-delegated human identity may do it, and the intent is journaled before the
     * change is made. If the journal cannot record the intent, the definition stays quarantined.
     */
    private McpHttpResponse acceptDefinition(String event, CallerIdentity caller, Map<String, Object> details,
                                             java.util.concurrent.Callable<Boolean> accept, String conflict) throws Exception {
        if (!caller.actorChain().isEmpty()
                || !ApprovalService.identityKey(caller.actor()).equals(ApprovalService.identityKey(caller.effectiveSubject()))) {
            Map<String, Object> refused = new LinkedHashMap<>(details);
            refused.put("reason", "definition acceptance must be made by a non-delegated identity");
            try {
                engine.journalAdministrative(event + "_REFUSED", caller, refused);
            } catch (Exception journalDown) {
                // Refusal stands either way; nothing changed.
            }
            return McpHttpResponse.json(403, Map.of("error", "definition acceptance must be made by a non-delegated identity"));
        }
        try {
            engine.journalAdministrative(event + "_INTENT", caller, details);
        } catch (Exception e) {
            return McpHttpResponse.json(503, Map.of("error", "the audit journal could not record this change, so it was not made"));
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
        return ok ? McpHttpResponse.json(200, Map.of("accepted", name)) : McpHttpResponse.json(409, Map.of("error", conflict));
    }
}
