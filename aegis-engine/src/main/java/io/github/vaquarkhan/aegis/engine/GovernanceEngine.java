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
package io.github.vaquarkhan.aegis.engine;

import io.github.vaquarkhan.aegis.engine.approval.ApprovalService;
import io.github.vaquarkhan.aegis.engine.approval.VrpService;
import io.github.vaquarkhan.aegis.engine.catalog.DryRunSpec;
import io.github.vaquarkhan.aegis.engine.catalog.GovernedTool;
import io.github.vaquarkhan.aegis.engine.catalog.ResourceRule;
import io.github.vaquarkhan.aegis.engine.catalog.ToolCatalog;
import io.github.vaquarkhan.aegis.engine.control.CircuitBreakers;
import io.github.vaquarkhan.aegis.engine.control.ControlMode;
import io.github.vaquarkhan.aegis.engine.control.EgressGuard;
import io.github.vaquarkhan.aegis.engine.control.HookRunner;
import io.github.vaquarkhan.aegis.engine.control.OutputGuard;
import io.github.vaquarkhan.aegis.engine.control.SchemaValidator;
import io.github.vaquarkhan.aegis.engine.control.ScopeRule;
import io.github.vaquarkhan.aegis.engine.model.Action;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.model.ControlTokens;
import io.github.vaquarkhan.aegis.engine.model.Decision;
import io.github.vaquarkhan.aegis.engine.model.DenyCodes;
import io.github.vaquarkhan.aegis.engine.model.Digests;
import io.github.vaquarkhan.aegis.engine.model.ToolCall;
import io.github.vaquarkhan.aegis.engine.model.ToolClass;
import io.github.vaquarkhan.aegis.engine.model.ToolResult;
import io.github.vaquarkhan.aegis.engine.model.TraceContext;
import io.github.vaquarkhan.aegis.engine.spi.DecisionListener;
import io.github.vaquarkhan.aegis.engine.spi.AuditSink;
import io.github.vaquarkhan.aegis.engine.spi.CredentialProvider;
import io.github.vaquarkhan.aegis.engine.spi.ExecutionContext;
import io.github.vaquarkhan.aegis.engine.spi.GovernanceHook;
import io.github.vaquarkhan.aegis.engine.spi.PolicyDecisionPoint;
import io.github.vaquarkhan.aegis.engine.spi.StateStore;
import io.github.vaquarkhan.aegis.engine.spi.ToolExecutor;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The governance chain for MCP {@code tools/call}, independent of transport (HTTP, stdio) and of
 * host (the gateway's own server, Apache Knox, an engine's own process).
 *
 * <p>Evaluation order. The 0.1 step numbers are kept in audit records ({@code DENIED:<code>:step<N>});
 * the one deliberate reordering is that approval (step 4) is evaluated after every automated
 * control, so approvers only see calls that automation would allow:
 * <pre>
 *  1 exposure      tool exists, is not quarantined, caller may see it, write unlock
 *  2 input+scope   control values split out, schema subset, resources by JSON pointer, scopes
 *  3 policy        PDP over the full canonical action (deny-overrides, default deny)
 *  5 egress        declared targets and nested HTTP(S) URLs; every DNS answer returned by the check
 *  6 rate/budget   atomic counters, charged for every admitted attempt
 *  7 breaker       per source, half-open single probe
 *  8 screening     injection phrases at any depth; then extension hooks, in order
 *  4 approval      per-call token bound to caller + tool + argument digest (verify only)
 *  9 VRP           dry-run receipt for DESTRUCTIVE (verify only)
 *    commit        consume approval and receipt nonces atomically, then audit ALLOW_INTENT
 *                  (audit failure denies: AUDIT_UNAVAILABLE)
 * 10 execute       credentials resolved now (never before), executor runs, output guarded,
 *                  EXECUTION_RESULT audited
 * </pre>
 * Any exception inside a control is a denial (fail-closed).
 */
