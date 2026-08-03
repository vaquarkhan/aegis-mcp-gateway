package io.github.vaquarkhan.aegis.adapter.couchdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.core.config.GatewayConfig;
import io.github.vaquarkhan.aegis.core.spi.ToolDef;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** @author Viquar Khan */
class CouchdbAdapterTest {

    @Test
    void taxonomyAndTools() {
        CouchdbAdapter adapter = new CouchdbAdapter();
        assertEquals("couchdb", adapter.engineId());
        assertEquals("datastore", adapter.taxonomyClass());
        Set<String> names = adapter.tools(GatewayConfig.builder().defaults().build()).stream()
                .map(ToolDef::name)
                .collect(Collectors.toSet());
        assertTrue(names.contains("list_dbs"));
    }
}
