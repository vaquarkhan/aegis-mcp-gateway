package io.github.vaquarkhan.aegis.adapter.hudi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.core.config.GatewayConfig;
import io.github.vaquarkhan.aegis.core.spi.ResourceDef;
import io.github.vaquarkhan.aegis.core.spi.ToolClass;
import io.github.vaquarkhan.aegis.core.spi.ToolDef;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Unit tests for {@link HudiAdapter}.
 *
 * @author Viquar Khan
 */
class HudiAdapterTest {

    private static final McpJsonMapper JSON = new JacksonMcpJsonMapperSupplier().get();
    private HudiAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new HudiAdapter();
    }

    @Test
    void taxonomyAndMetadata() {
        assertEquals("hudi", adapter.engineId());
        assertEquals("lakehouse", adapter.taxonomyClass());
    }

    @Test
    void toolRegistrationAndPermissions() throws Exception {
        GatewayConfig cfg = GatewayConfig.builder().defaults().build();
        List<ToolDef> tools = adapter.tools(cfg);

        Map<String, ToolDef> toolMap = tools.stream()
                .collect(Collectors.toMap(ToolDef::name, t -> t));

        assertTrue(toolMap.containsKey("list_tables"));
        assertEquals(ToolClass.READ, toolMap.get("list_tables").cls());

        JsonNode schemaNode = JSON.readValue(toolMap.get("list_tables").inputSchemaJson(), JsonNode.class);
        assertEquals("object", schemaNode.get("type").asText());
    }

    @Test
    void resourceRegistration() {
        GatewayConfig cfg = GatewayConfig.builder().defaults().build();
        List<ResourceDef> resources = adapter.resources(cfg);

        assertNotNull(resources);
    }

    @Test
    void egressAllowHosts_defaultAndCustom() {
        GatewayConfig defaultConfig = GatewayConfig.builder().defaults().build();
        Set<String> defaultHosts = adapter.egressAllowHosts(defaultConfig);
        assertEquals(Set.of("localhost"), defaultHosts);

        GatewayConfig customConfig = GatewayConfig.builder()
                .defaults()
                .adapterProperties(Map.of("hudi.url", "http://hudi-service.prod.internal:8080"))
                .build();
        Set<String> customHosts = adapter.egressAllowHosts(customConfig);
        assertEquals(Set.of("hudi-service.prod.internal"), customHosts);
    }

    @Test
    void egressAllowHosts_handlesInvalidUriGracefully() {
        GatewayConfig invalidConfig = GatewayConfig.builder()
                .defaults()
                .adapterProperties(Map.of("hudi.url", "ht tp://invalid uri"))
                .build();
        Set<String> hosts = adapter.egressAllowHosts(invalidConfig);
        assertTrue(hosts.isEmpty());
    }

    @Test
    void baseUrlResolutionHierarchy() {

        assertEquals("http://localhost:8080", HudiAdapter.baseUrl(GatewayConfig.builder().defaults().build()));


        GatewayConfig envCfg = GatewayConfig.builder()
                .defaults()
                .adapterProperties(Map.of("HUDI_URL", "http://fallback-hudi:8080"))
                .build();
        assertEquals("http://fallback-hudi:8080", HudiAdapter.baseUrl(envCfg));


        GatewayConfig priorityCfg = GatewayConfig.builder()
                .defaults()
                .adapterProperties(Map.of(
                        "HUDI_URL", "http://fallback-hudi:8080",
                        "hudi.url", "http://primary-hudi:8080"
                ))
                .build();
        assertEquals("http://primary-hudi:8080", HudiAdapter.baseUrl(priorityCfg));
    }
}