package io.github.vaquarkhan.aegis.adapter.kafka;

import io.github.vaquarkhan.aegis.adapter.kafka.client.DlqInspector;
import io.github.vaquarkhan.aegis.adapter.kafka.client.DlqOps;
import io.github.vaquarkhan.aegis.adapter.kafka.client.KafkaAdminSupport;
import io.github.vaquarkhan.aegis.adapter.kafka.client.KafkaClusterOps;
import io.github.vaquarkhan.aegis.adapter.kafka.client.SchemaRegistryClient;
import io.github.vaquarkhan.aegis.adapter.kafka.client.SchemaRegistryOps;
import io.github.vaquarkhan.aegis.core.config.GatewayConfig;
import io.github.vaquarkhan.aegis.core.spi.CallContext;
import io.github.vaquarkhan.aegis.core.spi.EngineAdapter;
import io.github.vaquarkhan.aegis.core.spi.ResourceDef;
import io.github.vaquarkhan.aegis.core.spi.ToolClass;
import io.github.vaquarkhan.aegis.core.spi.ToolDef;
import io.github.vaquarkhan.aegis.core.util.Inputs;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Kafka messaging adapter backed by the Kafka {@code AdminClient}, a Schema Registry HTTP client and
 * a short lived tail consumer for dead-letter inspection.
 *
 * <p>Backend failures propagate as {@link IllegalStateException} instead of a placeholder body, so
 * the circuit breaker sees a real failure and a caller is never told a dead cluster looks healthy.
 * Clients are created lazily on first tool call, so listing tools opens no connection.
 *
 * @author Viquar Khan
 */
public final class KafkaAdapter implements EngineAdapter {

    static final String BOOTSTRAP_YAML = "kafka.bootstrap.servers";
    static final String BOOTSTRAP_ENV = "KAFKA_BOOTSTRAP_SERVERS";
    static final String DEFAULT_BOOTSTRAP = "localhost:9092";
    static final String REGISTRY_YAML = "kafka.schema.registry.url";
    static final String REGISTRY_ENV = "SCHEMA_REGISTRY_URL";
    static final String DEFAULT_REGISTRY = "http://localhost:8081";
    static final String DLQ_MAX_RECORDS_YAML = "kafka.dlq.max.records";
    static final String DLQ_MAX_RECORDS_ENV = "KAFKA_DLQ_MAX_RECORDS";

    private static final int DEFAULT_DLQ_MAX_RECORDS = 5;
    private static final int MAX_CONFIG_VALUE_CHARS = 1024;

    private final KafkaClusterOps injectedCluster;
    private final SchemaRegistryOps injectedRegistry;
    private final DlqOps injectedDlq;

    private final Map<String, KafkaClusterOps> clusterCache = new ConcurrentHashMap<>();
    private final Map<String, SchemaRegistryOps> registryCache = new ConcurrentHashMap<>();
    private final Map<String, DlqOps> dlqCache = new ConcurrentHashMap<>();

    public KafkaAdapter() {
        this(null, null, null);
    }

    /** Test seam: supplies backends that do not need a broker or a registry. */
    KafkaAdapter(KafkaClusterOps cluster, SchemaRegistryOps registry, DlqOps dlq) {
        this.injectedCluster = cluster;
        this.injectedRegistry = registry;
        this.injectedDlq = dlq;
    }

    @Override
    public String engineId() {
        return "kafka";
    }

    @Override
    public String taxonomyClass() {
        return "messaging";
    }

    @Override
    public List<ToolDef> tools(GatewayConfig cfg) {
        List<ToolDef> tools = new ArrayList<>();
        tools.add(tool("list_topics", ToolClass.READ, "List Kafka topics",
                "{\"type\":\"object\",\"properties\":{}}",
                ctx -> cluster(cfg).listTopics()));
        tools.add(tool("describe_topic", ToolClass.READ, "Describe a Kafka topic",
                "{\"type\":\"object\",\"properties\":{\"topic\":{\"type\":\"string\"}},\"required\":[\"topic\"]}",
                ctx -> cluster(cfg).describeTopic(Inputs.requireTopic(arg(ctx, "topic")))));
        tools.add(tool("query_schema_registry", ToolClass.READ, "Query Schema Registry for a subject",
                "{\"type\":\"object\",\"properties\":{\"subject\":{\"type\":\"string\"}},\"required\":[\"subject\"]}",
                ctx -> registry(cfg).latestVersion(Inputs.requireId(arg(ctx, "subject")))));
        tools.add(tool("inspect_dlq", ToolClass.READ, "Inspect dead-letter queue topic samples",
                "{\"type\":\"object\",\"properties\":{\"topic\":{\"type\":\"string\"},"
                        + "\"maxRecords\":{\"type\":\"string\"}},\"required\":[\"topic\"]}",
                ctx -> dlq(cfg).sampleTail(
                        Inputs.requireTopic(arg(ctx, "topic")), maxRecords(cfg, arg(ctx, "maxRecords")))));
        tools.add(tool("create_topic", ToolClass.MUTATE, "Create a Kafka topic",
                "{\"type\":\"object\",\"properties\":{\"topic\":{\"type\":\"string\"},\"partitions\":{\"type\":\"string\"},\"approvalToken\":{\"type\":\"string\"}},\"required\":[\"topic\",\"approvalToken\"]}",
                ctx -> cluster(cfg).createTopic(
                        Inputs.requireTopic(arg(ctx, "topic")), partitions(arg(ctx, "partitions")))));
        tools.add(tool("alter_config", ToolClass.MUTATE, "Alter topic or broker config",
                "{\"type\":\"object\",\"properties\":{\"resource\":{\"type\":\"string\"},\"configs\":{\"type\":\"object\"},\"approvalToken\":{\"type\":\"string\"}},\"required\":[\"resource\",\"approvalToken\"]}",
                ctx -> cluster(cfg).alterTopicConfig(Inputs.requireId(arg(ctx, "resource")), configs(ctx))));
        tools.add(tool("reset_offsets", ToolClass.DESTRUCTIVE, "Reset consumer group offsets to earliest",
                "{\"type\":\"object\",\"properties\":{\"groupId\":{\"type\":\"string\"},\"approvalToken\":{\"type\":\"string\"}},\"required\":[\"groupId\",\"approvalToken\"]}",
                ctx -> cluster(cfg).resetOffsets(Inputs.requireId(arg(ctx, "groupId")))));
        tools.add(tool("delete_records", ToolClass.DESTRUCTIVE, "Delete records before the end offset of every partition",
                "{\"type\":\"object\",\"properties\":{\"topic\":{\"type\":\"string\"},\"approvalToken\":{\"type\":\"string\"}},\"required\":[\"topic\",\"approvalToken\"]}",
                ctx -> cluster(cfg).deleteRecords(Inputs.requireTopic(arg(ctx, "topic")))));
        return tools;
    }

