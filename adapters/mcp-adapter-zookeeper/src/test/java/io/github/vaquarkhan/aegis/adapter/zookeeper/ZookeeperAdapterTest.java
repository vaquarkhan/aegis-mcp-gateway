package io.github.vaquarkhan.aegis.adapter.zookeeper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.core.config.GatewayConfig;
import io.github.vaquarkhan.aegis.core.spi.ToolDef;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** @author Viquar Khan */
class ZookeeperAdapterTest {

    @Test
    void taxonomyAndTools() {
        ZookeeperAdapter adapter = new ZookeeperAdapter();
        assertEquals("zookeeper", adapter.engineId());
        assertEquals("coordination", adapter.taxonomyClass());
        Set<String> names = adapter.tools(GatewayConfig.builder().defaults().build()).stream()
                .map(ToolDef::name)
                .collect(Collectors.toSet());
        assertTrue(names.contains("ruok"));
    }
}
