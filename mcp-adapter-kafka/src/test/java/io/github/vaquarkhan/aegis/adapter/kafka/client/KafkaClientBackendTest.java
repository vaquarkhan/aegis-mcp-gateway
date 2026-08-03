package io.github.vaquarkhan.aegis.adapter.kafka.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

/** @author Viquar Khan */
class KafkaClientBackendTest {

    @Test
    void unreachableBrokerSurfacesAsBackendFailure() {
        try (KafkaAdminSupport admin = new KafkaAdminSupport("127.0.0.1:1", 800, Map.of())) {
            assertThrows(IllegalStateException.class, admin::listTopics,
                    "an unreachable broker must trip the breaker, not look like an empty topic list");
        }
    }

    @Test
    void blankBootstrapIsRejectedBeforeAnyConnection() {
        assertThrows(IllegalArgumentException.class, () -> new KafkaAdminSupport("  "));
        assertThrows(IllegalArgumentException.class, () -> new DlqInspector(null));
    }

    @Test
    void dlqSamplesRenderKeyValueAndHeaders() {
        ConsumerRecord<byte[], byte[]> record = new ConsumerRecord<>(
                "orders-dlq", 2, 41L, bytes("order-7"), bytes("{\"reason\":\"deserialization failed\"}"));
        record.headers().add("x-error-class", bytes("SerializationException"));
        String json = DlqInspector.toJson("orders-dlq", List.of(record));
        assertTrue(json.contains("\"topic\":\"orders-dlq\""));
        assertTrue(json.contains("\"sampled\":1"));
        assertTrue(json.contains("\"partition\":2"));
        assertTrue(json.contains("\"offset\":41"));
        assertTrue(json.contains("order-7"));
        assertTrue(json.contains("x-error-class"));
        assertTrue(json.contains("deserialization failed"));
    }

    @Test
    void dlqSamplesTruncateOversizedPayloadsAndTolerateTombstones() {
        String huge = "x".repeat(4096);
        ConsumerRecord<byte[], byte[]> tombstone =
                new ConsumerRecord<>("orders-dlq", 0, 1L, null, bytes(huge));
        String json = DlqInspector.toJson("orders-dlq", List.of(tombstone));
        assertTrue(json.contains("\"key\":null"));
        assertTrue(json.contains("[truncated]"));
        assertFalse(json.contains(huge));
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
