package io.github.vaquarkhan.aegis.adapter.solr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.core.config.GatewayConfig;
import io.github.vaquarkhan.aegis.core.spi.ToolDef;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** @author Viquar Khan */
class SolrAdapterTest {

    private static final McpJsonMapper JSON = new JacksonMcpJsonMapperSupplier().get();

    @Test
    void taxonomyAndTools() {
        SolrAdapter adapter = new SolrAdapter();
        assertEquals("solr", adapter.engineId());
        assertEquals("search", adapter.taxonomyClass());
        Set<String> names = adapter.tools(GatewayConfig.builder().defaults().build()).stream()
                .map(ToolDef::name)
                .collect(Collectors.toSet());
        assertTrue(names.contains("system_info"));
    }

    @Test
    void emptySchemaSerializesCorrectly() throws Exception {
        JsonNode node = parse(new JsonSchema("object", Map.of(), null, null, null, null));

        assertEquals("object", node.get("type").asText());
        assertTrue(node.get("properties").isObject());
        assertTrue(node.get("properties").isEmpty());
        assertFalse(node.has("required"));
    }

    @Test
    void querySchemaSerializesCorrectly() throws Exception {
        JsonNode node = parse(new JsonSchema("object", Map.of(
                "collection", Map.of("type", "string"),
                "q", Map.of("type", "string")),
                List.of("collection", "q"), null, null, null));

        assertEquals("object", node.get("type").asText());
        assertEquals("string", node.get("properties").get("collection").get("type").asText());
        assertEquals("string", node.get("properties").get("q").get("type").asText());
        assertFalse(node.get("properties").has("approvalToken"));
        assertEquals(2, node.get("required").size());
        assertEquals("collection", node.get("required").get(0).asText());
        assertEquals("q", node.get("required").get(1).asText());
    }

    @Test
    void deleteCollectionSchemaSerializesCorrectly() throws Exception {
        JsonNode node = parse(new JsonSchema("object", Map.of(
                "collection", Map.of("type", "string"),
                "approvalToken", Map.of("type", "string")),
                List.of("collection", "approvalToken"), null, null, null));

        assertEquals("object", node.get("type").asText());
        assertEquals("string", node.get("properties").get("collection").get("type").asText());
        assertEquals("string", node.get("properties").get("approvalToken").get("type").asText());
        assertFalse(node.get("properties").has("q"));
        assertEquals(2, node.get("required").size());
        assertEquals("collection", node.get("required").get(0).asText());
        assertEquals("approvalToken", node.get("required").get(1).asText());
    }

    private JsonNode parse(JsonSchema schema) throws Exception {
        return JSON.readValue(JSON.writeValueAsString(schema), JsonNode.class);
    }

}
