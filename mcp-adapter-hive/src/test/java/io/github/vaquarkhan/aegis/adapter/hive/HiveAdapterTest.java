package io.github.vaquarkhan.aegis.adapter.hive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.core.config.GatewayConfig;
import io.github.vaquarkhan.aegis.core.spi.ToolDef;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** @author Viquar Khan */
class HiveAdapterTest {

    @Test
    void taxonomyAndTools() {
        HiveAdapter adapter = new HiveAdapter();
        assertEquals("hive", adapter.engineId());
        assertEquals("query", adapter.taxonomyClass());
        Set<String> names = adapter.tools(GatewayConfig.builder().defaults().build()).stream()
                .map(ToolDef::name)
                .collect(Collectors.toSet());
        assertTrue(names.contains("get_webui"));
    }
}
