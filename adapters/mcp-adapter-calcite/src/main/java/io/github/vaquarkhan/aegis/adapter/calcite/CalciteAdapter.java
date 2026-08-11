package io.github.vaquarkhan.aegis.adapter.calcite;

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
 * Apache Calcite adapter. Talks to the configured HTTP/REST surface; failures propagate for the breaker.
 *
 * @author Viquar Khan
 */
public final class CalciteAdapter implements EngineAdapter {

    @Override
    public String engineId() {
        return "calcite";
    }

    @Override
    public String taxonomyClass() {
        return "query";
    }

    @Override
    public List<ToolDef> tools(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        List<ToolDef> tools = new ArrayList<>();
        tools.add(tool("get_status", ToolClass.READ, "Calcite HTTP status",
                "{\"type\":\"object\",\"properties\":{}}",
                ctx -> client.get("/")));
        tools.add(tool("explain_sql", ToolClass.READ, "Explain a SQL plan via HTTP facade",
                "{\"type\":\"object\",\"properties\":{\"sql\":{\"type\":\"string\"}},\"required\":[\"sql\"]}",
                ctx -> client.post("/explain", "{\"sql\":\"" + Inputs.jsonEscape(Inputs.requireSql(arg(ctx, "sql"), cfg.maxSqlChars())) + "\"}")));
        tools.add(tool("run_sql_readonly", ToolClass.READ, "Run read-only SQL via Calcite HTTP facade",
                "{\"type\":\"object\",\"properties\":{\"sql\":{\"type\":\"string\"}},\"required\":[\"sql\"]}",
                ctx -> client.post("/sql", "{\"sql\":\"" + Inputs.jsonEscape(Inputs.requireSql(arg(ctx, "sql"), cfg.maxSqlChars())) + "\"}")));
        return tools;
    }

    @Override
    public List<ResourceDef> resources(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        return List.of(new ResourceDef(
                "calcite://status",
                "calcite-status",
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
        return cfg.adapterProperty("calcite.url",
                cfg.adapterProperty("CALCITE_HTTP_URL", "http://localhost:8080"));
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
