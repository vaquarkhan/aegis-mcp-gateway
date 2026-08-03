package io.github.vaquarkhan.aegis.adapter.druid;

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
 * Apache Druid adapter. Talks to the configured HTTP/REST surface; failures propagate for the breaker.
 *
 * @author Viquar Khan
 */
public final class DruidAdapter implements EngineAdapter {

    @Override
    public String engineId() {
        return "druid";
    }

    @Override
    public String taxonomyClass() {
        return "olap";
    }

    @Override
    public List<ToolDef> tools(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        List<ToolDef> tools = new ArrayList<>();
        tools.add(tool("list_datasources", ToolClass.READ, "List Druid datasources",
                "{\"type\":\"object\",\"properties\":{}}",
                ctx -> client.get("/druid/coordinator/v1/datasources")));
        tools.add(tool("get_datasource", ToolClass.READ, "Get Druid datasource metadata",
                "{\"type\":\"object\",\"properties\":{\"datasource\":{\"type\":\"string\"}},\"required\":[\"datasource\"]}",
                ctx -> client.get("/druid/coordinator/v1/datasources/" + Inputs.requireId(arg(ctx, "datasource")))));
        tools.add(tool("run_sql_readonly", ToolClass.READ, "Run Druid SQL (read facade)",
                "{\"type\":\"object\",\"properties\":{\"sql\":{\"type\":\"string\"}},\"required\":[\"sql\"]}",
                ctx -> client.post("/druid/v2/sql", "{\"query\":\"" + Inputs.jsonEscape(Inputs.requireSql(arg(ctx, "sql"), cfg.maxSqlChars())) + "\"}")));
        tools.add(tool("disable_datasource", ToolClass.DESTRUCTIVE, "Disable a Druid datasource",
                "{\"type\":\"object\",\"properties\":{\"datasource\":{\"type\":\"string\"},\"approvalToken\":{\"type\":\"string\"}},\"required\":[\"datasource\",\"approvalToken\"]}",
                ctx -> client.delete("/druid/coordinator/v1/datasources/" + Inputs.requireId(arg(ctx, "datasource")))));
        return tools;
    }

    @Override
    public List<ResourceDef> resources(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        return List.of(new ResourceDef(
                "druid://status",
                "druid-status",
                "application/json",
                ctx -> client.get("/druid/coordinator/v1/datasources"),
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
        return cfg.adapterProperty("druid.url",
                cfg.adapterProperty("DRUID_ROUTER_URL", "http://localhost:8888"));
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
