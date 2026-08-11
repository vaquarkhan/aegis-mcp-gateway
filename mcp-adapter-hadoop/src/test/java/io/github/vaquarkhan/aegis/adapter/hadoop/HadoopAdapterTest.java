package io.github.vaquarkhan.aegis.adapter.hadoop;

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
 * Unit tests for {@link HadoopAdapter}.
 *
 * @author Viquar Khan
 */
class HadoopAdapterTest {

    private static final McpJsonMapper JSON = new JacksonMcpJsonMapperSupplier().get();
    private HadoopAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new HadoopAdapter();
    }

    @Test
    void taxonomyAndMetadata() {
        assertEquals("hadoop", adapter.engineId());
        assertEquals("storage", adapter.taxonomyClass());
    }

    @Test
    void toolRegistrationAndPermissions() throws Exception {
        GatewayConfig cfg = GatewayConfig.builder().defaults().build();
        List<ToolDef> tools = adapter.tools(cfg);

        assertEquals(4, tools.size());

        Map<String, ToolDef> toolMap = tools.stream()
                .collect(Collectors.toMap(ToolDef::name, t -> t));

        assertTrue(toolMap.containsKey("list_status"));
        assertEquals(ToolClass.READ, toolMap.get("list_status").cls());

        assertTrue(toolMap.containsKey("get_file_status"));
        assertEquals(ToolClass.READ, toolMap.get("get_file_status").cls());

        assertTrue(toolMap.containsKey("mkdirs"));
        assertEquals(ToolClass.MUTATE, toolMap.get("mkdirs").cls());

        assertTrue(toolMap.containsKey("delete_path"));
        assertEquals(ToolClass.DESTRUCTIVE, toolMap.get("delete_path").cls());

        JsonNode schemaNode = JSON.readValue(toolMap.get("list_status").inputSchemaJson(), JsonNode.class);
        assertEquals("object", schemaNode.get("type").asText());
        assertNotNull(schemaNode.get("properties").get("path"));
    }

    @Test
    void resourceRegistration() {
        GatewayConfig cfg = GatewayConfig.builder().defaults().build();
        List<ResourceDef> resources = adapter.resources(cfg);

        assertEquals(1, resources.size());
        ResourceDef resource = resources.get(0);

        assertEquals("hadoop://status", resource.uri());
        assertEquals("hadoop-status", resource.name());
        assertEquals("application/json", resource.mimeType());
        assertTrue(resource.direct());
    }

    @Test
    void egressAllowHosts_defaultAndCustom() {
        GatewayConfig defaultConfig = GatewayConfig.builder().defaults().build();
        Set<String> defaultHosts = adapter.egressAllowHosts(defaultConfig);
        assertEquals(Set.of("localhost"), defaultHosts);

        GatewayConfig customConfig = GatewayConfig.builder()
                .defaults()
                .property("hadoop.url", "http://hdfs-nn.prod.internal:9870")
                .build();
        Set<String> customHosts = adapter.egressAllowHosts(customConfig);
        assertEquals(Set.of("hdfs-nn.prod.internal"), customHosts);
    }

    @Test
    void egressAllowHosts_handlesInvalidUriGracefully() {
        GatewayConfig invalidConfig = GatewayConfig.builder()
                .defaults()
                .property("hadoop.url", "ht tp://invalid uri")
                .build();
        Set<String> hosts = adapter.egressAllowHosts(invalidConfig);
        assertTrue(hosts.isEmpty());
    }

    @Test
    void baseUrlResolutionHierarchy() {
        // Default fallback
        assertEquals("http://localhost:9870", HadoopAdapter.baseUrl(GatewayConfig.builder().defaults().build()));

        // HDFS_WEBHDFS_URL environment fallback
        GatewayConfig envCfg = GatewayConfig.builder()
                .defaults()
                .property("HDFS_WEBHDFS_URL", "http://fallback-nn:9870")
                .build();
        assertEquals("http://fallback-nn:9870", HadoopAdapter.baseUrl(envCfg));

        // hadoop.url primary property override
        GatewayConfig priorityCfg = GatewayConfig.builder()
                .defaults()
                .property("HDFS_WEBHDFS_URL", "http://fallback-nn:9870")
                .property("hadoop.url", "http://primary-nn:9870")
                .build();
        assertEquals("http://primary-nn:9870", HadoopAdapter.baseUrl(priorityCfg));
    }
}