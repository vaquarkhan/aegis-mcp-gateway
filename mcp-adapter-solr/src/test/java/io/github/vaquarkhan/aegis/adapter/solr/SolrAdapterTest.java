package io.github.vaquarkhan.aegis.adapter.solr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.core.config.GatewayConfig;
import io.github.vaquarkhan.aegis.core.jsonschema.InputSchema;
import io.github.vaquarkhan.aegis.core.jsonschema.SchemaProperties;
import io.github.vaquarkhan.aegis.core.spi.ToolDef;
import io.github.vaquarkhan.aegis.core.util.JacksonUtils;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** @author Viquar Khan */
class SolrAdapterTest {

    private static final ObjectMapper MAPPER = JacksonUtils.getMapper();

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
        InputSchema schema = new InputSchema("object", SchemaProperties.empty(), null);
        String json = MAPPER.writeValueAsString(schema);
        JsonNode node = MAPPER.readTree(json);

        assertEquals("object", node.get("type").asText());
        assertTrue(node.get("properties").isObject());
        assertTrue(node.get("properties").isEmpty());
        assertFalse(node.has("required"));
    }

    @Test
    void querySchemaSerializesCorrectly() throws Exception {
        InputSchema schema = new InputSchema("object",
                SchemaProperties.collectionAndQ(),
                List.of("collection", "q"));
        String json = MAPPER.writeValueAsString(schema);
        JsonNode node = MAPPER.readTree(json);

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
        InputSchema schema = new InputSchema("object",
                SchemaProperties.collectionAndApprovalToken(),
                List.of("collection", "approvalToken"));
        String json = MAPPER.writeValueAsString(schema);
        JsonNode node = MAPPER.readTree(json);

        assertEquals("object", node.get("type").asText());
        assertEquals("string", node.get("properties").get("collection").get("type").asText());
        assertEquals("string", node.get("properties").get("approvalToken").get("type").asText());
        assertFalse(node.get("properties").has("q"));
        assertEquals(2, node.get("required").size());
        assertEquals("collection", node.get("required").get(0).asText());
        assertEquals("approvalToken", node.get("required").get(1).asText());
    }

}
