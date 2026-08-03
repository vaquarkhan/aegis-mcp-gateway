package io.github.vaquarkhan.aegis.core.interceptor;

/**
 * How loudly an outcome should be reported. Governance denials are expected traffic; backend
 * failures are not.
 *
 * @author Viquar Khan
 */
public enum Severity {

    /** Allowed call. */
    INFO,

    /** Expected governance denial such as a missing approval token or a rate limit. */
    WARN,

    /** Backend trouble: timeout or error. These are the outcomes that trip the circuit breaker. */
    ERROR
}
