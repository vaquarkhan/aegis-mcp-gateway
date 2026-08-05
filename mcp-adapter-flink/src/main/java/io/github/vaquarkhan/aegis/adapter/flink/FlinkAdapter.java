package io.github.vaquarkhan.aegis.adapter.flink;

import io.github.vaquarkhan.aegis.adapter.flink.client.SqlReadonlyGuard;
import io.github.vaquarkhan.aegis.core.config.GatewayConfig;
import io.github.vaquarkhan.aegis.core.observability.Metrics;
import io.github.vaquarkhan.aegis.core.spi.*;
import io.github.vaquarkhan.aegis.core.spi.ResourceDef;
import io.github.vaquarkhan.aegis.core.spi.ToolDef;
import io.github.vaquarkhan.aegis.core.spi.PromptDef;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Apache Flink engine adapter. Exposes the JobManager REST API and the SQL Gateway as governed
 * MCP tools and resources. Only the JDK HTTP client is used, so no Flink runtime jar is needed
 * and the adapter works across Flink versions that keep the REST contract.
 *
 * @author Viquar Khan
 */
public final class FlinkAdapter implements EngineAdapter {

    public static final String ENGINE_ID = "flink";
    public static final String TAXONOMY_CLASS = "streaming";

    private static final Logger LOG = LoggerFactory.getLogger(FlinkAdapter.class);

    private final SqlReadonlyGuard sqlGuard = new SqlReadonlyGuard();
    private final Metrics metrics = new Metrics();

    private GatewayConfig factoryConfig;
    private FlinkToolFactory factory;

    @Override
    public String engineId() {
        return ENGINE_ID;
    }

    @Override
    public String taxonomyClass() {
        return TAXONOMY_CLASS;
    }

    @Override
    public List<ToolDef> tools(GatewayConfig cfg) {
        LOG.debug("building flink tools rest={} gateway={}",
                FlinkConfigKeys.restUrl(cfg), FlinkConfigKeys.gatewayUrl(cfg));
        return factory(cfg).tools();
    }

    @Override
    public List<ResourceDef> resources(GatewayConfig cfg) {
        return factory(cfg).resources();
    }

    @Override
    public Optional<ReadOnlyGuard> readOnlyGuard() {
        return Optional.of(sqlGuard);
    }

    @Override
    public Optional<CredentialResolver> credentialResolver() {
        // Propagate per-caller Authorization when the admission layer bound one on the identity.
        return Optional.of(PassThroughCredentialResolver.INSTANCE);
    }

    @Override
    public Set<String> egressAllowHosts(GatewayConfig cfg) {
        return FlinkConfigKeys.egressHosts(cfg);
    }

    /** Tools and resources share one factory so that both reuse the same HTTP clients. */
    private synchronized FlinkToolFactory factory(GatewayConfig cfg) {
        if (factory == null || factoryConfig != cfg) {
            factoryConfig = cfg;
            factory = new FlinkToolFactory(cfg, sqlGuard, metrics);
        }
        return factory;
    }
    @Override
    public boolean healthCheck(GatewayConfig cfg) {
        return factory(cfg).isHealthy();
    }

    @Override
    public Set<String> capabilities(GatewayConfig cfg) {
        return Set.of("REST_API", "SQL_GATEWAY", "JAR_UPLOAD", "SAVEPOINTS");
    }

    @Override
    public List<PromptDef> prompts(GatewayConfig cfg) {
        return List.of(
                new PromptDef(
                        "flink-sql-dialect",
                        "Guidelines for writing streaming and batch queries using Flink SQL dialect"
                )
        );
    }
}
