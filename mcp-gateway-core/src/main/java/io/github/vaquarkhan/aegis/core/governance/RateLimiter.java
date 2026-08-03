package io.github.vaquarkhan.aegis.core.governance;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Step 6. Fixed window per-second rate limiter, one window per caller.
 *
 * <p>The budget is per caller so a single noisy agent cannot spend the ceiling that every other
 * caller shares. A fixed window is deliberate: the gateway wants a hard, easily explained limit
 * that an operator can reason about from a single number, not a smoothed rate that can burst.
 *
 * <p>Stale windows are swept periodically so distinct OAuth subjects cannot grow {@code windows}
 * without bound for the process lifetime.
 *
 * @author Viquar Khan
 */
public final class RateLimiter {

    /** Bucket key used when a call arrives without a resolved caller. */
    public static final String ANONYMOUS = "-";

    private static final long WINDOW_MILLIS = 1000L;
    private static final long STALE_AFTER_MILLIS = 2000L;
    private static final int SWEEP_EVERY = 64;

    private final int maxPerSecond;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicInteger admitsSinceSweep = new AtomicInteger();

    public RateLimiter(int maxPerSecond) {
        this.maxPerSecond = maxPerSecond;
    }

    /** Consumes one permit from the caller's window. */
    public boolean allow(String callerId) {
        boolean ok = windows.computeIfAbsent(key(callerId), k -> new Window()).tryAcquire(maxPerSecond);
        if (admitsSinceSweep.incrementAndGet() >= SWEEP_EVERY) {
            admitsSinceSweep.set(0);
            sweepStale();
        }
        return ok;
    }

    /**
     * Consumes one permit from the shared anonymous window.
     *
     * @deprecated per LLD section 5 the limiter is keyed by caller; use {@link #allow(String)}.
     */
    @Deprecated
    public boolean allow() {
        return allow(ANONYMOUS);
    }

    public int maxPerSecond() {
        return maxPerSecond;
    }

    /** Calls consumed across every caller in their current windows. */
    public int used() {
        int total = 0;
        for (Window w : windows.values()) {
            total += w.used();
        }
        return total;
    }

    /** Calls this caller has consumed in its current window. */
    public int used(String callerId) {
        Window w = windows.get(key(callerId));
        return w == null ? 0 : w.used();
    }

    /** Number of callers currently tracked. */
    public int callers() {
        return windows.size();
    }

    /** Removes caller windows whose fixed second started more than two seconds ago. */
    public void sweepStale() {
        long cutoff = System.currentTimeMillis() - STALE_AFTER_MILLIS;
        Iterator<Map.Entry<String, Window>> it = windows.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Window> e = it.next();
            if (e.getValue().isStale(cutoff)) {
                it.remove();
            }
        }
    }

    private static String key(String callerId) {
        return (callerId == null || callerId.isBlank()) ? ANONYMOUS : callerId;
    }

    /** One fixed second window. */
    private static final class Window {

        private long startMillis = System.currentTimeMillis();
        private int count;

        synchronized boolean tryAcquire(int maxPerSecond) {
            long now = System.currentTimeMillis();
            if (now - startMillis >= WINDOW_MILLIS) {
                startMillis = now;
                count = 0;
            }
            if (count >= maxPerSecond) {
                return false;
            }
            count++;
            return true;
        }

        synchronized int used() {
            return count;
        }

        synchronized boolean isStale(long cutoffMillis) {
            return startMillis < cutoffMillis;
        }
    }
}
