package io.github.vaquarkhan.aegis.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.core.router.RetrievalRouter;
import io.github.vaquarkhan.aegis.core.router.TaxonomyRouter;
import io.github.vaquarkhan.aegis.core.spi.ToolClass;
import io.github.vaquarkhan.aegis.core.spi.ToolDef;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * @author Viquar Khan
 */
class RetrievalRouterTest {

    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{}}";

    private static Map<String, ToolDef> manifest() {
        Map<String, ToolDef> tools = new LinkedHashMap<>();
        tools.put("list_jobs", new ToolDef("list_jobs", ToolClass.READ, "List Flink jobs", SCHEMA, c -> ""));
        tools.put("describe_topic",
                new ToolDef("describe_topic", ToolClass.READ, "Describe a Kafka topic", SCHEMA, c -> ""));
        tools.put("get_table",
                new ToolDef("get_table", ToolClass.READ, "Get Iceberg table metadata", SCHEMA, c -> ""));
        return tools;
    }

    private static RetrievalRouter router() {
        TaxonomyRouter taxonomy = new TaxonomyRouter();
        taxonomy.register("list_jobs", "flink", "streaming");
        taxonomy.register("describe_topic", "kafka", "messaging");
        taxonomy.register("get_table", "iceberg", "lakehouse");
        return new RetrievalRouter(taxonomy);
    }

    private static List<String> names(List<ToolDef> tools) {
        return tools.stream().map(ToolDef::name).collect(Collectors.toList());
    }

    @Test
    void prunesToTheToolsThatMatchTheIntent() {
        assertEquals(List.of("describe_topic"), names(router().prune(manifest(), "inspect a kafka topic")));
        assertEquals(List.of("get_table"), names(router().prune(manifest(), "lakehouse metadata")));
    }

    @Test
    void anEmptyOrUnmatchedHintReturnsTheWholeAuthorizedSet() {
        assertEquals(3, router().prune(manifest(), null).size());
        assertEquals(3, router().prune(manifest(), "   ").size());
        assertEquals(3, router().prune(manifest(), "something unrelated").size(),
                "pruning may cost recall but must never leave the caller with nothing");
    }

    @Test
    void neverReturnsAToolThatWasNotAuthorized() {
        List<ToolDef> pruned = router().prune(manifest(), "flink streaming kafka iceberg");
        assertTrue(manifest().keySet().containsAll(names(pruned)));
        assertTrue(router().prune(Map.of(), "anything").isEmpty());
    }
}
