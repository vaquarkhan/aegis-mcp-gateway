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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Samples the tail of a dead-letter topic with a short lived, non committing consumer.
 *
 * <p>The consumer runs with {@code read_committed} isolation and a throwaway group id, assigns
 * partitions manually, and seeks back from the end so a caller sees the newest failures without
 * disturbing any real consumer group. Keys, values and header values are truncated because a DLQ
 * payload is untrusted and can be large.
 *
 * @author Viquar Khan
 */
public final class DlqInspector implements DlqOps {

    private static final Logger LOG = LoggerFactory.getLogger(DlqInspector.class);

    private static final int MAX_FIELD_CHARS = 512;
    private static final int MAX_RECORDS_CEILING = 50;
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(3);
    private static final int POLL_ATTEMPTS = 3;

    private final String bootstrapServers;
    private final int timeoutMs;

    public DlqInspector(String bootstrapServers) {
        this(bootstrapServers, 15_000);
    }

    public DlqInspector(String bootstrapServers, int timeoutMs) {
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            throw new IllegalArgumentException("bootstrap servers required");
        }
        this.bootstrapServers = bootstrapServers.trim();
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : 15_000;
    }

    @Override
    public String sampleTail(String topic, int maxRecords) {
        int limit = Math.min(Math.max(1, maxRecords), MAX_RECORDS_CEILING);
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(consumerProps())) {
            List<PartitionInfo> partitions = consumer.partitionsFor(topic, Duration.ofMillis(timeoutMs));
            if (partitions == null || partitions.isEmpty()) {
                throw new IllegalStateException("topic not found or has no partitions: " + topic);
            }
            List<TopicPartition> assignment = new ArrayList<>();
            for (PartitionInfo p : partitions) {
                assignment.add(new TopicPartition(topic, p.partition()));
            }
            consumer.assign(assignment);
            Map<TopicPartition, Long> beginnings = consumer.beginningOffsets(assignment, Duration.ofMillis(timeoutMs));
            Map<TopicPartition, Long> ends = consumer.endOffsets(assignment, Duration.ofMillis(timeoutMs));
            long available = 0L;
            for (TopicPartition tp : assignment) {
                long begin = beginnings.getOrDefault(tp, 0L);
                long end = ends.getOrDefault(tp, 0L);
                available += Math.max(0L, end - begin);
                consumer.seek(tp, Math.max(begin, end - limit));
            }
            if (available == 0L) {
                return "{\"topic\":\"" + Inputs.jsonEscape(topic) + "\",\"sampled\":0,\"records\":[]}";
            }
            List<ConsumerRecord<byte[], byte[]>> collected = new ArrayList<>();
            for (int attempt = 0; attempt < POLL_ATTEMPTS && collected.size() < limit; attempt++) {
                ConsumerRecords<byte[], byte[]> polled = consumer.poll(POLL_TIMEOUT);
                for (ConsumerRecord<byte[], byte[]> record : polled) {
                    collected.add(record);
                }
            }
            collected.sort(Comparator
                    .comparingInt(ConsumerRecord<byte[], byte[]>::partition)
                    .thenComparingLong(ConsumerRecord::offset));
            if (collected.size() > limit) {
                collected = new ArrayList<>(collected.subList(collected.size() - limit, collected.size()));
            }
            return toJson(topic, collected);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            LOG.warn("dlq sample failed topic={} msg={}", topic, e.getMessage());
            throw new IllegalStateException("kafka backend error: " + e.getMessage(), e);
        }
    }

    private Properties consumerProps() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "aegis-dlq-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, MAX_RECORDS_CEILING);
        props.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, timeoutMs);
        props.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, timeoutMs);
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, "aegis-mcp-gateway-dlq");
        return props;
    }

    /** Renders sampled records. Package visible so the shape can be asserted without a broker. */
    static String toJson(String topic, List<ConsumerRecord<byte[], byte[]>> records) {
        StringBuilder sb = new StringBuilder("{\"topic\":\"").append(Inputs.jsonEscape(topic))
                .append("\",\"sampled\":").append(records.size()).append(",\"records\":[");
        for (int i = 0; i < records.size(); i++) {
            ConsumerRecord<byte[], byte[]> record = records.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"partition\":").append(record.partition())
                    .append(",\"offset\":").append(record.offset())
                    .append(",\"timestamp\":").append(record.timestamp())
                    .append(",\"key\":").append(quotedOrNull(record.key()))
                    .append(",\"value\":").append(quotedOrNull(record.value()))
                    .append(",\"headers\":").append(headersJson(record))
                    .append('}');
        }
        return sb.append("]}").toString();
    }

    private static String headersJson(ConsumerRecord<byte[], byte[]> record) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Header header : record.headers()) {
            headers.put(header.key(), truncate(decode(header.value())));
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(Inputs.jsonEscape(e.getKey())).append("\":\"")
                    .append(Inputs.jsonEscape(e.getValue())).append('"');
        }
        return sb.append('}').toString();
    }

    private static String quotedOrNull(byte[] raw) {
        if (raw == null) {
            return "null";
        }
        return "\"" + Inputs.jsonEscape(truncate(decode(raw))) + "\"";
    }

    private static String decode(byte[] raw) {
        return raw == null ? "" : new String(raw, StandardCharsets.UTF_8);
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_FIELD_CHARS ? s : s.substring(0, MAX_FIELD_CHARS) + "...[truncated]";
    }
}
