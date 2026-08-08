package io.github.vaquarkhan.aegis.adapter.arrow;

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
 * Apache Arrow Flight adapter. Talks to the configured HTTP/REST surface; failures propagate for the breaker.
 *
 * @author Viquar Khan
 */
public final class ArrowAdapter implements EngineAdapter {

    private static final McpJsonMapper JSON_MAPPER = defaultJsonMapper();

    private static McpJsonMapper defaultJsonMapper() {
        return new JacksonMcpJsonMapperSupplier().get();
    }

    @Override
    public String engineId() {
        return "arrow";
    }

    @Override
    public String taxonomyClass() {
        return "analytics";
    }

    @Override
    public List<ToolDef> tools(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        List<ToolDef> tools = new ArrayList<>();
        tools.add(tool("list_flights", ToolClass.READ, "List Arrow Flight datasets via HTTP facade",
                new JsonSchema("object", Map.of(), null, null, null, null),
                ctx -> client.get("/flights")));
        tools.add(tool("get_flight", ToolClass.READ, "Get Flight info",
                new JsonSchema("object", Map.of("ticket", Map.of("type", "string")), List.of("ticket"), null, null, null),
                ctx -> client.get("/flights/" + Inputs.requireId(arg(ctx, "ticket")))));
        tools.add(tool("do_action", ToolClass.MUTATE, "Invoke a Flight action",
                new JsonSchema("object", Map.of("action", Map.of("type", "string"), "approvalToken", Map.of("type", "string")), List.of("action", "approvalToken"), null, null, null),
                ctx -> client.post("/actions/" + Inputs.requireId(arg(ctx, "action")), "{}")));
        return tools;
    }

    @Override
    public List<ResourceDef> resources(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        return List.of(new ResourceDef(
                "arrow://status",
                "arrow-status",
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
        return cfg.adapterProperty("arrow.url",
                cfg.adapterProperty("ARROW_FLIGHT_HTTP_URL", "http://localhost:8815"));
    }

    private static ToolDef tool(String name, ToolClass cls, String desc, JsonSchema schema,
                                Function<CallContext, String> backend) {
        try {
            return new ToolDef(name, cls, desc, JSON_MAPPER.writeValueAsString(schema), backend);
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize JsonSchema for tool: " + name, e);
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
