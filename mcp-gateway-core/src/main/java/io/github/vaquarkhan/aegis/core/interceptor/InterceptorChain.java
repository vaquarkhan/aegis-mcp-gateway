package io.github.vaquarkhan.aegis.core.interceptor;

import io.github.vaquarkhan.aegis.core.auth.CallerIdentity;
import io.github.vaquarkhan.aegis.core.authz.PolicyDecisionPoint;
import io.github.vaquarkhan.aegis.core.config.GatewayConfig;
import io.github.vaquarkhan.aegis.core.finops.SemanticCache;
import io.github.vaquarkhan.aegis.core.finops.TokenBudget;
import io.github.vaquarkhan.aegis.core.governance.Approval;
import io.github.vaquarkhan.aegis.core.governance.CircuitBreaker;
import io.github.vaquarkhan.aegis.core.governance.EgressGuard;
import io.github.vaquarkhan.aegis.core.governance.Exposure;
import io.github.vaquarkhan.aegis.core.governance.OutputControls;
import io.github.vaquarkhan.aegis.core.governance.PromptInjectionGuard;
import io.github.vaquarkhan.aegis.core.governance.RateLimiter;
import io.github.vaquarkhan.aegis.core.governance.Scope;
import io.github.vaquarkhan.aegis.core.governance.TimeoutExecutor;
import io.github.vaquarkhan.aegis.core.integrity.VrpValidator;
import io.github.vaquarkhan.aegis.core.spi.CallContext;
import io.github.vaquarkhan.aegis.core.spi.ToolClass;
import io.github.vaquarkhan.aegis.core.spi.ToolDef;
import io.github.vaquarkhan.aegis.core.util.Inputs;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The governance chain. Sanitizing mutators, then ten ordered validation steps where the first
 * denial wins, then bounded and redacted output.
 *
 * <p>Order is a security property. Mutators run first so every validator inspects the same bytes
 * the backend will receive, cheap authorization checks run before expensive ones, and no backend is
 * contacted until every gate has passed. See the low level design for the step table and the deny
 * code registry.
 *
 * @author Viquar Khan
 */
public final class InterceptorChain {

    private static final Logger LOG = LoggerFactory.getLogger(InterceptorChain.class);

    /** Argument name carrying the HMAC approval token for write class tools. */
    public static final String ARG_APPROVAL_TOKEN = "approvalToken";

    /** Argument names inspected by the egress guard. */
    private static final List<String> EGRESS_ARG_KEYS = List.of("url", "endpoint", "host", "uri", "callbackUrl");

    /**
     * Chain outcome. {@code body} carries the tool result when allowed and a stable
     * {@code denied: CODE} string otherwise.
     *
     * @author Viquar Khan
     */
    public record Result(boolean error, String code, int step, String body, long elapsedMillis) {

        public boolean allowed() {
            return !error;
        }
    }

    private final GatewayConfig config;
    private final Exposure exposure;
    private final PolicyDecisionPoint pdp;
    private final Approval approval;
    private final EgressGuard egressGuard;
    private final RateLimiter rateLimiter;
    private final CircuitBreaker breaker;
    private final PromptInjectionGuard injectionGuard;
    private final VrpValidator vrp;
    private final TimeoutExecutor executor;
    private final OutputControls output;
    private final TokenBudget tokenBudget;
    private final SemanticCache cache;
    private final List<Observer> observers = new ArrayList<>();
    private final List<Mutator> mutators = new ArrayList<>();

    public InterceptorChain(
            GatewayConfig config,
            Exposure exposure,
            PolicyDecisionPoint pdp,
            Approval approval,
            EgressGuard egressGuard,
            RateLimiter rateLimiter,
            CircuitBreaker breaker,
            PromptInjectionGuard injectionGuard,
            VrpValidator vrp,
            TimeoutExecutor executor,
            OutputControls output,
            TokenBudget tokenBudget,
            SemanticCache cache) {
        this.config = config;
        this.exposure = exposure;
        this.pdp = pdp;
        this.approval = approval;
        this.egressGuard = egressGuard;
        this.rateLimiter = rateLimiter;
        this.breaker = breaker;
        this.injectionGuard = injectionGuard;
        this.vrp = vrp;
        this.executor = executor;
        this.output = output;
        this.tokenBudget = tokenBudget;
        this.cache = cache;
    }

    public InterceptorChain addObserver(Observer observer) {
        if (observer != null) {
            observers.add(observer);
        }
        return this;
    }

    /** Registers an inbound mutator. Mutators run in priority order before every validator. */
    public InterceptorChain addMutator(Mutator mutator) {
        if (mutator != null) {
            mutators.add(mutator);
            mutators.sort(Comparator.comparingInt(Mutator::priority));
        }
        return this;
    }

