package io.github.vaquarkhan.aegis.core.spi;

import io.github.vaquarkhan.aegis.core.config.GatewayConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PromptAggregationTest {

    static class MockAdapter implements EngineAdapter {
        @Override
        public String engineId() {
            return "mock";
        }

        @Override
        public String taxonomyClass() {
            return "mock-taxonomy";
        }

        @Override
        public List<ToolDef> tools(GatewayConfig cfg) {
            return List.of();
        }

        @Override
        public List<ResourceDef> resources(GatewayConfig cfg) {
            return List.of();
        }

        @Override
        public Set<String> egressAllowHosts(GatewayConfig cfg) {
            return Set.of();
        }

        @Override
        public List<PromptDef> prompts(GatewayConfig cfg) {
            return List.of(new PromptDef("mock-prompt", "Mock Desc", "Mock Template Content"));
        }
    }

    @Test
    void aggregatePrompts_collectsPromptsFromAllAdapters() {
        List<EngineAdapter> adapters = List.of(new MockAdapter());
        GatewayConfig config = GatewayConfig.builder().defaults().build();

        List<PromptDef> aggregated = adapters.stream()
                .flatMap(a -> a.prompts(config).stream())
                .toList();

        assertEquals(1, aggregated.size());
        assertEquals("mock-prompt", aggregated.get(0).getId());
        assertEquals("Mock Template Content", aggregated.get(0).getTemplate());
    }
}