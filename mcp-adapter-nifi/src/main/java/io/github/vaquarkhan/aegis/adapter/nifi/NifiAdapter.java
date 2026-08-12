package io.github.vaquarkhan.aegis.adapter.nifi;

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
 * Apache NiFi adapter. Talks to the configured HTTP/REST surface; failures propagate for the breaker.
 *
 * @author Viquar Khan
 */
public final class NifiAdapter implements EngineAdapter {

    private static final McpJsonMapper JSON = defaultJsonMapper();

    private static McpJsonMapper defaultJsonMapper() {
        return new JacksonMcpJsonMapperSupplier().get();
    }

    @Override
    public String engineId() {
        return "nifi";
    }

    @Override
    public String taxonomyClass() {
        return "dataflow";
    }

    @Override
    public List<ToolDef> tools(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        List<ToolDef> tools = new ArrayList<>();
        tools.add(tool("get_about", ToolClass.READ, "NiFi about / version",
                new JsonSchema("object", Map.of(), List.of(), null, null, null),
                ctx -> client.get("/flow/about")));
        tools.add(tool("list_process_groups", ToolClass.READ, "List root process groups",
                new JsonSchema("object", Map.of(), List.of(), null, null, null),
                ctx -> client.get("/flow/process-groups/root")));
        tools.add(tool("get_process_group", ToolClass.READ, "Get a process group",
                new JsonSchema("object", Map.of("groupId", Map.of("type", "string")), List.of("groupId"), null, null, null),
                ctx -> client.get("/process-groups/" + Inputs.requireId(arg(ctx, "groupId")))));
        tools.add(tool("start_process_group", ToolClass.MUTATE, "Start a process group",
                new JsonSchema("object", Map.of("groupId", Map.of("type", "string"), "approvalToken", Map.of("type", "string")), List.of("groupId", "approvalToken"), null, null, null),
                ctx -> client.put("/flow/process-groups/" + Inputs.requireId(arg(ctx, "groupId")), "{\"id\":\"" + Inputs.jsonEscape(Inputs.requireId(arg(ctx, "groupId"))) + "\",\"state\":\"RUNNING\"}")));
        tools.add(tool("stop_process_group", ToolClass.DESTRUCTIVE, "Stop a process group",
                new JsonSchema("object", Map.of("groupId", Map.of("type", "string"), "approvalToken", Map.of("type", "string")), List.of("groupId", "approvalToken"), null, null, null),
                ctx -> client.put("/flow/process-groups/" + Inputs.requireId(arg(ctx, "groupId")), "{\"id\":\"" + Inputs.jsonEscape(Inputs.requireId(arg(ctx, "groupId"))) + "\",\"state\":\"STOPPED\"}")));
        return tools;
    }

    @Override
    public List<ResourceDef> resources(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        return List.of(new ResourceDef(
                "nifi://status",
                "nifi-status",
                "application/json",
                ctx -> client.get("/flow/about"),
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
        return cfg.adapterProperty("nifi.url",
                cfg.adapterProperty("NIFI_API_URL", "http://localhost:8080/nifi-api"));
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