    /**
     * Inbound half of LLD section 16: sanitize mutators, then validators, first denial wins.
     * Callers that need the mutated context should call {@link #applyMutators(CallContext)} first
     * and pass the result in.
     */
    public Decision runInbound(ToolDef tool, CallContext ctx) {
        return preflight(tool, applyMutators(ctx));
    }

    /** Outbound half of LLD section 16: bound the body, then redact it. */
    public String runOutbound(String body) {
        return output.boundAndRedact(body);
    }

    /** Applies every registered mutator in priority order. */
    public CallContext applyMutators(CallContext ctx) {
        CallContext current = ctx;
        for (Mutator m : mutators) {
            try {
                CallContext next = m.mutate(current);
                if (next != null) {
                    current = next;
                }
            } catch (RuntimeException e) {
                // A mutator is a rewrite, not a gate. Keep the un-mutated context and let the
                // validators decide; failing the call here would turn a cosmetic bug into an outage.
                LOG.warn("mutator {} failed: {}", m.name(), e.getMessage());
            }
        }
        return current;
    }

    /** Runs the chain for one tool call. */
    public Result execute(ToolDef tool, CallContext raw) {
        long started = System.nanoTime();
        CallContext ctx = applyMutators(raw);
        Decision decision = preflight(tool, ctx);
        if (decision.denied()) {
            return finish(ctx, decision, decision.message(), started);
        }

        // FinOps: refuse before spending backend capacity the caller can no longer pay for.
        String callerId = ctx.caller() == null ? "-" : ctx.caller().callerId();
        if (tokenBudget.enabled() && !tokenBudget.hasBudget(callerId)) {
            LOG.warn("caller {} is out of the daily token budget of {}", callerId, tokenBudget.dailyLimit());
            return finish(ctx,
                    Decision.deny(Decision.BUDGET_EXCEEDED, Decision.STEP_EXECUTE,
                            "denied: BUDGET_EXCEEDED"),
                    "denied: BUDGET_EXCEEDED", started);
        }

        Optional<String> cached = cache.get(ctx);
        if (cached.isPresent()) {
            return finish(ctx, Decision.allow(), runOutbound(cached.get()), started);
        }

        // Step 10: bounded execution. Only timeout and backend failure trip the breaker.
        Future<String> future;
        try {
            future = executor.submit(() -> tool.backend().apply(ctx), ctx.caller());
        } catch (RejectedExecutionException e) {
            breaker.recordFailure(ctx.toolName());
            return finish(ctx, Decision.fail(Decision.BACKEND_ERROR, "denied: BACKEND_ERROR"),
                    "denied: BACKEND_ERROR (executor saturated)", started);
        }

        String body;
        try {
            body = future.get(config.toolTimeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            breaker.recordFailure(ctx.toolName());
            return finish(ctx, Decision.fail(Decision.TIMEOUT, "denied: TIMEOUT"),
                    "denied: TIMEOUT after " + config.toolTimeoutMillis() + "ms", started);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() == null ? ee : ee.getCause();
            if (cause instanceof Inputs.InvalidInput) {
                // Caller error: counted, never charged against the breaker.
                return finish(ctx,
                        new Decision(false, Decision.INVALID_INPUT, Decision.STEP_EXECUTE, Severity.WARN,
                                "denied: INVALID_INPUT"),
                        "denied: INVALID_INPUT", started);
            }
            breaker.recordFailure(ctx.toolName());
            return finish(ctx, Decision.fail(Decision.BACKEND_ERROR, "denied: BACKEND_ERROR"),
                    output.boundAndRedact("backend error: " + safeMessage(cause)), started);
        } catch (InterruptedException ie) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            breaker.recordFailure(ctx.toolName());
            return finish(ctx, Decision.fail(Decision.BACKEND_ERROR, "denied: BACKEND_ERROR"),
                    "denied: BACKEND_ERROR (interrupted)", started);
        }

        breaker.recordSuccess(ctx.toolName());

        // Outbound: bound first so redaction sees the exact bytes that will be returned.
        String safe = runOutbound(body);
        if (injectionGuard.scanOutbound(safe) != null) {
            LOG.warn("outbound prompt-injection pattern detected for tool={}; withholding body", ctx.toolName());
            return finish(ctx,
                    Decision.deny(Decision.PROMPT_INJECTION, Decision.STEP_EXECUTE,
                            "denied: PROMPT_INJECTION"),
                    "denied: PROMPT_INJECTION", started);
        }
        cache.put(ctx, body);
        if (tokenBudget.enabled()
                && !tokenBudget.tryConsume(callerId, TokenBudget.estimateTokens(safe))) {
            // The work is already done, but returning the body would put the caller over the cap.
            // Withhold it: the budget is a ceiling on context spend, not a best-effort counter.
            LOG.warn("caller {} exceeded the daily token budget of {}; withholding the response",
                    callerId, tokenBudget.dailyLimit());
            return finish(ctx,
                    Decision.deny(Decision.BUDGET_EXCEEDED, Decision.STEP_EXECUTE,
                            "denied: BUDGET_EXCEEDED"),
                    "denied: BUDGET_EXCEEDED", started);
        }
        if (vrp.applies(ctx.cls()) && VrpValidator.isDryRun(ctx)) {
            String receipt = vrp.recordDryRun(ctx);
            safe = safe + "\n" + VrpValidator.ARG_RECEIPT + "=" + receipt;
        }
        return finish(ctx, Decision.allow(), safe, started);
    }

