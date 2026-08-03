package io.github.vaquarkhan.aegis.adapter.zookeeper;

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
 * Apache ZooKeeper adapter. Talks to the configured HTTP/REST surface; failures propagate for the breaker.
 *
 * @author Viquar Khan
 */
public final class ZookeeperAdapter implements EngineAdapter {

    @Override
    public String engineId() {
        return "zookeeper";
    }

    @Override
    public String taxonomyClass() {
        return "coordination";
    }

    @Override
    public List<ToolDef> tools(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        List<ToolDef> tools = new ArrayList<>();
        tools.add(tool("ruok", ToolClass.READ, "ZooKeeper ruok admin command",
                "{\"type\":\"object\",\"properties\":{}}",
                ctx -> client.get("/commands/ruok")));
        tools.add(tool("stat", ToolClass.READ, "ZooKeeper stat",
                "{\"type\":\"object\",\"properties\":{}}",
                ctx -> client.get("/commands/stats")));
        tools.add(tool("list_children", ToolClass.READ, "List znode children via REST facade",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}",
                ctx -> client.get("/znodes/v1" + (arg(ctx, "path") == null || arg(ctx, "path").isBlank() ? "/" : Inputs.requirePath(arg(ctx, "path"))))));
        tools.add(tool("delete_znode", ToolClass.DESTRUCTIVE, "Delete a znode",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"approvalToken\":{\"type\":\"string\"}},\"required\":[\"path\",\"approvalToken\"]}",
                ctx -> client.delete("/znodes/v1" + Inputs.requirePath(arg(ctx, "path")))));
        return tools;
    }

    @Override
    public List<ResourceDef> resources(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        return List.of(new ResourceDef(
                "zookeeper://status",
                "zookeeper-status",
                "application/json",
                ctx -> client.get("/commands/ruok"),
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
        return cfg.adapterProperty("zookeeper.url",
                cfg.adapterProperty("ZOOKEEPER_REST_URL", "http://localhost:9998"));
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
