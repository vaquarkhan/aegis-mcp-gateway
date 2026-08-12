package io.github.vaquarkhan.aegis.adapter.hive;

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
 * Unit tests for {@link HiveAdapter}.
 *
 * @author Viquar Khan
 */
class HiveAdapterTest {

    private static final McpJsonMapper JSON = new JacksonMcpJsonMapperSupplier().get();
    private HiveAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new HiveAdapter();
    }

    @Test
    void taxonomyAndMetadata() {
        assertEquals("hive", adapter.engineId());
        assertEquals("query", adapter.taxonomyClass());
    }

    @Test
    void toolRegistrationAndPermissions() throws Exception {
        GatewayConfig cfg = GatewayConfig.builder().defaults().build();
        List<ToolDef> tools = adapter.tools(cfg);

        Map<String, ToolDef> toolMap = tools.stream()
                .collect(Collectors.toMap(ToolDef::name, t -> t));

        assertTrue(toolMap.containsKey("get_webui"));
        assertEquals(ToolClass.READ, toolMap.get("get_webui").cls());

        JsonNode schemaNode = JSON.readValue(toolMap.get("get_webui").inputSchemaJson(), JsonNode.class);
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
                .adapterProperties(Map.of("hive.url", "http://hive-hs2.prod.internal:10002"))
                .build();
        Set<String> customHosts = adapter.egressAllowHosts(customConfig);
        assertEquals(Set.of("hive-hs2.prod.internal"), customHosts);
    }

    @Test
    void egressAllowHosts_handlesInvalidUriGracefully() {
        GatewayConfig invalidConfig = GatewayConfig.builder()
                .defaults()
                .adapterProperties(Map.of("hive.url", "ht tp://invalid uri"))
                .build();
        Set<String> hosts = adapter.egressAllowHosts(invalidConfig);
        assertTrue(hosts.isEmpty());
    }

    @Test
    void baseUrlResolutionHierarchy() {
        
        assertEquals("http://localhost:10002", HiveAdapter.baseUrl(GatewayConfig.builder().defaults().build()));

        GatewayConfig envCfg = GatewayConfig.builder()
                .defaults()
                .adapterProperties(Map.of("HIVE_WEBUI_URL", "http://fallback-hive:10002"))
                .build();
        assertEquals("http://fallback-hive:10002", HiveAdapter.baseUrl(envCfg));

        GatewayConfig priorityCfg = GatewayConfig.builder()
                .defaults()
                .adapterProperties(Map.of(
                        "HIVE_WEBUI_URL", "http://fallback-hive:10002",
                        "hive.url", "http://primary-hive:10002"
                ))
                .build();
        assertEquals("http://primary-hive:10002", HiveAdapter.baseUrl(priorityCfg));
    }
}