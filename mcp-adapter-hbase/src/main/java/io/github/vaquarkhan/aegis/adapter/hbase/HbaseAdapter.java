package io.github.vaquarkhan.aegis.adapter.hbase;

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
 * Apache HBase adapter. Talks to the configured HTTP/REST surface; failures propagate for the breaker.
 *
 * @author Viquar Khan
 */
public final class HbaseAdapter implements EngineAdapter {

    private static final McpJsonMapper JSON = defaultJsonMapper();

    private static McpJsonMapper defaultJsonMapper() {
        return new JacksonMcpJsonMapperSupplier().get();
    }

    @Override
    public String engineId() {
        return "hbase";
    }

    @Override
    public String taxonomyClass() {
        return "datastore";
    }

    @Override
    public List<ToolDef> tools(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        List<ToolDef> tools = new ArrayList<>();
        JsonSchema empty = new JsonSchema("object", Map.of(), List.of(), null, null, null);
        JsonSchema tableRequired = new JsonSchema("object", Map.of("table", Map.of("type", "string")), List.of("table"), null, null, null);
        JsonSchema tableApprovalRequired = new JsonSchema("object", Map.of("table", Map.of("type", "string"), "approvalToken", Map.of("type", "string")), List.of("table", "approvalToken"), null, null, null);
        tools.add(tool("cluster_version", ToolClass.READ, "HBase REST cluster version",
                empty,
                ctx -> client.get("/version/cluster")));
        tools.add(tool("list_tables", ToolClass.READ, "List HBase tables",
                empty,
                ctx -> client.get("/")));
        tools.add(tool("get_table_schema", ToolClass.READ, "Get HBase table schema",
                tableRequired,
                ctx -> client.get("/" + Inputs.requireTable(arg(ctx, "table")) + "/schema")));
        tools.add(tool("delete_table", ToolClass.DESTRUCTIVE, "Delete an HBase table",
                tableApprovalRequired,
                ctx -> client.delete("/" + Inputs.requireTable(arg(ctx, "table")) + "/schema")));
        return tools;
    }

    @Override
    public List<ResourceDef> resources(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        return List.of(new ResourceDef(
                "hbase://status",
                "hbase-status",
                "application/json",
                ctx -> client.get("/version/cluster"),
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
        return cfg.adapterProperty("hbase.url",
                cfg.adapterProperty("HBASE_REST_URL", "http://localhost:8080"));
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
