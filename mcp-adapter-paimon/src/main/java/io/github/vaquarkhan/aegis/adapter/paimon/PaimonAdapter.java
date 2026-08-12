package io.github.vaquarkhan.aegis.adapter.paimon;

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
 * Apache Paimon adapter. Talks to the configured HTTP/REST surface; failures propagate for the breaker.
 *
 * @author Viquar Khan
 */
public final class PaimonAdapter implements EngineAdapter {

    private static final McpJsonMapper JSON = defaultJsonMapper();

    private static McpJsonMapper defaultJsonMapper() {
        return new JacksonMcpJsonMapperSupplier().get();
    }

    @Override
    public String engineId() {
        return "paimon";
    }

    @Override
    public String taxonomyClass() {
        return "lakehouse";
    }

    @Override
    public List<ToolDef> tools(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        List<ToolDef> tools = new ArrayList<>();
        tools.add(tool("list_databases", ToolClass.READ, "List Paimon databases",
                new JsonSchema("object", Map.of(), List.of(), null, null, null),
                ctx -> client.get("/v1/databases")));
        tools.add(tool("list_tables", ToolClass.READ, "List Paimon tables",
                new JsonSchema("object", Map.of("database", Map.of("type", "string")), List.of("database"), null, null, null),
                ctx -> client.get("/v1/databases/" + Inputs.requireNamespace(arg(ctx, "database")) + "/tables")));
        tools.add(tool("get_table", ToolClass.READ, "Get Paimon table",
                new JsonSchema("object", Map.of("database", Map.of("type", "string"), "table", Map.of("type", "string")), List.of("database", "table"), null, null, null),
                ctx -> client.get("/v1/databases/" + Inputs.requireNamespace(arg(ctx, "database")) + "/tables/" + Inputs.requireTable(arg(ctx, "table")))));
        tools.add(tool("drop_table", ToolClass.DESTRUCTIVE, "Drop a Paimon table",
                new JsonSchema("object", Map.of("database", Map.of("type", "string"), "table", Map.of("type", "string"), "approvalToken", Map.of("type", "string")), List.of("database", "table", "approvalToken"), null, null, null),
                ctx -> client.delete("/v1/databases/" + Inputs.requireNamespace(arg(ctx, "database")) + "/tables/" + Inputs.requireTable(arg(ctx, "table")))));
        return tools;
    }

    @Override
    public List<ResourceDef> resources(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        return List.of(new ResourceDef(
                "paimon://status",
                "paimon-status",
                "application/json",
                ctx -> client.get("/v1/databases"),
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
        return cfg.adapterProperty("paimon.url",
                cfg.adapterProperty("PAIMON_REST_URL", "http://localhost:8082"));
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
