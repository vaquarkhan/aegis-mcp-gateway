package io.github.vaquarkhan.aegis.adapter.hbase;

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
 * Apache HBase adapter. Talks to the configured HTTP/REST surface; failures propagate for the breaker.
 *
 * @author Viquar Khan
 */
public final class HbaseAdapter implements EngineAdapter {

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
        tools.add(tool("cluster_version", ToolClass.READ, "HBase REST cluster version",
                "{\"type\":\"object\",\"properties\":{}}",
                ctx -> client.get("/version/cluster")));
        tools.add(tool("list_tables", ToolClass.READ, "List HBase tables",
                "{\"type\":\"object\",\"properties\":{}}",
                ctx -> client.get("/")));
        tools.add(tool("get_table_schema", ToolClass.READ, "Get HBase table schema",
                "{\"type\":\"object\",\"properties\":{\"table\":{\"type\":\"string\"}},\"required\":[\"table\"]}",
                ctx -> client.get("/" + Inputs.requireTable(arg(ctx, "table")) + "/schema")));
        tools.add(tool("delete_table", ToolClass.DESTRUCTIVE, "Delete an HBase table",
                "{\"type\":\"object\",\"properties\":{\"table\":{\"type\":\"string\"},\"approvalToken\":{\"type\":\"string\"}},\"required\":[\"table\",\"approvalToken\"]}",
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