    /** Steps 1 through 9. Returns the first denial, or allow. */
    Decision preflight(ToolDef tool, CallContext ctx) {
        CallerIdentity caller = ctx.caller();

        // Step 1: exposure.
        if (!exposure.isExposed(ctx.toolName(), tool.cls())) {
            return Decision.deny(Decision.NOT_EXPOSED, Decision.STEP_EXPOSURE, "denied: NOT_EXPOSED");
        }

        // Step 2: read-only caller, then resource scope.
        if (tool.cls() != ToolClass.READ && (config.readonlyCaller() || caller == null || caller.readonly())) {
            return Decision.deny(Decision.READONLY_CALLER, Decision.STEP_SCOPE, "denied: READONLY_CALLER");
        }
        if (!Scope.allowed(caller, ctx)) {
            return Decision.deny(Decision.SCOPE_DENIED, Decision.STEP_SCOPE, "denied: SCOPE_DENIED");
        }

        // Step 3: policy decision point.
        if (!pdp.allows(ctx)) {
            return Decision.deny(Decision.POLICY_DENIED, Decision.STEP_POLICY, "denied: POLICY_DENIED");
        }

        // Step 4: approval token, required for anything that is not purely observational.
        if (tool.cls() != ToolClass.READ) {
            String token = ctx.arg(ARG_APPROVAL_TOKEN);
            if (!approval.verify(token, ctx.toolName(), Scope.approvalScopeOf(ctx))) {
                return Decision.deny(Decision.APPROVAL_REQUIRED, Decision.STEP_APPROVAL,
                        "denied: APPROVAL_REQUIRED");
            }
        }

        // Step 5: egress guard on any target the caller supplied.
        for (String key : EGRESS_ARG_KEYS) {
            String target = ctx.arg(key);
            if (target != null && !egressGuard.isAllowed(target)) {
                LOG.warn("egress denied for tool={} arg={} reason={}",
                        ctx.toolName(), key, egressGuard.denyReason(target));
                return Decision.deny(Decision.EGRESS_DENIED, Decision.STEP_EGRESS, "denied: EGRESS_DENIED");
            }
        }

        // Step 6: rate limit, per caller.
        if (!rateLimiter.allow(caller == null ? null : caller.callerId())) {
            return Decision.deny(Decision.RATE_LIMITED, Decision.STEP_RATE, "denied: RATE_LIMITED");
        }

        // Step 7: circuit breaker.
        if (breaker.isOpen(ctx.toolName())) {
            return Decision.deny(Decision.BREAKER_OPEN, Decision.STEP_BREAKER, "denied: BREAKER_OPEN");
        }

        // Step 8: prompt injection screening.
        String suspicious = injectionGuard.scan(ctx.arguments());
        if (suspicious != null) {
            LOG.warn("prompt injection pattern in tool={} arg={}", ctx.toolName(), suspicious);
            return Decision.deny(Decision.PROMPT_INJECTION, Decision.STEP_INJECTION,
                    "denied: PROMPT_INJECTION");
        }

        // Step 9: validate-run-promote for destructive tools.
        if (!vrp.verifyReceipt(ctx)) {
            return Decision.deny(Decision.VRP_FAILED, Decision.STEP_VRP, "denied: VRP_FAILED");
        }

        return Decision.allow();
    }

    private Result finish(CallContext ctx, Decision decision, String body, long startedNanos) {
        long elapsed = (System.nanoTime() - startedNanos) / 1_000_000L;
        for (Observer o : observers) {
            try {
                o.onOutcome(ctx, decision, elapsed);
            } catch (RuntimeException e) {
                // Telemetry must never fail a call that governance already resolved.
                LOG.warn("observer {} failed: {}", o.getClass().getSimpleName(), e.getMessage());
            }
        }
        return new Result(decision.denied(), decision.code(), decision.step(), body, elapsed);
    }

    private static String safeMessage(Throwable e) {
        Throwable c = e.getCause();
        if (c != null && c != e && c.getMessage() != null && !c.getMessage().isBlank()) {
            return c.getMessage();
        }
        if (e.getMessage() != null && !e.getMessage().isBlank()) {
            return e.getMessage();
        }
        return e.getClass().getName();
    }
}
