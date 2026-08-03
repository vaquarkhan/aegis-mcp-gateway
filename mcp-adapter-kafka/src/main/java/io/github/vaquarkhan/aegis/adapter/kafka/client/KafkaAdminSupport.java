/*
 * Licensed to the Aegis MCP Gateway project under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.vaquarkhan.aegis.adapter.kafka.client;

import io.github.vaquarkhan.aegis.core.util.Inputs;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsSpec;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.RecordsToDelete;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.config.ConfigResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Live {@link KafkaClusterOps} backed by the Kafka {@code AdminClient}.
 *
 * <p>The admin client is created lazily on first use so building the tool list never opens a
 * connection, and every broker error is rethrown as {@link IllegalStateException} so the gateway
 * circuit breaker can trip. Config values whose key looks like a secret are redacted before they
 * reach a caller.
 *
 * @author Viquar Khan
 */
public final class KafkaAdminSupport implements KafkaClusterOps {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaAdminSupport.class);

    private static final int DEFAULT_TIMEOUT_MS = 15_000;
    private static final Set<String> SECRET_HINTS = Set.of("password", "secret", "credential", "keystore", "truststore");

    private final String bootstrapServers;
    private final int timeoutMs;
    private final Map<String, String> extraClientProps;

    private volatile Admin admin;

    public KafkaAdminSupport(String bootstrapServers) {
        this(bootstrapServers, DEFAULT_TIMEOUT_MS, Map.of());
    }

    public KafkaAdminSupport(String bootstrapServers, int timeoutMs, Map<String, String> extraClientProps) {
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            throw new IllegalArgumentException("bootstrap servers required");
        }
        this.bootstrapServers = bootstrapServers.trim();
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        this.extraClientProps = extraClientProps == null ? Map.of() : Map.copyOf(extraClientProps);
    }

    @Override
    public String listTopics() {
        Set<String> names = new TreeSet<>(await(admin().listTopics().names(), "list_topics"));
        StringBuilder sb = new StringBuilder("{\"topics\":[");
        boolean first = true;
        for (String name : names) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(Inputs.jsonEscape(name)).append('"');
        }
        return sb.append("],\"count\":").append(names.size()).append('}').toString();
    }

    @Override
    public String describeTopic(String topic) {
        Map<String, TopicDescription> described =
                await(admin().describeTopics(List.of(topic)).allTopicNames(), "describe_topic");
        TopicDescription desc = described.get(topic);
        if (desc == null) {
            throw new IllegalStateException("topic not found: " + topic);
        }
        StringBuilder sb = new StringBuilder("{\"topic\":\"").append(Inputs.jsonEscape(desc.name()))
                .append("\",\"internal\":").append(desc.isInternal())
                .append(",\"partitions\":[");
        boolean first = true;
        for (TopicPartitionInfo p : desc.partitions()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"partition\":").append(p.partition())
                    .append(",\"leader\":").append(nodeId(p.leader()))
                    .append(",\"replicas\":").append(nodeIds(p.replicas()))
                    .append(",\"isr\":").append(nodeIds(p.isr()))
                    .append('}');
        }
        sb.append("],\"config\":").append(topicConfigJson(topic)).append('}');
        return sb.toString();
    }

    @Override
    public String createTopic(String topic, int partitions) {
        int count = partitions > 0 ? partitions : 1;
        NewTopic newTopic = new NewTopic(topic, Optional.of(count), Optional.empty());
        await(admin().createTopics(List.of(newTopic)).all(), "create_topic");
        return "{\"status\":\"created\",\"topic\":\"" + Inputs.jsonEscape(topic)
                + "\",\"partitions\":" + count + "}";
    }

    @Override
    public String alterTopicConfig(String resource, Map<String, String> configs) {
        if (configs == null || configs.isEmpty()) {
            return "{\"status\":\"unchanged\",\"resource\":\"" + Inputs.jsonEscape(resource)
                    + "\",\"current\":" + topicConfigJson(resource) + "}";
        }
        ConfigResource target = new ConfigResource(ConfigResource.Type.TOPIC, resource);
        Collection<AlterConfigOp> ops = new ArrayList<>();
        for (Map.Entry<String, String> e : configs.entrySet()) {
            ops.add(new AlterConfigOp(new ConfigEntry(e.getKey(), e.getValue()), AlterConfigOp.OpType.SET));
        }
        await(admin().incrementalAlterConfigs(Map.of(target, ops)).all(), "alter_config");
        StringBuilder sb = new StringBuilder("{\"status\":\"altered\",\"resource\":\"")
                .append(Inputs.jsonEscape(resource)).append("\",\"applied\":{");
        boolean first = true;
        for (Map.Entry<String, String> e : new LinkedHashMap<>(configs).entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(Inputs.jsonEscape(e.getKey())).append("\":\"")
                    .append(Inputs.jsonEscape(maskIfSecret(e.getKey(), e.getValue()))).append('"');
        }
        return sb.append("}}").toString();
    }

    @Override
    public String resetOffsets(String groupId) {
        Map<TopicPartition, OffsetAndMetadata> current = await(
                admin().listConsumerGroupOffsets(Map.of(groupId, new ListConsumerGroupOffsetsSpec()))
                        .partitionsToOffsetAndMetadata(groupId),
                "reset_offsets");
        if (current == null || current.isEmpty()) {
            return "{\"status\":\"unchanged\",\"groupId\":\"" + Inputs.jsonEscape(groupId)
                    + "\",\"reason\":\"group has no committed offsets\"}";
        }
        Map<TopicPartition, OffsetSpec> earliestSpec = new LinkedHashMap<>();
        for (TopicPartition tp : current.keySet()) {
            earliestSpec.put(tp, OffsetSpec.earliest());
        }
        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> earliest =
                await(admin().listOffsets(earliestSpec).all(), "reset_offsets");
        Map<TopicPartition, OffsetAndMetadata> target = new LinkedHashMap<>();
        for (Map.Entry<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> e : earliest.entrySet()) {
            target.put(e.getKey(), new OffsetAndMetadata(Math.max(0L, e.getValue().offset())));
        }
        await(admin().alterConsumerGroupOffsets(groupId, target).all(), "reset_offsets");
        StringBuilder sb = new StringBuilder("{\"status\":\"reset\",\"groupId\":\"")
                .append(Inputs.jsonEscape(groupId)).append("\",\"to\":\"earliest\",\"partitions\":[");
        boolean first = true;
        for (Map.Entry<TopicPartition, OffsetAndMetadata> e : target.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            OffsetAndMetadata before = current.get(e.getKey());
            sb.append("{\"topic\":\"").append(Inputs.jsonEscape(e.getKey().topic()))
                    .append("\",\"partition\":").append(e.getKey().partition())
                    .append(",\"from\":").append(before == null ? -1L : before.offset())
                    .append(",\"to\":").append(e.getValue().offset())
                    .append('}');
        }
        return sb.append("]}").toString();
    }

    @Override
    public String deleteRecords(String topic) {
        Map<String, TopicDescription> described =
                await(admin().describeTopics(List.of(topic)).allTopicNames(), "delete_records");
        TopicDescription desc = described.get(topic);
        if (desc == null) {
            throw new IllegalStateException("topic not found: " + topic);
        }
        Map<TopicPartition, OffsetSpec> latestSpec = new LinkedHashMap<>();
        for (TopicPartitionInfo p : desc.partitions()) {
            latestSpec.put(new TopicPartition(topic, p.partition()), OffsetSpec.latest());
        }
        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> ends =
                await(admin().listOffsets(latestSpec).all(), "delete_records");
        Map<TopicPartition, RecordsToDelete> deletions = new LinkedHashMap<>();
        for (Map.Entry<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> e : ends.entrySet()) {
            deletions.put(e.getKey(), RecordsToDelete.beforeOffset(e.getValue().offset()));
        }
        await(admin().deleteRecords(deletions).all(), "delete_records");
        StringBuilder sb = new StringBuilder("{\"status\":\"deleted\",\"topic\":\"")
                .append(Inputs.jsonEscape(topic)).append("\",\"partitions\":[");
        boolean first = true;
        for (Map.Entry<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> e : ends.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"partition\":").append(e.getKey().partition())
                    .append(",\"beforeOffset\":").append(e.getValue().offset())
                    .append('}');
        }
        return sb.append("]}").toString();
    }

    @Override
    public void close() {
        Admin a = admin;
        admin = null;
        if (a != null) {
            a.close(java.time.Duration.ofSeconds(5));
        }
    }

    private String topicConfigJson(String topic) {
        ConfigResource target = new ConfigResource(ConfigResource.Type.TOPIC, topic);
        Map<ConfigResource, Config> configs =
                await(admin().describeConfigs(List.of(target)).all(), "describe_configs");
        Config config = configs.get(target);
        StringBuilder sb = new StringBuilder("{");
        if (config != null) {
            boolean first = true;
            for (ConfigEntry entry : config.entries()) {
                if (entry.isDefault()) {
                    continue;
                }
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(Inputs.jsonEscape(entry.name())).append("\":\"")
                        .append(Inputs.jsonEscape(maskIfSecret(entry.name(), entry.value()))).append('"');
            }
        }
        return sb.append('}').toString();
    }

    private Admin admin() {
        Admin existing = admin;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (admin == null) {
                Properties props = new Properties();
                props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
                props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, timeoutMs);
                props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, timeoutMs);
                props.put(AdminClientConfig.CLIENT_ID_CONFIG, "aegis-mcp-gateway-admin");
                props.putAll(extraClientProps);
                try {
                    admin = Admin.create(props);
                } catch (RuntimeException e) {
                    LOG.warn("kafka admin client creation failed bootstrap={} msg={}",
                            bootstrapServers, e.getMessage());
                    throw new IllegalStateException("kafka admin unavailable: " + e.getMessage(), e);
                }
            }
            return admin;
        }
    }

    private <T> T await(KafkaFuture<T> future, String op) {
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("kafka " + op + " interrupted", e);
        } catch (Exception e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            String reason = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            LOG.warn("kafka {} failed bootstrap={} msg={}", op, bootstrapServers, reason);
            throw new IllegalStateException("kafka backend error: " + reason, cause);
        }
    }

    private static String maskIfSecret(String key, String value) {
        if (value == null) {
            return "";
        }
        String lower = key == null ? "" : key.toLowerCase(java.util.Locale.ROOT);
        for (String hint : SECRET_HINTS) {
            if (lower.contains(hint)) {
                return "<redacted>";
            }
        }
        return value;
    }

    private static int nodeId(Node node) {
        return node == null ? -1 : node.id();
    }

    private static String nodeIds(List<Node> nodes) {
        StringBuilder sb = new StringBuilder("[");
        if (nodes != null) {
            for (int i = 0; i < nodes.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(nodes.get(i).id());
            }
        }
        return sb.append(']').toString();
    }
}
