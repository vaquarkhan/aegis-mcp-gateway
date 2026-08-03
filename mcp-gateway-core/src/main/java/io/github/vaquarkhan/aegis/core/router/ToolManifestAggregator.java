package io.github.vaquarkhan.aegis.core.router;

import io.github.vaquarkhan.aegis.core.config.GatewayConfig;
import io.github.vaquarkhan.aegis.core.integrity.ToolCatalogIntegrity;
import io.github.vaquarkhan.aegis.core.spi.EngineAdapter;
import io.github.vaquarkhan.aegis.core.spi.ResourceDef;
import io.github.vaquarkhan.aegis.core.spi.ToolDef;
import io.github.vaquarkhan.aegis.core.yaml.YamlManifestLoader.ToolOverlay;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Merges the tool and resource manifests contributed by every enabled adapter into one catalog.
 *
 * <p>Three things happen here that cannot happen inside an adapter, because an adapter only sees
 * itself: name collisions between engines are resolved, the {@code tools.yaml} overlay is applied,
 * and every surviving tool is digested for rug-pull detection.
 *
 * @author Viquar Khan
 */
public final class ToolManifestAggregator {

    private static final Logger LOG = LoggerFactory.getLogger(ToolManifestAggregator.class);

    /**
     * Result of aggregating every adapter manifest.
     *
     * @author Viquar Khan
     */
    public record Aggregation(
            Map<String, ToolDef> tools,
            List<ResourceDef> resources,
            TaxonomyRouter router,
            Set<String> egressAllowHosts,
            List<String> warnings) {}

    private final ToolCatalogIntegrity integrity;
    private final Map<String, ToolOverlay> overlay;

    public ToolManifestAggregator(ToolCatalogIntegrity integrity) {
        this(integrity, Map.of());
    }

    public ToolManifestAggregator(ToolCatalogIntegrity integrity, Map<String, ToolOverlay> overlay) {
        this.integrity = integrity == null
                ? new ToolCatalogIntegrity(new io.github.vaquarkhan.aegis.core.integrity.DigestRegistry())
                : integrity;
        this.overlay = overlay == null ? Map.of() : Map.copyOf(overlay);
    }

    public Aggregation aggregate(List<EngineAdapter> adapters, GatewayConfig cfg) {
        Map<String, ToolDef> tools = new LinkedHashMap<>();
        List<ResourceDef> resources = new ArrayList<>();
        Set<String> resourceUris = new LinkedHashSet<>();
        Set<String> egress = new LinkedHashSet<>(cfg.egressAllowHosts());
        List<String> warnings = new ArrayList<>();
        TaxonomyRouter router = new TaxonomyRouter();

        for (EngineAdapter adapter : adapters) {
            String engineId = adapter.engineId();
            Set<String> adapterHosts = adapter.egressAllowHosts(cfg);
            if (adapterHosts != null) {
                egress.addAll(adapterHosts);
            }

            for (ToolDef raw : adapter.tools(cfg)) {
                ToolDef tool = applyOverlay(raw, engineId, warnings);
                if (tool == null) {
                    continue;
                }
                String name = tool.name();
                if (tools.containsKey(name)) {
                    // Namespacing on collision keeps both engines usable instead of silently
                    // letting the second adapter shadow the first.
                    String namespaced = engineId + "." + name;
                    if (tools.containsKey(namespaced)) {
                        warnings.add("dropping duplicate tool " + namespaced + " from adapter " + engineId);
                        continue;
                    }
                    warnings.add("tool name collision on " + name + "; exposing " + namespaced
                            + " for adapter " + engineId);
                    tool = tool.renamed(namespaced);
                    name = namespaced;
                }
                if (!integrity.verifyAndPin(tool)) {
                    warnings.add("tool " + name + " failed catalog integrity and was dropped");
                    continue;
                }
                ToolOverlay ov = overlay.get(raw.name());
                if (ov != null && !integrity.matchesExpected(tool, ov.schemaDigest())) {
                    warnings.add("tool " + name + " schema digest does not match tools.yaml pin");
                    continue;
                }
                tools.put(name, tool);
                router.register(name, engineId, adapter.taxonomyClass());
            }

            for (ResourceDef resource : adapter.resources(cfg)) {
                if (!resourceUris.add(resource.uri())) {
                    warnings.add("dropping duplicate resource uri " + resource.uri()
                            + " from adapter " + engineId);
                    continue;
                }
                resources.add(resource);
            }
        }

        for (String w : warnings) {
            LOG.warn("{}", w);
        }
        LOG.info("aggregated tools={} resources={} engines={} egressHosts={}",
                tools.size(), resources.size(), router.engines().size(), egress.size());

        return new Aggregation(
                Collections.unmodifiableMap(tools),
                Collections.unmodifiableList(resources),
                router,
                Collections.unmodifiableSet(egress),
                Collections.unmodifiableList(warnings));
    }

    /** Applies a {@code tools.yaml} entry. Returns {@code null} when the overlay drops the tool. */
    private ToolDef applyOverlay(ToolDef tool, String engineId, List<String> warnings) {
        ToolOverlay ov = overlay.get(tool.name());
        if (ov == null) {
            return tool;
        }
        if (ov.engine() != null && !ov.engine().equals(engineId)) {
            warnings.add("tools.yaml entry " + ov.name() + " targets engine " + ov.engine()
                    + " but was matched against " + engineId + "; overlay ignored");
            return tool;
        }
        if (!ov.enabled()) {
            LOG.info("tool {} disabled by tools.yaml", tool.name());
            return null;
        }
        ToolDef result = tool;
        if (ov.cls() != null) {
            if (ov.cls().atLeast(tool.cls()) && ov.cls() != tool.cls()) {
                // An overlay may only reduce authority; raising it would let a config file grant
                // permissions the adapter author never intended.
                warnings.add("tools.yaml cannot raise " + tool.name() + " from " + tool.cls()
                        + " to " + ov.cls() + "; keeping " + tool.cls());
            } else {
                result = result.downgradedTo(ov.cls());
            }
        }
        if (ov.description() != null) {
            result = new ToolDef(result.name(), result.cls(), ov.description(),
                    result.inputSchemaJson(), result.backend());
        }
        return result;
    }
}
