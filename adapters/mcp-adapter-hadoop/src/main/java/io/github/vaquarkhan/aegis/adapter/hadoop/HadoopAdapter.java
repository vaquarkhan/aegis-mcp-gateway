package io.github.vaquarkhan.aegis.adapter.hadoop;

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
 * Apache Hadoop HDFS adapter. Talks to the configured HTTP/REST surface; failures propagate for the breaker.
 *
 * @author Viquar Khan
 */
public final class HadoopAdapter implements EngineAdapter {

    @Override
    public String engineId() {
        return "hadoop";
    }

    @Override
    public String taxonomyClass() {
        return "storage";
    }

    @Override
    public List<ToolDef> tools(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        List<ToolDef> tools = new ArrayList<>();
        tools.add(tool("list_status", ToolClass.READ, "WebHDFS LISTSTATUS",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}",
                ctx -> client.get("/webhdfs/v1" + (arg(ctx, "path") == null || arg(ctx, "path").isBlank() ? "/" : Inputs.requirePath(arg(ctx, "path"))) + "?op=LISTSTATUS")));
        tools.add(tool("get_file_status", ToolClass.READ, "WebHDFS GETFILESTATUS",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}",
                ctx -> client.get("/webhdfs/v1" + Inputs.requirePath(arg(ctx, "path")) + "?op=GETFILESTATUS")));
        tools.add(tool("mkdirs", ToolClass.MUTATE, "WebHDFS MKDIRS",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"approvalToken\":{\"type\":\"string\"}},\"required\":[\"path\",\"approvalToken\"]}",
                ctx -> client.put("/webhdfs/v1" + Inputs.requirePath(arg(ctx, "path")) + "?op=MKDIRS", "{}")));
        tools.add(tool("delete_path", ToolClass.DESTRUCTIVE, "WebHDFS DELETE",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"approvalToken\":{\"type\":\"string\"}},\"required\":[\"path\",\"approvalToken\"]}",
                ctx -> client.delete("/webhdfs/v1" + Inputs.requirePath(arg(ctx, "path")) + "?op=DELETE&recursive=false")));
        return tools;
    }

    @Override
    public List<ResourceDef> resources(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        return List.of(new ResourceDef(
                "hadoop://status",
                "hadoop-status",
                "application/json",
                ctx -> client.get("/webhdfs/v1/?op=LISTSTATUS"),
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
        return cfg.adapterProperty("hadoop.url",
                cfg.adapterProperty("HDFS_WEBHDFS_URL", "http://localhost:9870"));
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