    @Override
    public List<ResourceDef> resources(GatewayConfig cfg) {
        return List.of(new ResourceDef(
                "kafka://cluster",
                "kafka-cluster",
                "application/json",
                ctx -> cluster(cfg).listTopics(),
                true));
    }

    @Override
    public Set<String> egressAllowHosts(GatewayConfig cfg) {
        Set<String> hosts = new LinkedHashSet<>();
        for (String pair : bootstrap(cfg).split(",")) {
            String trimmed = pair.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int colon = trimmed.lastIndexOf(':');
            hosts.add(colon > 0 ? trimmed.substring(0, colon) : trimmed);
        }
        try {
            String host = URI.create(registryUrl(cfg)).getHost();
            if (host != null && !host.isBlank()) {
                hosts.add(host);
            }
        } catch (IllegalArgumentException e) {
            // an unparsable registry url contributes no allowed host, so egress stays closed
        }
        return Set.copyOf(hosts);
    }

    static String bootstrap(GatewayConfig cfg) {
        return property(cfg, BOOTSTRAP_YAML, BOOTSTRAP_ENV, DEFAULT_BOOTSTRAP);
    }

    static String registryUrl(GatewayConfig cfg) {
        return property(cfg, REGISTRY_YAML, REGISTRY_ENV, DEFAULT_REGISTRY);
    }

    private KafkaClusterOps cluster(GatewayConfig cfg) {
        if (injectedCluster != null) {
            return injectedCluster;
        }
        return clusterCache.computeIfAbsent(bootstrap(cfg), KafkaAdminSupport::new);
    }

    private SchemaRegistryOps registry(GatewayConfig cfg) {
        if (injectedRegistry != null) {
            return injectedRegistry;
        }
        return registryCache.computeIfAbsent(registryUrl(cfg), SchemaRegistryClient::new);
    }

    private DlqOps dlq(GatewayConfig cfg) {
        if (injectedDlq != null) {
            return injectedDlq;
        }
        return dlqCache.computeIfAbsent(bootstrap(cfg), DlqInspector::new);
    }

    private static int maxRecords(GatewayConfig cfg, String requested) {
        if (requested != null && !requested.isBlank()) {
            return Integer.parseInt(Inputs.requireInt(requested.trim()));
        }
        String configured = property(cfg, DLQ_MAX_RECORDS_YAML, DLQ_MAX_RECORDS_ENV, null);
        if (configured == null || configured.isBlank()) {
            return DEFAULT_DLQ_MAX_RECORDS;
        }
        return Integer.parseInt(Inputs.requireInt(configured.trim()));
    }

    private static int partitions(String requested) {
        if (requested == null || requested.isBlank()) {
            return 1;
        }
        int parsed = Integer.parseInt(Inputs.requireInt(requested.trim()));
        if (parsed < 1) {
            throw new Inputs.InvalidInput("partitions must be at least 1");
        }
        return parsed;
    }

    /**
     * Reads the optional {@code configs} object. Keys are validated as identifiers and values are
     * length bounded, so nothing unbounded or structural reaches the broker.
     */
    private static Map<String, String> configs(CallContext ctx) {
        Map<String, Object> args = ctx.arguments();
        Object raw = args == null ? null : args.get("configs");
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new Inputs.InvalidInput("configs must be an object");
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String key = Inputs.requireId(e.getKey() == null ? null : String.valueOf(e.getKey()));
            String value = e.getValue() == null ? "" : String.valueOf(e.getValue());
            if (value.length() > MAX_CONFIG_VALUE_CHARS) {
                throw new Inputs.InvalidInput("config value exceeds max length " + MAX_CONFIG_VALUE_CHARS);
            }
            out.put(key, value);
        }
        return out;
    }

    private static String property(GatewayConfig cfg, String yamlKey, String envKey, String def) {
        if (cfg == null) {
            return def;
        }
        String yamlValue = cfg.adapterProperty(yamlKey, null);
        if (yamlValue != null && !yamlValue.isBlank()) {
            return yamlValue;
        }
        return cfg.adapterProperty(envKey, def);
    }

    private static ToolDef tool(String name, ToolClass cls, String desc, String schema,
                                Function<CallContext, String> backend) {
        return new ToolDef(name, cls, desc, schema, backend);
    }

    private static String arg(CallContext ctx, String key) {
        Map<String, Object> args = ctx.arguments();
        if (args == null) {
            return null;
        }
        Object v = args.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