public final class GovernanceEngine implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(GovernanceEngine.class.getName());

    private final GovernanceSettings settings;
    private final ToolCatalog catalog;
    private final PolicyDecisionPoint pdp;
    private final ApprovalService approvals;
    private final VrpService vrp;
    private final StateStore state;
    private final AuditSink audit;
    private final CredentialProvider credentials;
    private final CircuitBreakers breakers;
    private final Clock clock;
    private final boolean checkArgumentUrls;
    private final HookRunner hooks;
    private final SecureRandom random = new SecureRandom();

    public GovernanceEngine(GovernanceSettings settings, ToolCatalog catalog, PolicyDecisionPoint pdp,
                            ApprovalService approvals, VrpService vrp, StateStore state, AuditSink audit,
                            CredentialProvider credentials, CircuitBreakers breakers, Clock clock,
                            boolean checkArgumentUrls) {
        this.settings = settings;
        this.catalog = catalog;
        this.pdp = pdp;
        this.approvals = approvals;
        this.vrp = vrp;
        this.state = state;
        this.audit = audit;
        this.credentials = credentials == null ? CredentialProvider.NONE : credentials;
        this.breakers = breakers;
        this.clock = clock;
        this.checkArgumentUrls = checkArgumentUrls;
        this.hooks = new HookRunner(settings.hooks());
    }

    public GovernanceSettings settings() {
        return settings;
    }

    public ToolCatalog catalog() {
        return catalog;
    }

    public ApprovalService approvals() {
        return approvals;
    }

    /**
     * Writes one administrative event (catalog or prompt acceptance, for example) to the audit journal
     * of this gateway, adding the timestamp, gateway name and the operator's identity. Throws when the
     * journal cannot record it; callers use that to refuse the administrative change (fail-closed), the
     * same rule that gates tool execution on ALLOW_INTENT.
     */
    public void journalAdministrative(String event, CallerIdentity operator, Map<String, Object> details) throws Exception {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ts", clock.instant().toString());
        r.put("gateway", settings.gatewayName());
        r.put("event", event);
        r.put("operator", operator.actor());
        r.put("operatorIssuer", operator.issuer());
        r.put("operatorAuthMethod", operator.authMethod());
        if (!operator.actorChain().isEmpty()) {
            r.put("operatorActorChain", operator.actorChain());
        }
        r.putAll(details);
        audit.append(r);
    }

    // ------------------------------------------------------------------ tools/list

    /** Tools this caller may see: active, and exposure passes. Deterministic order (by name). */
    public List<Map<String, Object>> listTools(CallerIdentity caller) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ToolCatalog.Entry e : catalog.entries()) {
            if (e.active() && exposure(caller, e.tool()) == null) {
                out.add(e.tool().advertise(settings.advertiseControlArguments()));
            }
        }
        return out;
    }

    /**
     * What a call would meet, without running it: steps 1 to 3 exactly as {@link #call} runs them, then
     * whether approval and a dry-run receipt would be required. Nothing is journaled, no state changes, no
     * backend or credential is touched, and listeners are not notified. Used by the offline policy test.
     */
    public Explanation explain(CallerIdentity caller, ToolCall call) {
        Trace t = new Trace("explain", call, caller, clock.millis(), TraceContext.from(call.meta(), null));
        Decision denial;
        try {
            denial = preflight(t);
        } catch (RuntimeException e) {
            denial = Decision.deny(DenyCodes.POLICY_DENIED, DenyCodes.STEP_POLICY, "internal governance error");
        }
        ToolClass cls = t.tool == null ? null : t.tool.toolClass();
        List<String> resources = t.action == null ? List.of() : t.action.resources();
        if (denial != null) {
            return new Explanation(denial, cls, false, false, resources);
        }
        boolean dry = t.tokens != null && t.tokens.dryRun();
        boolean approval = !dry && settings.approvalRequiredFor().contains(cls);
        boolean receipt = !dry && settings.vrpEnabled() && cls == ToolClass.DESTRUCTIVE;
        return new Explanation(Decision.allow(), cls, approval, receipt, resources);
    }

    /**
     * Result of {@link #explain}.
     *
     * @param decision              the steps 1 to 3 decision
     * @param toolClass             class of the tool, or null when the tool is unknown
     * @param approvalRequired      an allowed call would still need a per-call approval
     * @param dryRunReceiptRequired an allowed call would still need a fresh dry-run receipt
     * @param resources             typed resources extracted from the arguments
     */
    public record Explanation(Decision decision, ToolClass toolClass, boolean approvalRequired,
                              boolean dryRunReceiptRequired, List<String> resources) {}

    // ------------------------------------------------------------------ tools/call

    public CallOutcome call(CallerIdentity caller, ToolCall call) {
        String rid = "req_" + randomHex(12);
        long t0 = clock.millis();
        Trace trace = new Trace(rid, call, caller, t0, TraceContext.from(call.meta(), null));
        trace.governance.put("traceId", trace.tc.traceId());
        try {
            return govern(trace);
        } catch (RuntimeException e) {
            // Defensive: any unexpected failure inside a control is a denial.
            LOG.log(System.Logger.Level.ERROR, "governance control failed for " + rid, e);
            return deny(trace, Decision.deny(DenyCodes.POLICY_DENIED, DenyCodes.STEP_POLICY, "internal governance error"));
        }
    }

    /**
     * Steps 1 to 3 (exposure, input and scope, policy). Shared by {@link #call} and {@link #explain}, so the
     * offline policy test evaluates exactly the code a real call runs. Writes nothing: no audit record, no
     * state, no listener. Fills the trace's tool, tokens, action, binding and backend arguments.
     *
     * @return the first denial, or null when steps 1 to 3 allow the call
     */
    private Decision preflight(Trace t) {
        ToolCall call = t.call;
        CallerIdentity caller = t.caller;

        // ---- step 1: exposure
        Optional<ToolCatalog.Entry> entry = catalog.lookup(call.toolName());
        if (entry.isEmpty()) {
            return Decision.deny(DenyCodes.NOT_EXPOSED, DenyCodes.STEP_EXPOSURE, "unknown tool");
        }
        GovernedTool tool = entry.get().tool();
        t.tool = tool;
        if (!entry.get().active()) {
            return Decision.deny(DenyCodes.CATALOG_INTEGRITY, DenyCodes.STEP_EXPOSURE,
                    "tool is quarantined: " + entry.get().reason());
        }
        String exposureCode = exposure(caller, tool);
        if (exposureCode != null) {
            return Decision.deny(exposureCode, DenyCodes.STEP_EXPOSURE,
                    exposureCode.equals(DenyCodes.READONLY_CALLER)
                            ? "caller is not in a group allowed to call " + tool.toolClass() + " tools"
                            : "tool is not exposed to this caller");
        }

        // ---- step 2: input and scope
        ControlTokens.Split split;
        try {
            split = ControlTokens.split(call.meta(), call.arguments(), tool.declaredProperties());
        } catch (IllegalArgumentException e) {
            return Decision.deny(DenyCodes.INVALID_INPUT, DenyCodes.STEP_SCOPE, e.getMessage());
        }
        Map<String, Object> args = split.backendArguments();
        ControlTokens tokens = split.tokens();
        t.tokens = tokens;
        t.dryRun = tokens.dryRun();
        String schemaError = SchemaValidator.validate(tool.descriptor().inputSchema(), args);
        if (schemaError != null) {
            return Decision.deny(DenyCodes.INVALID_INPUT, DenyCodes.STEP_SCOPE, "arguments do not match the tool schema: " + schemaError);
        }
        if (tokens.dryRun() && tool.toolClass() == ToolClass.READ) {
            return Decision.deny(DenyCodes.INVALID_INPUT, DenyCodes.STEP_SCOPE, "dry run applies to write tools only");
        }
        List<String> resources = new ArrayList<>();
        for (ResourceRule rule : tool.resources()) {
            ResourceRule.Extraction ex = rule.extract(args);
            if (!ex.ok()) {
                return Decision.deny(DenyCodes.SCOPE_DENIED, DenyCodes.STEP_SCOPE, ex.error());
            }
            resources.addAll(ex.resources());
        }
        Action action = new Action(tool.exposedName(), tool.toolClass(), tool.sourceId(), resources,
                Digests.canonicalSha256(args), List.copyOf(tool.actionScopes()));
        t.action = action;
        t.binding = action.binding(settings.gatewayName(), entry.get().digest());
        t.backendArgs = args;
        if (!caller.scopes().containsAll(action.requiredActionScopes())) {
            List<String> missingScopes = action.requiredActionScopes().stream()
                    .filter(scope -> !caller.scopes().contains(scope)).toList();
            // Lets a front door that runs OAuth step-up name these scopes in its challenge.
            io.github.vaquarkhan.aegis.engine.spi.ScopeChallenge.require(action.requiredActionScopes());
            return Decision.deny(DenyCodes.SCOPE_DENIED, DenyCodes.STEP_SCOPE,
                    "caller lacks a required action scope", Map.of("requiredActionScopes", action.requiredActionScopes(),
                            "missingActionScopes", missingScopes));
        }
        if (!settings.scopes().isEmpty() && resources.isEmpty() && !tool.resources().isEmpty()) {
            // The tool governs resources, but this call named none (optional pointers omitted):
            // with scopes configured that must not become "unscoped".
            return Decision.deny(DenyCodes.SCOPE_DENIED, DenyCodes.STEP_SCOPE,
                    "this tool is resource-scoped and the call names no resource");
        }
        if (!settings.scopes().isEmpty() && !resources.isEmpty()) {
            String out = ScopeRule.firstOutOfScope(settings.scopes(), caller, resources);
            if (out != null) {
                return Decision.deny(DenyCodes.SCOPE_DENIED, DenyCodes.STEP_SCOPE, "resource out of scope: " + out);
            }
        }

        // ---- step 3: policy
        try {
            PolicyDecisionPoint.Result r = pdp.decide(caller, action, args);
            if (r == null || !r.allow()) {
                return Decision.deny(DenyCodes.POLICY_DENIED, DenyCodes.STEP_POLICY,
                        r == null ? "policy returned no decision" : r.reason());
            }
        } catch (Exception e) {
            return Decision.deny(DenyCodes.POLICY_DENIED, DenyCodes.STEP_POLICY,
                    "policy evaluation failed (" + e.getClass().getSimpleName() + ")");
        }
        return null;
    }

    private CallOutcome govern(Trace t) {
        Decision preflightDenial = preflight(t);
        if (preflightDenial != null) {
            return deny(t, preflightDenial);
        }
        ToolCall call = t.call;
        CallerIdentity caller = t.caller;
        GovernedTool tool = t.tool;
        ControlTokens tokens = t.tokens;
        Map<String, Object> args = t.backendArgs;
        Action action = t.action;

        // ---- step 5: egress
        DryRunSpec spec = tool.dryRun();
        boolean previewTool = tokens.dryRun() && spec.mode() == DryRunSpec.Mode.TOOL;
        ToolExecutor executor = previewTool ? spec.previewExecutor() : tool.executor();
        try {
            List<URI> targets = new ArrayList<>(executor.egressTargets(args));
            if (checkArgumentUrls) {
                targets.addAll(EgressGuard.urlsIn(args));
                targets.addAll(EgressGuard.urlsIn(call.roundTrip().inputResponses()));
            }
            for (URI u : targets) {
                String why = settings.egressGuard().check(u);
                if (why != null) {
                    return deny(t, Decision.deny(DenyCodes.EGRESS_DENIED, DenyCodes.STEP_EGRESS, why));
                }
            }
        } catch (RuntimeException e) {
            return deny(t, Decision.deny(DenyCodes.EGRESS_DENIED, DenyCodes.STEP_EGRESS,
                    "egress target could not be determined (" + e.getClass().getSimpleName() + ")"));
        }

        // ---- step 6: rate and budget
        String rateCode = settings.rateLimiter().admit(state, caller, action, clock.instant());
        if (rateCode != null) {
            return deny(t, Decision.deny(rateCode, DenyCodes.STEP_RATE,
                    rateCode.equals(DenyCodes.RATE_LIMITED) ? "call rate limit reached" : "call budget exhausted"));
        }

        // ---- step 7: breaker
        CircuitBreakers.Permit permit = breakers.acquire(tool.sourceId());
        if (permit == CircuitBreakers.Permit.DENIED) {
            return deny(t, Decision.deny(DenyCodes.BREAKER_OPEN, DenyCodes.STEP_BREAKER, "backend circuit is open"));
        }
        t.permit = permit;

        // ---- step 8: screening, then extension hooks
        if (settings.argumentScreen().mode() != ControlMode.OFF) {
            Optional<String> hit = settings.argumentScreen().firstMatch(args);
            if (hit.isEmpty()) {
                // MRTR retry: the client's answers (elicitation, sampling output) reach the backend too.
                hit = settings.argumentScreen().firstMatch(call.roundTrip().inputResponses());
            }
            if (hit.isPresent()) {
                if (settings.argumentScreen().mode() == ControlMode.ENFORCE) {
                    return deny(t, Decision.deny(DenyCodes.PROMPT_INJECTION, DenyCodes.STEP_INJECTION,
                            "arguments match an injection pattern"));
                }
                t.governance.put("screeningMatch", hit.get());
            }
        }
        GovernanceHook.Context hookCtx = new GovernanceHook.Context(t.requestId, t.tc.traceId(), caller,
                GovernanceHook.Operation.TOOL_CALL, tool.exposedName(), tool, tool.sourceId(), action, args,
                tokens.dryRun());
        Decision hookDenial = hooks.before(hookCtx, DenyCodes.STEP_INJECTION, t.hookAudit);
        if (hookDenial != null) {
            return deny(t, hookDenial);
        }

        // ---- step 4: approval (verify only; consumed at commit). Evaluated AFTER the automated
        // controls on purpose, so a human is never asked to approve a call that egress, rate,
        // breaker, screening or a hook would reject anyway. Step number stays 4 (0.1 audit format).
        ApprovalService.Check approval = null;
        if (!tokens.dryRun() && settings.approvalRequiredFor().contains(tool.toolClass())) {
            if (tokens.approvalToken() == null) {
                if (!approvals.configured()) {
                    return deny(t, Decision.deny(DenyCodes.APPROVAL_REQUIRED, DenyCodes.STEP_APPROVAL,
                            "approval is required but not configured on this gateway"));
                }
                Map<String, Object> req;
                try {
                    req = approvals.request(caller, action, t.binding, approverEvidence(args, null));
                } catch (ApprovalService.ApprovalException e) {
                    return deny(t, Decision.deny(DenyCodes.APPROVAL_REQUIRED, DenyCodes.STEP_APPROVAL, e.getMessage()));
                }
                return deny(t, Decision.deny(DenyCodes.APPROVAL_REQUIRED, DenyCodes.STEP_APPROVAL,
                        "this " + tool.toolClass() + " call needs a per-call approval",
                        Map.of("approvalRequestId", req.get("id"), "argsDigest", action.argsDigest())));
            }
            approval = approvals.verify(caller, action, t.binding, tokens.approvalToken());
            if (!approval.ok()) {
                return deny(t, Decision.deny(DenyCodes.APPROVAL_REQUIRED, DenyCodes.STEP_APPROVAL, approval.reason()));
            }
            t.approvedBy = approval.approver();
        }

        // ---- step 9: VRP (verify only)
        VrpService.Check receipt = null;
        boolean vrpApplies = settings.vrpEnabled() && tool.toolClass() == ToolClass.DESTRUCTIVE;
        if (vrpApplies) {
            if (!vrp.configured()) {
                return deny(t, Decision.deny(DenyCodes.VRP_FAILED, DenyCodes.STEP_VRP, "VRP is required but not configured"));
            }
            if (!tokens.dryRun()) {
                receipt = vrp.verify(caller, action, t.binding, tokens.dryRunReceipt());
                if (!receipt.ok()) {
                    return deny(t, Decision.deny(DenyCodes.VRP_FAILED, DenyCodes.STEP_VRP, receipt.reason()));
                }
            }
        }

        // ---- commit: consume single-use values, then record durable intent. Promotion is at-most-once:
        // if the intent journal fails after a consume, the backend is still not reached but the caller
        // must obtain any already consumed approval or receipt again.
        boolean approvalConsumed = false;
        boolean receiptConsumed = false;
        if (approval != null && !approvals.consume(approval, caller, t.binding)) {
            return deny(t, Decision.deny(DenyCodes.APPROVAL_REQUIRED, DenyCodes.STEP_APPROVAL, "approval token has already been used"));
        }
        approvalConsumed = approval != null;
        if (receipt != null && !vrp.consume(receipt)) {
            return deny(t, Decision.deny(DenyCodes.VRP_FAILED, DenyCodes.STEP_VRP, "dry-run receipt has already been used"));
        }
        receiptConsumed = receipt != null;
        try {
            audit.append(record(t, "ALLOW_INTENT", Decision.allow()));
        } catch (Exception e) {
            LOG.log(System.Logger.Level.ERROR, "audit journal unavailable; denying " + t.requestId, e);
            Map<String, Object> recovery = new LinkedHashMap<>();
            if (approvalConsumed) {
                recovery.put("freshApprovalRequired", true);
            }
            if (receiptConsumed) {
                recovery.put("freshDryRunReceiptRequired", true);
            }
            String reason = recovery.isEmpty()
                    ? "the audit journal could not record this call, so it was not executed"
                    : "the audit journal could not record this call, so it was not executed; obtain fresh consumed controls before retrying";
            return deny(t, Decision.deny(DenyCodes.AUDIT_UNAVAILABLE, DenyCodes.STEP_EXECUTE, reason, recovery));
        }

        // ---- step 10: execute
        if (tokens.dryRun() && spec.mode() == DryRunSpec.Mode.GATEWAY) {
            breakers.release(tool.sourceId(), t.permit);
            t.permit = null;
            ToolResult preview = ToolResult.text("Dry run: this exact " + tool.toolClass() + " call passed every Aegis governance check. "
                    + "Nothing was sent to the backend.", false);
            return completeDryRun(t, preview);
        }
        Map<String, String> creds;
        try {
            creds = credentials.credentialsFor(tool.sourceId(), caller);
        } catch (Exception e) {
            return fail(t, DenyCodes.BACKEND_ERROR, "backend credentials could not be resolved (" + e.getClass().getSimpleName() + ")", false);
        }
        Map<String, Object> execArgs = args;
        if (tokens.dryRun() && spec.mode() == DryRunSpec.Mode.ARGUMENT) {
            Map<String, Object> m = new LinkedHashMap<>(args);
            m.put(spec.argument(), Boolean.TRUE);
            execArgs = java.util.Collections.unmodifiableMap(m);
        }
        String upstream = previewTool && spec.previewToolName() != null ? spec.previewToolName() : tool.upstreamName();
        ExecutionContext ctx = new ExecutionContext(t.requestId, upstream, execArgs, caller, creds, tool.timeoutMillis(), tokens.dryRun(), t.tc.header(),
                call.roundTrip());
        ToolResult result;
        try {
            result = executor.execute(ctx);
            if (result == null) {
                throw new ToolExecutor.ExecutionException("executor returned no result");
            }
            breakers.success(tool.sourceId(), t.permit);
            t.permit = null;
        } catch (ToolExecutor.ExecutionException e) {
            if (e.availabilityFailure()) {
                breakers.failure(tool.sourceId(), t.permit);
            } else {
                breakers.release(tool.sourceId(), t.permit);
            }
            t.permit = null;
            return fail(t, e.timeout() ? DenyCodes.TIMEOUT : DenyCodes.BACKEND_ERROR, e.getMessage(), true);
        } catch (RuntimeException e) {
            breakers.failure(tool.sourceId(), t.permit);
            t.permit = null;
            return fail(t, DenyCodes.BACKEND_ERROR, "backend call failed (" + e.getClass().getSimpleName() + ")", true);
        }
        result = hooks.afterTool(hookCtx, result, t.hookAudit);
        OutputGuard.Guarded guarded = settings.outputGuard().apply(result);
        if (guarded.binaryRefused()) {
            // Binary parts are opaque to redaction and screening, so they reach the model only when the
            // operator allows them. The backend already ran; the result is withheld and the call recorded.
            t.redactions = guarded.redactions();
            return fail(t, DenyCodes.POLICY_DENIED,
                    "the result contains binary content, which this gateway does not pass (output.allowBinary)", true);
        }
        t.redactions = guarded.redactions();
        t.truncated = guarded.truncated();
        t.resultDigest = Digests.canonicalSha256(guarded.result().body());
        t.isError = guarded.result().isError();
        if (tokens.dryRun()) {
            return completeDryRun(t, guarded.result());
        }
        auditBestEffort(record(t, "EXECUTION_RESULT", Decision.allow()));
        notifyListeners(t, Decision.allow(), true);
        return new CallOutcome(t.requestId, Decision.allow(), guarded.result(), t.governance);
    }

    private CallOutcome completeDryRun(Trace t, ToolResult preview) {
        if (!preview.isError()) {
            if (settings.vrpEnabled() && t.tool.toolClass() == ToolClass.DESTRUCTIVE) {
                VrpService.Receipt r = vrp.issue(t.caller, t.action, t.binding);
                t.governance.put("dryRunReceipt", r.token());
                t.governance.put("dryRunReceiptExpiresAt", r.expiresAt().toString());
            }
            if (settings.approvalRequiredFor().contains(t.tool.toolClass()) && approvals.configured()) {
                try {
                    Map<String, Object> req = approvals.request(t.caller, t.action, t.binding,
                            approverEvidence(t.backendArgs, preview));
                    t.governance.put("approvalRequestId", req.get("id"));
                } catch (ApprovalService.ApprovalException e) {
                    t.governance.put("approvalRequestError", e.getMessage());
                }
            }
        }
        t.governance.put("argsDigest", t.action.argsDigest());
        auditBestEffort(record(t, "DRY_RUN_RESULT", Decision.allow()));
        notifyListeners(t, Decision.allow(), t.tool.dryRun().mode() != DryRunSpec.Mode.GATEWAY);
        return new CallOutcome(t.requestId, Decision.allow(), preview, t.governance);
    }

    private CallOutcome fail(Trace t, String code, String message, boolean reachedBackend) {
        if (t.permit != null) {
            breakers.release(t.tool.sourceId(), t.permit);
            t.permit = null;
        }
        // A backend failure message can echo a token, connection string or row data. Successful output
        // is redacted by OutputGuard; the error path must be too, because this reason reaches the caller
        // and the audit journal. Redaction masks only secret shapes, so Aegis's own wording is unchanged.
        String safeMessage = message == null
                ? "backend failure"
                : String.valueOf(settings.outputGuard().redactAll(message).value());
        Decision d = Decision.deny(code, DenyCodes.STEP_EXECUTE, safeMessage);
        t.governance.put("reachedBackend", reachedBackend);
        auditBestEffort(record(t, "EXECUTION_RESULT", d));
        notifyListeners(t, d, reachedBackend);
        return new CallOutcome(t.requestId, d, null, t.governance);
    }

    private CallOutcome deny(Trace t, Decision d) {
        if (t.permit != null && t.tool != null) {
            breakers.release(t.tool.sourceId(), t.permit);
            t.permit = null;
        }
        auditBestEffort(record(t, "DECISION", d));
        notifyListeners(t, d, false);
        return new CallOutcome(t.requestId, d, null, t.governance);
    }

    private void notifyListeners(Trace t, Decision d, boolean reachedBackend) {
        if (settings.listeners().isEmpty()) {
            return;
        }
        DecisionListener.Span span = new DecisionListener.Span(t.tc.spanId(), t.tc.parentSpanId(), t.startMillis);
        DecisionListener.Event e = new DecisionListener.Event(t.requestId, t.tc.traceId(),
                t.tool != null ? t.tool.exposedName() : safe(t.call.toolName()), "tool",
                t.tool != null ? t.tool.toolClass().name() : "", t.tool != null ? t.tool.sourceId() : "",
                d.allowed() ? DenyCodes.ALLOWED : d.code(), d.step(), reachedBackend, clock.millis() - t.startMillis, span);
        io.github.vaquarkhan.aegis.engine.observe.Listeners.notify(settings.listeners(), e);
    }

    /** @return null when exposed, else NOT_EXPOSED or READONLY_CALLER */
    private String exposure(CallerIdentity caller, GovernedTool tool) {
        if (!tool.allowedGroups().isEmpty() && !caller.inAnyGroup(tool.allowedGroups())) {
            return DenyCodes.NOT_EXPOSED;
        }
        if (tool.toolClass() == ToolClass.MUTATE && !caller.inAnyGroup(settings.writeGroups())) {
            return DenyCodes.READONLY_CALLER;
        }
        if (tool.toolClass() == ToolClass.DESTRUCTIVE && !caller.inAnyGroup(settings.destructiveGroups())) {
            return DenyCodes.READONLY_CALLER;
        }
        return null;
    }

    /** Evidence bound on an approval request: bounded like the audit arguments, redacted like output. */
    static final int MAX_EVIDENCE_BYTES = 8 * 1024;

    /**
     * What a human approver is shown: the backend arguments and, after a dry run, the preview result,
     * both passed through the output redaction patterns and bounded. Without this an approver would be
     * deciding on a digest.
     */
    private Map<String, Object> approverEvidence(Map<String, Object> args, ToolResult preview) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("arguments", boundedEvidence(settings.outputGuard().redactAll(args == null ? Map.of() : args).value()));
        if (preview != null) {
            ev.put("preview", boundedEvidence(settings.outputGuard().redactAll(preview.body()).value()));
        }
        return ev;
    }

    private static Object boundedEvidence(Object redacted) {
        String json = io.github.vaquarkhan.aegis.engine.json.Json.write(redacted);
        if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= MAX_EVIDENCE_BYTES) {
            return redacted;
        }
        byte[] b = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return Map.of("truncated", true, "bytes", (long) b.length,
                "head", new String(b, 0, MAX_EVIDENCE_BYTES / 2, java.nio.charset.StandardCharsets.UTF_8));
    }

    private void auditBestEffort(Map<String, Object> rec) {
        try {
            audit.append(rec);
        } catch (Exception e) {
            // Denials and results are still returned; the missing record is logged loudly. ALLOW_INTENT
            // (above) is the record that gates execution, so a broken journal still stops new calls.
            LOG.log(System.Logger.Level.ERROR, "audit journal unavailable for " + rec.get("event") + " " + rec.get("requestId"), e);
        }
    }

    private Map<String, Object> record(Trace t, String event, Decision d) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ts", clock.instant().toString());
        r.put("gateway", settings.gatewayName());
        r.put("event", event);
        r.put("requestId", t.requestId);
        r.put("clientRequestId", safe(t.call.requestId()));
        r.put("traceId", t.tc.traceId());
        r.put("spanId", t.tc.spanId());
        if (t.tc.parentSpanId() != null) {
            r.put("parentSpanId", t.tc.parentSpanId());
        }
        r.put("protocolVersion", safe(t.call.protocolVersion()));
        r.put("actor", t.caller.actor());
        r.put("effectiveSubject", t.caller.effectiveSubject());
        r.put("issuer", t.caller.issuer());
        r.put("authMethod", t.caller.authMethod());
        if (!t.caller.actorChain().isEmpty()) {
            r.put("actorChain", t.caller.actorChain());
        }
        r.put("tool", t.tool != null ? t.tool.exposedName() : safe(t.call.toolName()));
        if (t.tool != null) {
            r.put("toolClass", t.tool.toolClass().name());
            r.put("source", t.tool.sourceId());
        }
        if (t.action != null) {
            r.put("resources", t.action.resources());
            r.put("argsDigest", t.action.argsDigest());
        }
        r.put("dryRun", t.dryRun);
        if (t.call.roundTrip().isRetry()) {
            r.put("roundTripRetry", true);
        }
        r.put("outcome", d.auditOutcome());
        if (d.denied()) {
            r.put("code", d.code());
            r.put("step", (long) d.step());
            r.put("reason", d.reason());
        }
        if (t.approvedBy != null) {
            r.put("approvedBy", t.approvedBy);
        }
        if (t.resultDigest != null) {
            r.put("resultDigest", t.resultDigest);
            r.put("isError", t.isError);
            r.put("redactions", (long) t.redactions);
            r.put("truncated", t.truncated);
        }
        Object screening = t.governance.get("screeningMatch");
        if (screening != null) {
            r.put("screeningMatch", screening);
        }
        if (!t.hookAudit.isEmpty()) {
            // What an AUDIT-mode hook would have done. The call was allowed; this is the dry-run trail
            // an operator reads before switching that hook to ENFORCE.
            r.put("hookAudit", List.copyOf(t.hookAudit));
        }
        if (settings.auditArguments() && t.call.arguments() != null) {
            r.put("arguments", t.call.arguments());
        }
        r.put("durationMs", clock.millis() - t.startMillis);
        return r;
    }

    private static String safe(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < 200; i++) {
            char c = s.charAt(i);
            sb.append(c < 0x20 || c == 0x7f ? '?' : c);
        }
        return sb.toString();
    }

    private String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    @Override
    public void close() {
        catalog.close();
        hooks.close();
        try {
            audit.close();
        } catch (Exception ignored) {
            // shutdown
        }
    }

    /** Mutable per-call trace; confined to one thread. */
    private static final class Trace {
        final String requestId;
        final ToolCall call;
        final CallerIdentity caller;
        final long startMillis;
        final TraceContext tc;
        final Map<String, Object> governance = new LinkedHashMap<>();
        final List<String> hookAudit = new ArrayList<>();
        GovernedTool tool;
        ControlTokens tokens;
        Action action;
        boolean dryRun;
        CircuitBreakers.Permit permit;
        String approvedBy;
        String binding;
        Map<String, Object> backendArgs;
        String resultDigest;
        boolean isError;
        int redactions;
        boolean truncated;

        Trace(String requestId, ToolCall call, CallerIdentity caller, long startMillis, TraceContext tc) {
            this.requestId = requestId;
            this.call = call;
            this.caller = caller;
            this.startMillis = startMillis;
            this.tc = tc;
        }
    }
}
