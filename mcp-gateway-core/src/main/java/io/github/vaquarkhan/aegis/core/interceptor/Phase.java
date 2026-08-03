package io.github.vaquarkhan.aegis.core.interceptor;

/**
 * Which kind of work an interceptor performs, as in LLD section 4.
 *
 * <p>Mutation runs first and atomically, validation decides, observation only records. The older
 * position based names are retained as deprecated aliases so existing plugins keep compiling.
 *
 * @author Viquar Khan
 */
public enum Phase {

    /** Rewrites the call: argument sanitizing inbound, redaction outbound. */
    MUTATION,

    /** Decides whether the call proceeds. Steps 1 through 9. */
    VALIDATION,

    /** Records the outcome. Never changes it. */
    OBSERVATION,

    /**
     * Runs before the backend is contacted.
     *
     * @deprecated use {@link #VALIDATION}.
     */
    @Deprecated
    PRE,

    /**
     * The bounded backend invocation itself. Step 10.
     *
     * @deprecated the chain owns execution; interceptors never declare this phase.
     */
    @Deprecated
    EXECUTE,

    /**
     * Runs on the result: output bounding and redaction.
     *
     * @deprecated use {@link #MUTATION}.
     */
    @Deprecated
    POST
}
