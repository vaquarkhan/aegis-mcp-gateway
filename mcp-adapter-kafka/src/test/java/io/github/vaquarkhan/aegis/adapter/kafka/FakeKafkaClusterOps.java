package io.github.vaquarkhan.aegis.adapter.kafka;

import io.github.vaquarkhan.aegis.adapter.kafka.client.KafkaClusterOps;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory {@link KafkaClusterOps} so adapter wiring can be asserted without a broker.
 *
 * @author Viquar Khan
 */
final class FakeKafkaClusterOps implements KafkaClusterOps {

    private final List<String> calls = new ArrayList<>();
    private final Map<String, String> lastConfigs = new LinkedHashMap<>();
    private boolean failing;
    private int lastPartitions = -1;

    List<String> calls() {
        return calls;
    }

    Map<String, String> lastConfigs() {
        return lastConfigs;
    }

    int lastPartitions() {
        return lastPartitions;
    }

    void failNextCalls() {
        failing = true;
    }

    @Override
    public String listTopics() {
        record("listTopics");
        return "{\"topics\":[\"orders\",\"orders-dlq\"],\"count\":2}";
    }

    @Override
    public String describeTopic(String topic) {
        record("describeTopic:" + topic);
        return "{\"topic\":\"" + topic + "\",\"internal\":false,\"partitions\":[{\"partition\":0}],\"config\":{}}";
    }

    @Override
    public String createTopic(String topic, int partitions) {
        record("createTopic:" + topic);
        lastPartitions = partitions;
        return "{\"status\":\"created\",\"topic\":\"" + topic + "\",\"partitions\":" + partitions + "}";
    }

    @Override
    public String alterTopicConfig(String resource, Map<String, String> configs) {
        record("alterTopicConfig:" + resource);
        lastConfigs.clear();
        lastConfigs.putAll(configs);
        return "{\"status\":\"altered\",\"resource\":\"" + resource + "\"}";
    }

    @Override
    public String resetOffsets(String groupId) {
        record("resetOffsets:" + groupId);
        return "{\"status\":\"reset\",\"groupId\":\"" + groupId + "\",\"to\":\"earliest\"}";
    }

    @Override
    public String deleteRecords(String topic) {
        record("deleteRecords:" + topic);
        return "{\"status\":\"deleted\",\"topic\":\"" + topic + "\"}";
    }

    private void record(String call) {
        calls.add(call);
        if (failing) {
            throw new IllegalStateException("kafka backend error: fake cluster unreachable");
        }
    }
}
