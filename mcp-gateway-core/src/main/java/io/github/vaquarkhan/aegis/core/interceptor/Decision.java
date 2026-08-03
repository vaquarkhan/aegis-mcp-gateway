package io.github.vaquarkhan.aegis.core.interceptor;

/**
 * Outcome of one interceptor. The chain stops at the first denial.
 *
 * <p>The code and step values are a public contract: they appear in audit records as
 * {@code DENIED:<code>:step<N>} and as the {@code code} label on the denial metric, so operators
 * can alert on them uniformly across every engine.
 *
 * @author Viquar Khan
 */
public record Decision(boolean allowed, String code, int step, Severity severity, String message) {

    public static final String NOT_EXPOSED = "NOT_EXPOSED";
    public static final String READONLY_CALLER = "READONLY_CALLER";
    public static final String SCOPE_DENIED = "SCOPE_DENIED";
    public static final String POLICY_DENIED = "POLICY_DENIED";
    public static final String APPROVAL_REQUIRED = "APPROVAL_REQUIRED";
    public static final String EGRESS_DENIED = "EGRESS_DENIED";
    public static final String RATE_LIMITED = "RATE_LIMITED";
    public static final String BREAKER_OPEN = "BREAKER_OPEN";
    public static final String PROMPT_INJECTION = "PROMPT_INJECTION";
    public static final String VRP_FAILED = "VRP_FAILED";
    public static final String INVALID_INPUT = "INVALID_INPUT";
    public static final String TIMEOUT = "TIMEOUT";
    public static final String BACKEND_ERROR = "BACKEND_ERROR";
    public static final String BUDGET_EXCEEDED = "BUDGET_EXCEEDED";
    public static final String ALLOWED = "ALLOWED";

    public static final int STEP_EXPOSURE = 1;
    public static final int STEP_SCOPE = 2;
    public static final int STEP_POLICY = 3;
    public static final int STEP_APPROVAL = 4;
    public static final int STEP_EGRESS = 5;
    public static final int STEP_RATE = 6;
    public static final int STEP_BREAKER = 7;
    public static final int STEP_INJECTION = 8;
    public static final int STEP_VRP = 9;
    public static final int STEP_EXECUTE = 10;

    private static final Decision ALLOW = new Decision(true, ALLOWED, 0, Severity.INFO, "");

    public Decision {
        code = code == null ? "" : code;
        severity = severity == null ? Severity.INFO : severity;
        message = message == null ? "" : message;
    }

    public static Decision allow() {
        return ALLOW;
    }

    /** Expected governance denial. */
    public static Decision deny(String code, int step) {
        return new Decision(false, code, step, Severity.WARN, "denied: " + code);
    }

    public static Decision deny(String code, int step, String message) {
        return new Decision(false, code, step, Severity.WARN, message);
    }

    /** Backend failure. Recorded at error severity and used for breaker accounting. */
    public static Decision fail(String code, String message) {
        return new Decision(false, code, STEP_EXECUTE, Severity.ERROR, message);
    }

    public boolean denied() {
        return !allowed;
    }

    /** Audit suffix, for example {@code DENIED:RATE_LIMITED:step6}. */
    public String auditOutcome() {
        return allowed ? ALLOWED : "DENIED:" + code + ":step" + step;
    }
}
