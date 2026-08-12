package io.github.vaquarkhan.aegis.adapter.pulsar;

import io.github.vaquarkhan.aegis.core.config.GatewayConfig;
import io.github.vaquarkhan.aegis.core.spi.CallContext;
import io.github.vaquarkhan.aegis.core.spi.EngineAdapter;
import io.github.vaquarkhan.aegis.core.spi.ResourceDef;
import io.github.vaquarkhan.aegis.core.spi.ToolClass;
import io.github.vaquarkhan.aegis.core.spi.ToolDef;
import io.github.vaquarkhan.aegis.core.util.HttpJsonClient;
import io.github.vaquarkhan.aegis.core.util.Inputs;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Apache Pulsar adapter. Talks to the configured HTTP/REST surface; failures propagate for the breaker.
 *
 * @author Viquar Khan
 */
public final class PulsarAdapter implements EngineAdapter {

    private static final McpJsonMapper JSON = defaultJsonMapper();

    private static McpJsonMapper defaultJsonMapper() {
        return new JacksonMcpJsonMapperSupplier().get();
    }

    @Override
    public String engineId() {
        return "pulsar";
    }

    @Override
    public String taxonomyClass() {
        return "messaging";
    }

    @Override
    public List<ToolDef> tools(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        List<ToolDef> tools = new ArrayList<>();
        tools.add(tool("list_clusters", ToolClass.READ, "List Pulsar clusters",
                new JsonSchema("object", Map.of(), List.of(), null, null, null),
                ctx -> client.get("/admin/v2/clusters")));
        tools.add(tool("list_tenants", ToolClass.READ, "List Pulsar tenants",
                new JsonSchema("object", Map.of(), List.of(), null, null, null),
                ctx -> client.get("/admin/v2/tenants")));
        tools.add(tool("list_topics", ToolClass.READ, "List topics in a namespace",
                new JsonSchema("object", Map.of("namespace", Map.of("type", "string")), List.of("namespace"), null, null, null),
                ctx -> client.get("/admin/v2/persistent/" + Inputs.requirePath(arg(ctx, "namespace")))));
        tools.add(tool("get_topic_stats", ToolClass.READ, "Get Pulsar topic stats",
                new JsonSchema("object", Map.of("topic", Map.of("type", "string")), List.of("topic"), null, null, null),
                ctx -> client.get("/admin/v2/persistent/" + Inputs.requirePath(arg(ctx, "topic")) + "/stats")));
        tools.add(tool("create_topic", ToolClass.MUTATE, "Create a Pulsar topic",
                new JsonSchema("object", Map.of("topic", Map.of("type", "string"), "approvalToken", Map.of("type", "string")), List.of("topic", "approvalToken"), null, null, null),
                ctx -> client.put("/admin/v2/persistent/" + Inputs.requirePath(arg(ctx, "topic")), "{}")));
        tools.add(tool("delete_topic", ToolClass.DESTRUCTIVE, "Delete a Pulsar topic",
                new JsonSchema("object", Map.of("topic", Map.of("type", "string"), "approvalToken", Map.of("type", "string")), List.of("topic", "approvalToken"), null, null, null),
                ctx -> client.delete("/admin/v2/persistent/" + Inputs.requirePath(arg(ctx, "topic")))));
        return tools;
    }

    @Override
    public List<ResourceDef> resources(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        return List.of(new ResourceDef(
                "pulsar://status",
                "pulsar-status",
                "application/json",
                ctx -> client.get("/admin/v2/clusters"),
                true));
    }

    @Override
    public Set<String> egressAllowHosts(GatewayConfig cfg) {
        try {
            String host = URI.create(baseUrl(cfg)).getHost();
            return host == null || host.isBlank() ? Set.of() : Set.of(host);
        } catch (Exception e) {
            return Set.of();
        }
    }

    static String baseUrl(GatewayConfig cfg) {
        return cfg.adapterProperty("pulsar.url",
                cfg.adapterProperty("PULSAR_ADMIN_URL", "http://localhost:8080"));
    }

    private static ToolDef tool(String name, ToolClass cls, String desc, JsonSchema schema,
                                Function<CallContext, String> backend) {
        try {
            return new ToolDef(name, cls, desc, JSON.writeValueAsString(schema), backend);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize schema for tool: " + name, e);
        }
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
