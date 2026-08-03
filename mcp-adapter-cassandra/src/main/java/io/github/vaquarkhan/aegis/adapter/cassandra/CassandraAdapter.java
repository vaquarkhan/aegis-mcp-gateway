package io.github.vaquarkhan.aegis.adapter.cassandra;

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
 * Apache Cassandra adapter. Talks to the configured HTTP/REST surface; failures propagate for the breaker.
 *
 * @author Viquar Khan
 */
public final class CassandraAdapter implements EngineAdapter {

    @Override
    public String engineId() {
        return "cassandra";
    }

    @Override
    public String taxonomyClass() {
        return "datastore";
    }

    @Override
    public List<ToolDef> tools(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        List<ToolDef> tools = new ArrayList<>();
        tools.add(tool("list_keyspaces", ToolClass.READ, "List keyspaces via Cassandra Sidecar",
                "{\"type\":\"object\",\"properties\":{}}",
                ctx -> client.get("/api/v1/cassandra/native/keyspaces")));
        tools.add(tool("list_tables", ToolClass.READ, "List tables in a keyspace",
                "{\"type\":\"object\",\"properties\":{\"keyspace\":{\"type\":\"string\"}},\"required\":[\"keyspace\"]}",
                ctx -> client.get("/api/v1/cassandra/native/keyspaces/" + Inputs.requireNamespace(arg(ctx, "keyspace")) + "/tables")));
        tools.add(tool("get_ring", ToolClass.READ, "Cassandra ring health via Sidecar",
                "{\"type\":\"object\",\"properties\":{}}",
                ctx -> client.get("/api/v1/cassandra/native/token-ranges")));
        tools.add(tool("drop_table", ToolClass.DESTRUCTIVE, "Drop a Cassandra table via Sidecar SQL facade",
                "{\"type\":\"object\",\"properties\":{\"keyspace\":{\"type\":\"string\"},\"table\":{\"type\":\"string\"},\"approvalToken\":{\"type\":\"string\"}},\"required\":[\"keyspace\",\"table\",\"approvalToken\"]}",
                ctx -> client.delete("/api/v1/cassandra/native/keyspaces/" + Inputs.requireNamespace(arg(ctx, "keyspace")) + "/tables/" + Inputs.requireTable(arg(ctx, "table")))));
        return tools;
    }

    @Override
    public List<ResourceDef> resources(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        return List.of(new ResourceDef(
                "cassandra://status",
                "cassandra-status",
                "application/json",
                ctx -> client.get("/api/v1/cassandra/native/keyspaces"),
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
        return cfg.adapterProperty("cassandra.url",
                cfg.adapterProperty("CASSANDRA_SIDECAR_URL", "http://localhost:9043"));
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
