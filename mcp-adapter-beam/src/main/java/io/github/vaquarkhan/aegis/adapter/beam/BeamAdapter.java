package io.github.vaquarkhan.aegis.adapter.beam;

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
 * Apache Beam adapter. Talks to the configured HTTP/REST surface; failures propagate for the breaker.
 *
 * @author Viquar Khan
 */
public final class BeamAdapter implements EngineAdapter {

    private static final McpJsonMapper JSON = defaultJsonMapper();

    private static McpJsonMapper defaultJsonMapper() {
        return new JacksonMcpJsonMapperSupplier().get();
    }

    @Override
    public String engineId() {
        return "beam";
    }

    @Override
    public String taxonomyClass() {
        return "pipeline";
    }

    @Override
    public List<ToolDef> tools(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        List<ToolDef> tools = new ArrayList<>();
        tools.add(tool("list_jobs", ToolClass.READ, "List Beam Job API jobs",
                new JsonSchema("object", Map.of(), null, null, null, null),
                ctx -> client.get("/v1/jobs")));
        tools.add(tool("get_job", ToolClass.READ, "Get a Beam job",
                new JsonSchema("object", Map.of("jobId", Map.of("type", "string")), List.of("jobId"), null, null, null),
                ctx -> client.get("/v1/jobs/" + Inputs.requireId(arg(ctx, "jobId")))));
        tools.add(tool("get_job_metrics", ToolClass.READ, "Get Beam job metrics",
                new JsonSchema("object", Map.of("jobId", Map.of("type", "string")), List.of("jobId"), null, null, null),
                ctx -> client.get("/v1/jobs/" + Inputs.requireId(arg(ctx, "jobId")) + "/metrics")));
        tools.add(tool("cancel_job", ToolClass.DESTRUCTIVE, "Cancel a Beam job",
                new JsonSchema("object", Map.of("jobId", Map.of("type", "string"), "approvalToken", Map.of("type", "string")), List.of("jobId", "approvalToken"), null, null, null),
                ctx -> client.post("/v1/jobs/" + Inputs.requireId(arg(ctx, "jobId")) + ":cancel", "{}")));
        return tools;
    }

    @Override
    public List<ResourceDef> resources(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        return List.of(new ResourceDef(
                "beam://status",
                "beam-status",
                "application/json",
                ctx -> client.get("/v1/jobs"),
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
        return cfg.adapterProperty("beam.url",
                cfg.adapterProperty("BEAM_JOB_SERVER_URL", "http://localhost:8099"));
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
