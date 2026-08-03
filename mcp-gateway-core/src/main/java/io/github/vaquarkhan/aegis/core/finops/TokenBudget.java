package io.github.vaquarkhan.aegis.core.finops;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-caller daily token budget.
 *
 * <p>Tool output is what fills a model's context window, so the gateway meters output characters
 * rather than trying to guess the client's tokenizer. A budget of zero disables metering entirely.
 * The window resets at UTC midnight so a global deployment resets everywhere at once.
 *
 * @author Viquar Khan
 */
public final class TokenBudget {

    /** Rough characters-per-token ratio used to convert output size into a token estimate. */
    private static final int CHARS_PER_TOKEN = 4;

    private final long dailyLimit;
    private final Map<String, AtomicLong> spendByCaller = new ConcurrentHashMap<>();
    private volatile LocalDate window = LocalDate.now(ZoneOffset.UTC);

    public TokenBudget(long dailyLimit) {
        this.dailyLimit = Math.max(0L, dailyLimit);
    }

    public boolean enabled() {
        return dailyLimit > 0;
    }

    public long dailyLimit() {
        return dailyLimit;
    }

    /** Estimated tokens for a body of text. */
    public static long estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0L;
        }
        return (text.length() + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
    }

    /** Consumes budget. Returns false when the caller has exhausted the day's allowance. */
    public boolean tryConsume(String callerId, long tokens) {
        if (!enabled()) {
            return true;
        }
        rollIfNewDay();
        AtomicLong spend = spendByCaller.computeIfAbsent(key(callerId), k -> new AtomicLong());
        long updated = spend.addAndGet(Math.max(0L, tokens));
        return updated <= dailyLimit;
    }

    /** Checks remaining budget without consuming it. */
    public boolean hasBudget(String callerId) {
        if (!enabled()) {
            return true;
        }
        rollIfNewDay();
        AtomicLong spend = spendByCaller.get(key(callerId));
        return spend == null || spend.get() < dailyLimit;
    }

    public long spent(String callerId) {
        rollIfNewDay();
        AtomicLong spend = spendByCaller.get(key(callerId));
        return spend == null ? 0L : spend.get();
    }

    public long remaining(String callerId) {
        if (!enabled()) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, dailyLimit - spent(callerId));
    }

    public void reset() {
        spendByCaller.clear();
    }

    private void rollIfNewDay() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (!today.equals(window)) {
            synchronized (this) {
                if (!today.equals(window)) {
                    spendByCaller.clear();
                    window = today;
                }
            }
        }
    }

    private static String key(String callerId) {
        return callerId == null ? "-" : callerId;
    }
}
