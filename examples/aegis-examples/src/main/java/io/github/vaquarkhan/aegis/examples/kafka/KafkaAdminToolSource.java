/*
 * Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vaquarkhan.aegis.examples.kafka;

import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.json.JsonValues;
import io.github.vaquarkhan.aegis.engine.model.ToolDescriptor;
import io.github.vaquarkhan.aegis.engine.model.ToolResult;
import io.github.vaquarkhan.aegis.engine.spi.ExecutionContext;
import io.github.vaquarkhan.aegis.engine.spi.ToolExecutor;
import io.github.vaquarkhan.aegis.engine.spi.ToolSource;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.CreateTopicsOptions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.config.ConfigResource;

/**
 * Apache Kafka topics through the Kafka {@link Admin} client, for clusters without a REST layer.
 * Capability only: every tool's class, resources and dry-run mode come from the operator's
 * {@code tools:} section, exactly as for MCP and HTTP sources.
 *
 * <p>Suggested classification (see {@code adopters/configurations/kafka/kafka.yaml}):
 * {@code kafka_list_topics} and
 * {@code kafka_describe_topic} READ, {@code kafka_create_topic} MUTATE with dry-run
 * {@code ARGUMENT validateOnly} (Kafka's own CreateTopics validate-only mode), and
 * {@code kafka_delete_topic} DESTRUCTIVE.
 *
 * <p>Egress: every bootstrap server is reported as a {@code tcp://host:port} endpoint. Its host joins
 * the egress host list like an HTTP source's {@code baseUrl}, and every call checks each bootstrap
 * host and every DNS answer for it against {@code egress} before the Admin client is used, so a
 * broker name that resolves to a private address needs a matching {@code allowCidrs} entry. The
 * brokers the cluster then advertises in its metadata are reached by the Kafka client directly.
 */
public final class KafkaAdminToolSource implements ToolSource {

    private final String id;
    private final List<URI> endpoints;
    private final Supplier<Admin> adminSupplier;
    private Admin admin;

