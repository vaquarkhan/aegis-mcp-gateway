package io.github.vaquarkhan.aegis.adapter.couchdb;

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
 * Apache CouchDB adapter. Talks to the configured HTTP/REST surface; failures propagate for the breaker.
 *
 * @author Viquar Khan
 */
public final class CouchdbAdapter implements EngineAdapter {

    private static final McpJsonMapper JSON = defaultJsonMapper();

    private static McpJsonMapper defaultJsonMapper() {
        return new JacksonMcpJsonMapperSupplier().get();
    }

    @Override
    public String engineId() {
        return "couchdb";
    }

    @Override
    public String taxonomyClass() {
        return "datastore";
    }

    @Override
    public List<ToolDef> tools(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        List<ToolDef> tools = new ArrayList<>();
        tools.add(tool("list_dbs", ToolClass.READ, "List CouchDB databases",
                new JsonSchema("object", Map.of(), null, null, null, null),
                ctx -> client.get("/_all_dbs")));
        tools.add(tool("get_db", ToolClass.READ, "Get CouchDB database info",
                new JsonSchema("object", Map.of("db", Map.of("type", "string")), List.of("db"), null, null, null),
                ctx -> client.get("/" + Inputs.requireId(arg(ctx, "db")))));
        tools.add(tool("create_db", ToolClass.MUTATE, "Create a CouchDB database",
                new JsonSchema("object", Map.of("db", Map.of("type", "string"), "approvalToken", Map.of("type", "string")), List.of("db", "approvalToken"), null, null, null),
                ctx -> client.put("/" + Inputs.requireId(arg(ctx, "db")), "{}")));
        tools.add(tool("delete_db", ToolClass.DESTRUCTIVE, "Delete a CouchDB database",
                new JsonSchema("object", Map.of("db", Map.of("type", "string"), "approvalToken", Map.of("type", "string")), List.of("db", "approvalToken"), null, null, null),
                ctx -> client.delete("/" + Inputs.requireId(arg(ctx, "db")))));
        return tools;
    }

    @Override
    public List<ResourceDef> resources(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        return List.of(new ResourceDef(
                "couchdb://status",
                "couchdb-status",
                "application/json",
                ctx -> client.get("/_all_dbs"),
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
        return cfg.adapterProperty("couchdb.url",
                cfg.adapterProperty("COUCHDB_URL", "http://localhost:5984"));
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
