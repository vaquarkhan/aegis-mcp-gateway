package io.github.vaquarkhan.aegis.adapter.pulsar;

import io.github.vaquarkhan.aegis.core.config.GatewayConfig;
import io.github.vaquarkhan.aegis.core.spi.CallContext;
import io.github.vaquarkhan.aegis.core.spi.EngineAdapter;
import io.github.vaquarkhan.aegis.core.spi.ResourceDef;
import io.github.vaquarkhan.aegis.core.spi.ToolClass;
import io.github.vaquarkhan.aegis.core.spi.ToolDef;
import io.github.vaquarkhan.aegis.core.util.HttpJsonClient;
import io.github.vaquarkhan.aegis.core.util.Inputs;
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
                "{\"type\":\"object\",\"properties\":{}}",
                ctx -> client.get("/admin/v2/clusters")));
        tools.add(tool("list_tenants", ToolClass.READ, "List Pulsar tenants",
                "{\"type\":\"object\",\"properties\":{}}",
                ctx -> client.get("/admin/v2/tenants")));
        tools.add(tool("list_topics", ToolClass.READ, "List topics in a namespace",
                "{\"type\":\"object\",\"properties\":{\"namespace\":{\"type\":\"string\"}},\"required\":[\"namespace\"]}",
                ctx -> client.get("/admin/v2/persistent/" + Inputs.requirePath(arg(ctx, "namespace")))));
        tools.add(tool("get_topic_stats", ToolClass.READ, "Get Pulsar topic stats",
                "{\"type\":\"object\",\"properties\":{\"topic\":{\"type\":\"string\"}},\"required\":[\"topic\"]}",
                ctx -> client.get("/admin/v2/persistent/" + Inputs.requirePath(arg(ctx, "topic")) + "/stats")));
        tools.add(tool("create_topic", ToolClass.MUTATE, "Create a Pulsar topic",
                "{\"type\":\"object\",\"properties\":{\"topic\":{\"type\":\"string\"},\"approvalToken\":{\"type\":\"string\"}},\"required\":[\"topic\",\"approvalToken\"]}",
                ctx -> client.put("/admin/v2/persistent/" + Inputs.requirePath(arg(ctx, "topic")), "{}")));
        tools.add(tool("delete_topic", ToolClass.DESTRUCTIVE, "Delete a Pulsar topic",
                "{\"type\":\"object\",\"properties\":{\"topic\":{\"type\":\"string\"},\"approvalToken\":{\"type\":\"string\"}},\"required\":[\"topic\",\"approvalToken\"]}",
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
