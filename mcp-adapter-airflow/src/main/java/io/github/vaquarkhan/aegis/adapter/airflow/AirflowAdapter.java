package io.github.vaquarkhan.aegis.adapter.airflow;

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
 * Apache Airflow adapter. Talks to the configured HTTP/REST surface; failures propagate for the breaker.
 *
 * @author Viquar Khan
 */
public final class AirflowAdapter implements EngineAdapter {

    private static final McpJsonMapper JSON = defaultJsonMapper();

    private static McpJsonMapper defaultJsonMapper() {
        return new JacksonMcpJsonMapperSupplier().get();
    }

    @Override
    public String engineId() {
        return "airflow";
    }

    @Override
    public String taxonomyClass() {
        return "orchestration";
    }

    @Override
    public List<ToolDef> tools(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        List<ToolDef> tools = new ArrayList<>();
        tools.add(tool("get_health", ToolClass.READ, "Airflow health",
                new JsonSchema("object", Map.of(), null, null, null, null),
                ctx -> client.get("/health")));
        tools.add(tool("list_dags", ToolClass.READ, "List DAGs",
                new JsonSchema("object", Map.of(), null, null, null, null),
                ctx -> client.get("/dags")));
        tools.add(tool("get_dag", ToolClass.READ, "Get a DAG",
                new JsonSchema("object", Map.of("dagId", Map.of("type", "string")), List.of("dagId"), null, null, null),
                ctx -> client.get("/dags/" + Inputs.requireId(arg(ctx, "dagId")))));
        tools.add(tool("trigger_dag", ToolClass.MUTATE, "Trigger a DAG run",
                new JsonSchema("object", Map.of("dagId", Map.of("type", "string"), "approvalToken", Map.of("type", "string")), List.of("dagId", "approvalToken"), null, null, null),
                ctx -> client.post("/dags/" + Inputs.requireId(arg(ctx, "dagId")) + "/dagRuns", "{}")));
        tools.add(tool("delete_dag", ToolClass.DESTRUCTIVE, "Delete a DAG",
                new JsonSchema("object", Map.of("dagId", Map.of("type", "string"), "approvalToken", Map.of("type", "string")), List.of("dagId", "approvalToken"), null, null, null),
                ctx -> client.delete("/dags/" + Inputs.requireId(arg(ctx, "dagId")))));
        return tools;
    }

    @Override
    public List<ResourceDef> resources(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        return List.of(new ResourceDef(
                "airflow://status",
                "airflow-status",
                "application/json",
                ctx -> client.get("/health"),
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
        return cfg.adapterProperty("airflow.url",
                cfg.adapterProperty("AIRFLOW_API_URL", "http://localhost:8080/api/v1"));
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
