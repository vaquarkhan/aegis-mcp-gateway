package io.github.vaquarkhan.aegis.adapter.ozone;

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
 * Apache Ozone adapter. Talks to the configured HTTP/REST surface; failures propagate for the breaker.
 *
 * @author Viquar Khan
 */
public final class OzoneAdapter implements EngineAdapter {

    @Override
    public String engineId() {
        return "ozone";
    }

    @Override
    public String taxonomyClass() {
        return "objectstore";
    }

    @Override
    public List<ToolDef> tools(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        List<ToolDef> tools = new ArrayList<>();
        tools.add(tool("get_service", ToolClass.READ, "Ozone OM HTTP root",
                "{\"type\":\"object\",\"properties\":{}}",
                ctx -> client.get("/")));
        tools.add(tool("list_volumes", ToolClass.READ, "List Ozone volumes via HTTP facade",
                "{\"type\":\"object\",\"properties\":{}}",
                ctx -> client.get("/v1/volumes")));
        tools.add(tool("list_buckets", ToolClass.READ, "List Ozone buckets",
                "{\"type\":\"object\",\"properties\":{\"volume\":{\"type\":\"string\"}},\"required\":[\"volume\"]}",
                ctx -> client.get("/v1/volumes/" + Inputs.requireId(arg(ctx, "volume")) + "/buckets")));
        tools.add(tool("delete_bucket", ToolClass.DESTRUCTIVE, "Delete an Ozone bucket",
                "{\"type\":\"object\",\"properties\":{\"volume\":{\"type\":\"string\"},\"bucket\":{\"type\":\"string\"},\"approvalToken\":{\"type\":\"string\"}},\"required\":[\"volume\",\"bucket\",\"approvalToken\"]}",
                ctx -> client.delete("/v1/volumes/" + Inputs.requireId(arg(ctx, "volume")) + "/buckets/" + Inputs.requireId(arg(ctx, "bucket")))));
        return tools;
    }

    @Override
    public List<ResourceDef> resources(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        return List.of(new ResourceDef(
                "ozone://status",
                "ozone-status",
                "application/json",
                ctx -> client.get("/"),
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
        return cfg.adapterProperty("ozone.url",
                cfg.adapterProperty("OZONE_OM_HTTP_URL", "http://localhost:9874"));
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