    public KafkaAdminToolSource(String id, List<URI> endpoints, Supplier<Admin> adminSupplier) {
        this.id = id;
        this.endpoints = List.copyOf(endpoints);
        this.adminSupplier = adminSupplier;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public List<URI> configuredEndpoints() {
        return endpoints;
    }

    private synchronized Admin admin() {
        if (admin == null) {
            admin = adminSupplier.get();
        }
        return admin;
    }

    private static final String TOPIC_PROP = "{\"topic\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":249}}";

    @Override
    public List<SourcedTool> discover() {
        return List.of(
                tool("kafka_list_topics", "List Kafka topic names (internal topics excluded).",
                        "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}", this::listTopics),
                tool("kafka_describe_cluster", "Describe the Kafka cluster ID, controller and broker nodes.",
                        "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}", this::describeCluster),
                tool("kafka_describe_topic", "Describe one topic: partition count and replication.",
                        "{\"type\":\"object\",\"properties\":" + TOPIC_PROP + ",\"required\":[\"topic\"],\"additionalProperties\":false}",
                        this::describeTopic),
                tool("kafka_describe_topic_configs", "Describe one topic configuration without exposing sensitive values.",
                        "{\"type\":\"object\",\"properties\":" + TOPIC_PROP + ",\"required\":[\"topic\"],\"additionalProperties\":false}",
                        this::describeTopicConfigs),
                tool("kafka_create_topic", "Create a topic. Supports Kafka validate-only.",
                        "{\"type\":\"object\",\"properties\":{\"topic\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":249},"
                                + "\"partitions\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":10000},"
                                + "\"replicationFactor\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":32767},"
                                + "\"validateOnly\":{\"type\":\"boolean\"}},\"required\":[\"topic\",\"partitions\"],"
                                + "\"additionalProperties\":false}", this::createTopic),
                tool("kafka_delete_topic", "Delete a topic and all of its data.",
                        "{\"type\":\"object\",\"properties\":" + TOPIC_PROP + ",\"required\":[\"topic\"],\"additionalProperties\":false}",
                        this::deleteTopic));
    }

    @FunctionalInterface
    private interface Op {
        ToolResult run(Map<String, Object> args, long timeoutMillis) throws Exception;
    }

    private SourcedTool tool(String name, String description, String schema, Op op) {
        ToolDescriptor d = new ToolDescriptor(name, null, description, Json.parseObject(schema), null, null);
        return new SourcedTool(d, new ToolExecutor() {
            @Override
            public List<URI> egressTargets(Map<String, Object> backendArguments) {
                return endpoints; // the bootstrap servers; the target is configuration, never an argument
            }

            @Override
            public ToolResult execute(ExecutionContext ctx) throws ToolExecutor.ExecutionException {
                try {
                    return op.run(ctx.arguments(), ctx.timeoutMillis());
                } catch (TimeoutException e) {
                    throw new ToolExecutor.ExecutionException("Kafka admin call timed out", true, e);
                } catch (ExecutionException e) {
                    Throwable c = e.getCause() == null ? e : e.getCause();
                    // Kafka ApiExceptions (TopicExists, UnknownTopic, InvalidPartitions...) are caller errors.
                    if (c instanceof org.apache.kafka.common.errors.ApiException) {
                        return ToolResult.text("Kafka rejected the request: " + c.getClass().getSimpleName() + ": " + c.getMessage(), true);
                    }
                    throw new ToolExecutor.ExecutionException("Kafka admin call failed: " + c.getClass().getSimpleName(), false, c);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ToolExecutor.ExecutionException("interrupted", false, e);
                } catch (Exception e) {
                    throw new ToolExecutor.ExecutionException("Kafka admin call failed: " + e.getClass().getSimpleName(), false, e);
                }
            }
        });
    }

    private ToolResult listTopics(Map<String, Object> args, long timeout) throws Exception {
        TreeSet<String> names = new TreeSet<>(admin().listTopics().names().get(timeout, TimeUnit.MILLISECONDS));
        return ToolResult.structured(String.join(",", names), Map.of("topics", new ArrayList<>(names)));
    }

    private ToolResult describeCluster(Map<String, Object> args, long timeout) throws Exception {
        var result = admin().describeCluster();
        String clusterId = result.clusterId().get(timeout, TimeUnit.MILLISECONDS);
        Node controller = result.controller().get(timeout, TimeUnit.MILLISECONDS);
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Node node : result.nodes().get(timeout, TimeUnit.MILLISECONDS).stream().sorted(java.util.Comparator.comparingInt(Node::id)).toList()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", (long) node.id());
            item.put("host", node.host());
            item.put("port", (long) node.port());
            item.put("rack", node.rack());
            nodes.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("clusterId", clusterId);
        out.put("controllerId", controller == null ? null : (long) controller.id());
        out.put("nodes", nodes);
        return ToolResult.structured(Json.write(out), out);
    }

    private ToolResult describeTopic(Map<String, Object> args, long timeout) throws Exception {
        String topic = (String) args.get("topic");
        TopicDescription d = admin().describeTopics(List.of(topic)).allTopicNames().get(timeout, TimeUnit.MILLISECONDS).get(topic);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("topic", d.name());
        m.put("partitions", (long) d.partitions().size());
        m.put("replicationFactor", d.partitions().isEmpty() ? 0L : (long) d.partitions().get(0).replicas().size());
        m.put("internal", d.isInternal());
        return ToolResult.structured(Json.write(m), m);
    }

    private ToolResult describeTopicConfigs(Map<String, Object> args, long timeout) throws Exception {
        String topic = (String) args.get("topic");
        ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
        Config config = admin().describeConfigs(List.of(resource)).all().get(timeout, TimeUnit.MILLISECONDS).get(resource);
        List<Map<String, Object>> entries = new ArrayList<>();
        for (ConfigEntry entry : config.entries().stream().sorted(java.util.Comparator.comparing(ConfigEntry::name)).toList()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("name", entry.name());
            value.put("value", entry.isSensitive() ? "<redacted>" : entry.value());
            value.put("sensitive", entry.isSensitive());
            value.put("default", entry.isDefault());
            value.put("source", entry.source().name());
            entries.add(value);
        }
        Map<String, Object> out = Map.of("topic", topic, "configs", entries);
        return ToolResult.structured(Json.write(out), out);
    }

    private ToolResult createTopic(Map<String, Object> args, long timeout) throws Exception {
        String topic = (String) args.get("topic");
        int partitions = JsonValues.integral(args.get("partitions")).intValue();
        Optional<Short> rf = Optional.ofNullable(JsonValues.integral(args.get("replicationFactor"))).map(Long::shortValue);
        boolean validateOnly = Boolean.TRUE.equals(args.get("validateOnly"));
        NewTopic nt = new NewTopic(topic, Optional.of(partitions), rf);
        admin().createTopics(List.of(nt), new CreateTopicsOptions().validateOnly(validateOnly))
                .all().get(timeout, TimeUnit.MILLISECONDS);
        return ToolResult.text((validateOnly ? "Validated (not created) topic " : "Created topic ") + topic, false);
    }

    private ToolResult deleteTopic(Map<String, Object> args, long timeout) throws Exception {
        String topic = (String) args.get("topic");
        admin().deleteTopics(List.of(topic)).all().get(timeout, TimeUnit.MILLISECONDS);
        return ToolResult.text("Deleted topic " + topic, false);
    }

    @Override
    public synchronized void close() {
        if (admin != null) {
            admin.close();
            admin = null;
        }
    }
}
