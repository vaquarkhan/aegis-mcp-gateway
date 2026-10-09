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
package io.github.vaquarkhan.aegis.knox;

import io.github.vaquarkhan.aegis.config.BuildContext;
import io.github.vaquarkhan.aegis.config.Gateway;
import io.github.vaquarkhan.aegis.config.GatewayFactory;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One Aegis gateway per deployed topology web application, built on the first request and closed
 * when Knox undeploys the topology ({@link AegisKnoxLifecycle}). A topology whose Aegis configuration
 * fails to load answers 503 on the Aegis route only (route-local readiness); the rest of the
 * topology is unaffected and nothing falls back to ungoverned access. A failed build is retried on a
 * later request once {@link #RETRY_AFTER} has passed, so a corrected configuration file takes effect
 * without redeploying the topology. A built gateway is kept until the topology is undeployed.
 */
public final class KnoxGatewayRegistry {

    /** Service parameter (topology XML) naming the Aegis configuration file. */
    public static final String CONFIG_PARAM = "aegis.config";

    /** Minimum time between two build attempts for a topology whose configuration failed to load. */
    public static final Duration RETRY_AFTER = Duration.ofSeconds(10);

    private static final ConcurrentHashMap<Object, Entry> GATEWAYS = new ConcurrentHashMap<>();

    /** Retry interval in nanoseconds; tests in this package may shorten it. */
    static volatile long retryAfterNanos = RETRY_AFTER.toNanos();

    private KnoxGatewayRegistry() {}

    /** Result of a lookup: a gateway, or a stable public unavailable reason. */
    public record Holder(Gateway gateway, String error) {}

    /** A lookup result and, for a failed build, when it failed. */
    private record Entry(Holder holder, long failedAtNanos) {}

    private static final String UNAVAILABLE = "Aegis configuration is unavailable";

    public static Holder get(Object contextKey, String configPath) {
        // compute() runs under the map's lock for this key, so concurrent requests on one topology
        // see one build attempt, never two gateways opening the same audit journal.
        return GATEWAYS.compute(contextKey, (k, current) -> {
            if (current != null && (current.holder().gateway() != null
                    || System.nanoTime() - current.failedAtNanos() < retryAfterNanos)) {
                return current;
            }
            Holder built = build(configPath);
            return new Entry(built, built.gateway() != null ? 0L : System.nanoTime());
        }).holder();
    }

    private static Holder build(String configPath) {
        if (configPath == null || configPath.isBlank()) {
            return new Holder(null, UNAVAILABLE);
        }
        try {
            Path p = Path.of(configPath).toAbsolutePath();
            return new Holder(GatewayFactory.build(p, BuildContext.defaults(p.getParent())), null);
        } catch (Exception e) {
            System.getLogger(KnoxGatewayRegistry.class.getName()).log(System.Logger.Level.ERROR,
                    "Aegis configuration failed to load; the Aegis route answers 503", e);
            return new Holder(null, UNAVAILABLE);
        }
    }

    public static void close(Object contextKey) {
        Entry e = GATEWAYS.remove(contextKey);
        Holder h = e == null ? null : e.holder();
        if (h != null && h.gateway() != null) {
            h.gateway().close();
            System.getLogger(KnoxGatewayRegistry.class.getName()).log(System.Logger.Level.INFO,
                    "Aegis gateway closed for undeployed topology (audit journal and sources released)");
        }
    }
}
