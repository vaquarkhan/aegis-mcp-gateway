package io.github.vaquarkhan.aegis.adapter.hudi;

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
 * Apache Hudi adapter. Talks to the configured HTTP/REST surface; failures propagate for the breaker.
 *
 * @author Viquar Khan
 */
public final class HudiAdapter implements EngineAdapter {

    @Override
    public String engineId() {
        return "hudi";
    }

    @Override
    public String taxonomyClass() {
        return "lakehouse";
    }

    @Override
    public List<ToolDef> tools(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        List<ToolDef> tools = new ArrayList<>();
        tools.add(tool("list_tables", ToolClass.READ, "List Hudi tables via REST catalog facade",
                "{\"type\":\"object\",\"properties\":{}}",
                ctx -> client.get("/v1/tables")));
        tools.add(tool("get_table", ToolClass.READ, "Get Hudi table metadata",
                "{\"type\":\"object\",\"properties\":{\"table\":{\"type\":\"string\"}},\"required\":[\"table\"]}",
                ctx -> client.get("/v1/tables/" + Inputs.requireTable(arg(ctx, "table")))));
        tools.add(tool("run_clustering", ToolClass.MUTATE, "Trigger Hudi clustering",
                "{\"type\":\"object\",\"properties\":{\"table\":{\"type\":\"string\"},\"approvalToken\":{\"type\":\"string\"}},\"required\":[\"table\",\"approvalToken\"]}",
                ctx -> client.post("/v1/tables/" + Inputs.requireTable(arg(ctx, "table")) + "/clustering", "{}")));
        tools.add(tool("run_compaction", ToolClass.DESTRUCTIVE, "Trigger Hudi compaction",
                "{\"type\":\"object\",\"properties\":{\"table\":{\"type\":\"string\"},\"approvalToken\":{\"type\":\"string\"}},\"required\":[\"table\",\"approvalToken\"]}",
                ctx -> client.post("/v1/tables/" + Inputs.requireTable(arg(ctx, "table")) + "/compaction", "{}")));
        return tools;
    }

    @Override
    public List<ResourceDef> resources(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        return List.of(new ResourceDef(
                "hudi://status",
                "hudi-status",
                "application/json",
                ctx -> client.get("/v1/tables"),
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
        return cfg.adapterProperty("hudi.url",
                cfg.adapterProperty("HUDI_REST_URL", "http://localhost:8081"));
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
