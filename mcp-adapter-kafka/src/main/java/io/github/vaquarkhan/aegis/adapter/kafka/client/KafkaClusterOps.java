package io.github.vaquarkhan.aegis.adapter.kafka.client;

import java.util.Map;

/**
 * Cluster operations the Kafka adapter needs, expressed as JSON returning calls.
 *
 * <p>The interface exists so the adapter can be exercised without a broker. Implementations must
 * throw {@link IllegalStateException} when the cluster is unreachable so the circuit breaker sees a
 * real failure instead of a healthy looking placeholder body.
 *
 * @author Viquar Khan
 */
public interface KafkaClusterOps extends AutoCloseable {

    String listTopics();

    String describeTopic(String topic);

    String createTopic(String topic, int partitions);

    String alterTopicConfig(String resource, Map<String, String> configs);

    String resetOffsets(String groupId);

    String deleteRecords(String topic);

    @Override
    default void close() {
        // implementations holding a broker connection override this
    }
}
