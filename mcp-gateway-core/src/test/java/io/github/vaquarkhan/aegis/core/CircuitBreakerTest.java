package io.github.vaquarkhan.aegis.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.core.governance.CircuitBreaker;
import org.junit.jupiter.api.Test;

/**
 * @author Viquar Khan
 */
class CircuitBreakerTest {

    @Test
    void opensAfterThresholdFailures() {
        CircuitBreaker breaker = new CircuitBreaker(3, 60_000L);
        assertFalse(breaker.isOpen("list_jobs"));
        breaker.recordFailure("list_jobs");
        breaker.recordFailure("list_jobs");
        assertFalse(breaker.isOpen("list_jobs"));
        breaker.recordFailure("list_jobs");
        assertTrue(breaker.isOpen("list_jobs"));
    }

    @Test
    void successResetsTheFailureCount() {
        CircuitBreaker breaker = new CircuitBreaker(2, 60_000L);
        breaker.recordFailure("get_job");
        breaker.recordSuccess("get_job");
        breaker.recordFailure("get_job");
        assertFalse(breaker.isOpen("get_job"), "one failure after a success must not open the breaker");
    }

    @Test
    void breakerIsPerTool() {
        CircuitBreaker breaker = new CircuitBreaker(1, 60_000L);
        breaker.recordFailure("list_jobs");
        assertTrue(breaker.isOpen("list_jobs"));
        assertFalse(breaker.isOpen("list_topics"), "one unhealthy tool must not deny the others");
    }

    @Test
    void halfOpenAdmitsOneProbeThenClosesOnSuccess() throws InterruptedException {
        CircuitBreaker breaker = new CircuitBreaker(1, 50L);
        breaker.recordFailure("run_sql_readonly");
        assertTrue(breaker.isOpen("run_sql_readonly"));

        Thread.sleep(80L);
        assertFalse(breaker.isOpen("run_sql_readonly"), "first call after reset is the probe");
        assertTrue(breaker.isOpen("run_sql_readonly"), "a concurrent second probe must be refused");

        breaker.recordSuccess("run_sql_readonly");
        assertFalse(breaker.isOpen("run_sql_readonly"));
    }

    @Test
    void halfOpenReopensOnFailure() throws InterruptedException {
        CircuitBreaker breaker = new CircuitBreaker(1, 50L);
        breaker.recordFailure("cancel_job");
        Thread.sleep(80L);
        assertFalse(breaker.isOpen("cancel_job"));
        breaker.recordFailure("cancel_job");
        assertTrue(breaker.isOpen("cancel_job"));
    }
}
