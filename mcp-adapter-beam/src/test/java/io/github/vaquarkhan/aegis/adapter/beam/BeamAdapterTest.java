package io.github.vaquarkhan.aegis.adapter.beam;

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
class BeamAdapterTest {

    private static final McpJsonMapper JSON = new JacksonMcpJsonMapperSupplier().get();

    private JsonNode parse(JsonSchema schema) throws Exception {
        return JSON.readValue(JSON.writeValueAsString(schema), JsonNode.class);
    }

    @Test
    void emptySchemaHasNoRequired() throws Exception {
        JsonSchema schema = new JsonSchema("object", Map.of(), null, null, null, null);
        JsonNode node = parse(schema);
        assertEquals("object", node.get("type").asText());
        assertFalse(node.has("required"));
    }

    @Test
    void taxonomyAndTools() {
        BeamAdapter adapter = new BeamAdapter();
        assertEquals("beam", adapter.engineId());
        assertEquals("pipeline", adapter.taxonomyClass());
        Set<String> names = adapter.tools(GatewayConfig.builder().defaults().build()).stream()
                .map(ToolDef::name)
                .collect(Collectors.toSet());
        assertTrue(names.contains("list_jobs"));
    }
}
