package io.github.vaquarkhan.aegis.core.config;

import io.github.vaquarkhan.aegis.core.spi.EngineAdapter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discovers {@link EngineAdapter} implementations.
 *
 * <p>Adapters come from two places: the {@link ServiceLoader}, which finds every adapter jar on the
 * classpath, and explicit programmatic registration, which tests and embedders use. The
 * {@code MCP_GW_ADAPTERS} allow list then narrows the result, so adding an adapter jar to the
 * classpath is not by itself enough to enable it in a locked-down deployment.
 *
 * @author Viquar Khan
 */
public final class AdapterRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(AdapterRegistry.class);

    private final Map<String, EngineAdapter> registered = new LinkedHashMap<>();

    /** Registers an adapter explicitly. The last registration for an engine id wins. */
    public AdapterRegistry register(EngineAdapter adapter) {
        if (adapter == null) {
            throw new IllegalArgumentException("adapter required");
        }
        String id = adapter.engineId();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("adapter engineId required: " + adapter.getClass().getName());
        }
        registered.put(id, adapter);
        return this;
    }

    /** Adds every adapter published through the service loader. */
    public AdapterRegistry loadFromServiceLoader() {
        return loadFromServiceLoader(Thread.currentThread().getContextClassLoader());
    }

    public AdapterRegistry loadFromServiceLoader(ClassLoader loader) {
        ServiceLoader<EngineAdapter> services = loader == null
                ? ServiceLoader.load(EngineAdapter.class)
                : ServiceLoader.load(EngineAdapter.class, loader);
        for (java.util.Iterator<EngineAdapter> it = services.iterator(); it.hasNext();) {
            try {
                register(it.next());
            } catch (ServiceConfigurationError e) {
                // A broken adapter jar must not stop the gateway from serving the healthy ones.
                LOG.error("skipping unloadable engine adapter: {}", e.getMessage());
            }
        }
        return this;
    }

    /** Adapters that survive the {@code MCP_GW_ADAPTERS} allow list, in registration order. */
    public List<EngineAdapter> enabled(GatewayConfig cfg) {
        List<EngineAdapter> out = new ArrayList<>();
        for (Map.Entry<String, EngineAdapter> e : registered.entrySet()) {
            if (cfg.adapterEnabled(e.getKey())) {
                out.add(e.getValue());
            } else {
                LOG.info("adapter {} present but not enabled by MCP_GW_ADAPTERS", e.getKey());
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** Convenience: service loader discovery followed by allow-list filtering. */
    public static List<EngineAdapter> discover(GatewayConfig cfg) {
        AdapterRegistry registry = new AdapterRegistry().loadFromServiceLoader();
        List<EngineAdapter> adapters = registry.enabled(cfg);
        if (adapters.isEmpty()) {
            LOG.warn("no engine adapters enabled; the gateway will expose governance endpoints only");
        } else {
            LOG.info("enabled adapters: {}", registry.engineIds(cfg));
        }
        return adapters;
    }

    public Optional<EngineAdapter> find(String engineId) {
        return Optional.ofNullable(registered.get(engineId));
    }

    public Set<String> engineIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(registered.keySet()));
    }

    public Set<String> engineIds(GatewayConfig cfg) {
        Set<String> out = new LinkedHashSet<>();
        for (EngineAdapter a : enabled(cfg)) {
            out.add(a.engineId());
        }
        return Collections.unmodifiableSet(out);
    }

    public int size() {
        return registered.size();
    }
}
