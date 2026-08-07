package io.github.vaquarkhan.aegis.adapter.solr;

import io.github.vaquarkhan.aegis.core.config.GatewayConfig;
import io.github.vaquarkhan.aegis.core.spi.CallContext;
import io.github.vaquarkhan.aegis.core.spi.EngineAdapter;
import io.github.vaquarkhan.aegis.core.spi.ResourceDef;
import io.github.vaquarkhan.aegis.core.spi.ToolClass;
import io.github.vaquarkhan.aegis.core.spi.ToolDef;
import io.github.vaquarkhan.aegis.core.util.HttpJsonClient;
import io.github.vaquarkhan.aegis.core.util.Inputs;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import tools.jackson.databind.json.JsonMapper;

/**
 * Apache Solr adapter. Talks to the configured HTTP/REST surface; failures propagate for the breaker.
 *
 * @author Viquar Khan
 */
public final class SolrAdapter implements EngineAdapter {

    private static final McpJsonMapper JSON = new JacksonMcpJsonMapper(JsonMapper.builder().build());

    @Override
    public String engineId() {
        return "solr";
    }

    @Override
    public String taxonomyClass() {
        return "search";
    }

    @Override
    public List<ToolDef> tools(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        List<ToolDef> tools = new ArrayList<>();
        tools.add(tool("system_info", ToolClass.READ, "Solr system info",
                new JsonSchema("object", Map.of(), null, null, null, null),
                ctx -> client.get("/admin/info/system?wt=json")));
        tools.add(tool("list_collections", ToolClass.READ, "List Solr collections",
                new JsonSchema("object", Map.of(), null, null, null, null),
                ctx -> client.get("/admin/collections?action=LIST&wt=json")));
        tools.add(tool("query", ToolClass.READ, "Query a Solr collection",
                new JsonSchema("object", Map.of(
                        "collection", Map.of("type", "string"),
                        "q", Map.of("type", "string")),
                        List.of("collection", "q"), null, null, null),
                ctx -> client.get("/" + Inputs.requireId(arg(ctx, "collection")) + "/select?q=" + Inputs.requirePath(arg(ctx, "q") == null || arg(ctx, "q").isBlank() ? "*:*" : arg(ctx, "q")) + "&wt=json")));
        tools.add(tool("delete_collection", ToolClass.DESTRUCTIVE, "Delete a Solr collection",
                new JsonSchema("object", Map.of(
                        "collection", Map.of("type", "string"),
                        "approvalToken", Map.of("type", "string")),
                        List.of("collection", "approvalToken"), null, null, null),
                ctx -> client.get("/admin/collections?action=DELETE&name=" + Inputs.requireId(arg(ctx, "collection")) + "&wt=json")));
        return tools;
    }

    @Override
    public List<ResourceDef> resources(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        return List.of(new ResourceDef(
                "solr://status",
                "solr-status",
                "application/json",
                ctx -> client.get("/admin/info/system"),
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
        return cfg.adapterProperty("solr.url",
                cfg.adapterProperty("SOLR_URL", "http://localhost:8983/solr"));
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
