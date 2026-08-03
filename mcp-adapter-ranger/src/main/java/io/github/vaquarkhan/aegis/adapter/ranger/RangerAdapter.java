package io.github.vaquarkhan.aegis.adapter.ranger;

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
 * Apache Ranger adapter. Talks to the configured HTTP/REST surface; failures propagate for the breaker.
 *
 * @author Viquar Khan
 */
public final class RangerAdapter implements EngineAdapter {

    @Override
    public String engineId() {
        return "ranger";
    }

    @Override
    public String taxonomyClass() {
        return "governance";
    }

    @Override
    public List<ToolDef> tools(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        List<ToolDef> tools = new ArrayList<>();
        tools.add(tool("list_services", ToolClass.READ, "List Ranger services",
                "{\"type\":\"object\",\"properties\":{}}",
                ctx -> client.get("/service/public/v2/api/service")));
        tools.add(tool("list_policies", ToolClass.READ, "List Ranger policies for a service",
                "{\"type\":\"object\",\"properties\":{\"service\":{\"type\":\"string\"}},\"required\":[\"service\"]}",
                ctx -> client.get("/service/public/v2/api/service/" + Inputs.requireId(arg(ctx, "service")) + "/policy")));
        tools.add(tool("get_policy", ToolClass.READ, "Get a Ranger policy",
                "{\"type\":\"object\",\"properties\":{\"policyId\":{\"type\":\"string\"}},\"required\":[\"policyId\"]}",
                ctx -> client.get("/service/public/v2/api/policy/" + Inputs.requireId(arg(ctx, "policyId")))));
        tools.add(tool("delete_policy", ToolClass.DESTRUCTIVE, "Delete a Ranger policy",
                "{\"type\":\"object\",\"properties\":{\"policyId\":{\"type\":\"string\"},\"approvalToken\":{\"type\":\"string\"}},\"required\":[\"policyId\",\"approvalToken\"]}",
                ctx -> client.delete("/service/public/v2/api/policy/" + Inputs.requireId(arg(ctx, "policyId")))));
        return tools;
    }

    @Override
    public List<ResourceDef> resources(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        return List.of(new ResourceDef(
                "ranger://status",
                "ranger-status",
                "application/json",
                ctx -> client.get("/service/public/v2/api/service"),
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
        return cfg.adapterProperty("ranger.url",
                cfg.adapterProperty("RANGER_URL", "http://localhost:6080"));
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
