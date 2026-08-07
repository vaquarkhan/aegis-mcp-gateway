package io.github.vaquarkhan.aegis.adapter.nifi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import java.util.List;
import java.util.Map;

import io.github.vaquarkhan.aegis.core.config.GatewayConfig;
import io.github.vaquarkhan.aegis.core.spi.ToolDef;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** @author Viquar Khan */
class NifiAdapterTest {

    private static final McpJsonMapper JSON = new JacksonMcpJsonMapperSupplier().get();

    private JsonNode parse(JsonSchema schema) throws Exception {
        return JSON.readValue(JSON.writeValueAsString(schema), JsonNode.class);
    }

    @Test
    void emptySchemaHasNoRequired() throws Exception {
        JsonSchema schema = new JsonSchema("object", Map.of(), List.of(), null, null, null);
        JsonNode node = parse(schema);
        assertEquals("object", node.get("type").asText());
        assertFalse(node.has("required"));
    }

    @Test
    void taxonomyAndTools() {
        NifiAdapter adapter = new NifiAdapter();
        assertEquals("nifi", adapter.engineId());
        assertEquals("dataflow", adapter.taxonomyClass());
        Set<String> names = adapter.tools(GatewayConfig.builder().defaults().build()).stream()
                .map(ToolDef::name)
                .collect(Collectors.toSet());
        assertTrue(names.contains("get_about"));
    }
}
