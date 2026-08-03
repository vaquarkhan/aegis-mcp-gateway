package io.github.vaquarkhan.aegis.core.auth;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single-use nonce tracker backing approval token replay protection. Entries are evicted once the
 * token they belong to has expired, so the store stays bounded by the approval TTL.
 *
 * @author Viquar Khan
 */
public final class NonceStore {

    private final ConcurrentHashMap<String, Long> seen = new ConcurrentHashMap<>();

    /** Returns true the first time a nonce is presented and false on every replay. */
    public boolean useOnce(String nonce, long expMillis) {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            if (e.getValue() < now) {
                it.remove();
            }
        }
        return seen.putIfAbsent(nonce, expMillis) == null;
    }

    public int size() {
        return seen.size();
    }
}
