package io.github.vaquarkhan.aegis.adapter.impala;

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
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import java.io.IOException;
import java.util.function.Function;

/**
 * Apache Impala adapter. Talks to the configured HTTP/REST surface; failures propagate for the breaker.
 *
 * @author Viquar Khan
 */
public final class ImpalaAdapter implements EngineAdapter {

    private static final McpJsonMapper JSON = defaultJsonMapper();

    private static McpJsonMapper defaultJsonMapper() {
        return new JacksonMcpJsonMapperSupplier().get();
    }

    @Override
    public String engineId() {
        return "impala";
    }

    @Override
    public String taxonomyClass() {
        return "query";
    }

    @Override
    public List<ToolDef> tools(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        List<ToolDef> tools = new ArrayList<>();
        tools.add(tool("get_metrics", ToolClass.READ, "Impala /metrics",
                new JsonSchema("object", Map.of(), List.of(), null, null, null),
                ctx -> client.get("/metrics")));
        tools.add(tool("list_queries", ToolClass.READ, "Impala queries page JSON facade",
                new JsonSchema("object", Map.of(), List.of(), null, null, null),
                ctx -> client.get("/queries?json")));
        tools.add(tool("cancel_query", ToolClass.DESTRUCTIVE, "Cancel an Impala query",
                new JsonSchema("object",
                        Map.of("queryId", Map.of("type", "string"),
                                "approvalToken", Map.of("type", "string")),
                        List.of("queryId", "approvalToken"), null, null, null),
                ctx -> client.post("/cancel_query?query_id=" + Inputs.requireId(arg(ctx, "queryId")), "{}")));
        return tools;
    }

    @Override
    public List<ResourceDef> resources(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        return List.of(new ResourceDef(
                "impala://status",
                "impala-status",
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
        return cfg.adapterProperty("impala.url",
                cfg.adapterProperty("IMPALA_HTTP_URL", "http://localhost:25000"));
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
