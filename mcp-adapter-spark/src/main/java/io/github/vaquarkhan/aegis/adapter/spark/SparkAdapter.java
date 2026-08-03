package io.github.vaquarkhan.aegis.adapter.spark;

import io.github.vaquarkhan.aegis.core.config.GatewayConfig;
import io.github.vaquarkhan.aegis.core.governance.SqlReadonlyGuard;
import io.github.vaquarkhan.aegis.core.spi.CallContext;
import io.github.vaquarkhan.aegis.core.spi.EngineAdapter;
import io.github.vaquarkhan.aegis.core.spi.ReadOnlyGuard;
import io.github.vaquarkhan.aegis.core.spi.ResourceDef;
import io.github.vaquarkhan.aegis.core.spi.ToolClass;
import io.github.vaquarkhan.aegis.core.spi.ToolDef;
import io.github.vaquarkhan.aegis.core.util.Inputs;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Spark batch adapter. History Server reads, Livy batch submit and Livy batch kill are live over
 * HTTP; read-only SQL needs an operator supplied HTTP SQL endpoint because neither Spark Connect nor
 * a Thrift client is bundled with the gateway.
 *
 * <p>Backends propagate HTTP failures instead of substituting a placeholder body, so the circuit
 * breaker sees a real failure and a caller is never told a dead History Server or Livy looks healthy.
 *
 * @author Viquar Khan
 */
public final class SparkAdapter implements EngineAdapter {

    private static final int MAX_FILE_REF_CHARS = 1024;

    private final SqlReadonlyGuard sqlGuard = new SqlReadonlyGuard();

    @Override
    public String engineId() {
        return "spark";
    }

    @Override
    public String taxonomyClass() {
        return "batch";
    }

    @Override
    public List<ToolDef> tools(GatewayConfig cfg) {
        SparkHttpClient history = new SparkHttpClient(historyUrl(cfg));
        SparkHttpClient livy = new SparkHttpClient(livyUrl(cfg));
        String sqlUrl = sqlUrl(cfg);
        SparkHttpClient sql = sqlUrl == null ? null : new SparkHttpClient(sqlUrl);
        List<ToolDef> tools = new ArrayList<>();
        tools.add(tool("list_applications", ToolClass.READ, "List Spark History applications",
                "{\"type\":\"object\",\"properties\":{}}",
                ctx -> history.get("/api/v1/applications")));
        tools.add(tool("get_application", ToolClass.READ, "Get Spark application details from History Server",
                "{\"type\":\"object\",\"properties\":{\"appId\":{\"type\":\"string\"}},\"required\":[\"appId\"]}",
                ctx -> history.get("/api/v1/applications/" + Inputs.requireId(arg(ctx, "appId")))));
        tools.add(tool("run_sql_readonly", ToolClass.READ, "Guarded read-only SQL via HTTP SQL gateway",
                "{\"type\":\"object\",\"properties\":{\"sql\":{\"type\":\"string\"}},\"required\":[\"sql\"]}",
                ctx -> {
                    String sqlText = Inputs.requireSql(arg(ctx, "sql"), cfg.maxSqlChars());
                    if (!sqlGuard.isReadOnly(sqlText)) {
                        throw new Inputs.InvalidInput("SQL_NOT_READONLY");
                    }
                    if (sql == null) {
                        throw new Inputs.InvalidInput(
                                "SQL_BACKEND_NOT_CONFIGURED: set spark.sql.http.url or SPARK_SQL_HTTP_URL to a "
                                        + "read-only SQL endpoint; Spark Connect and Thrift clients are not bundled");
                    }
                    return sql.post("", "{\"sql\":\"" + Inputs.jsonEscape(sqlText) + "\"}");
                }));
        tools.add(tool("submit_batch", ToolClass.DESTRUCTIVE, "Submit a Livy batch job",
                "{\"type\":\"object\",\"properties\":{\"file\":{\"type\":\"string\"},\"className\":{\"type\":\"string\"},\"approvalToken\":{\"type\":\"string\"}},\"required\":[\"file\",\"approvalToken\"]}",
                ctx -> livy.post("/batches", submitBody(ctx))));
        tools.add(tool("kill_application", ToolClass.DESTRUCTIVE, "Kill a Livy batch application",
                "{\"type\":\"object\",\"properties\":{\"appId\":{\"type\":\"string\"},\"approvalToken\":{\"type\":\"string\"}},\"required\":[\"appId\",\"approvalToken\"]}",
                ctx -> livy.delete("/batches/" + Inputs.requireId(arg(ctx, "appId")))));
        return tools;
    }

    @Override
    public List<ResourceDef> resources(GatewayConfig cfg) {
        SparkHttpClient history = new SparkHttpClient(historyUrl(cfg));
        return List.of(new ResourceDef(
                "spark://history",
                "spark-history",
                "application/json",
                ctx -> history.get("/api/v1/applications"),
                true));
    }

    @Override
    public Optional<ReadOnlyGuard> readOnlyGuard() {
        return Optional.of(sqlGuard);
    }

    @Override
    public Set<String> egressAllowHosts(GatewayConfig cfg) {
        Set<String> hosts = new LinkedHashSet<>();
        addHost(hosts, historyUrl(cfg));
        addHost(hosts, livyUrl(cfg));
        addHost(hosts, sqlUrl(cfg));
        return Set.copyOf(hosts);
    }

    static String historyUrl(GatewayConfig cfg) {
        return cfg.adapterProperty("spark.history.url",
                cfg.adapterProperty("SPARK_HISTORY_URL", "http://localhost:18080"));
    }

    static String livyUrl(GatewayConfig cfg) {
        return cfg.adapterProperty("spark.livy.url",
                cfg.adapterProperty("SPARK_LIVY_URL", "http://localhost:8998"));
    }

    /** Null when no SQL endpoint is configured, which makes read-only SQL a caller error. */
    static String sqlUrl(GatewayConfig cfg) {
        String url = cfg.adapterProperty("spark.sql.http.url", cfg.adapterProperty("SPARK_SQL_HTTP_URL", null));
        return url == null || url.isBlank() ? null : url;
    }

    /** Livy batch request body. Only the fields the caller supplied are sent. */
    private static String submitBody(CallContext ctx) {
        String file = requireFileRef(arg(ctx, "file"));
        StringBuilder sb = new StringBuilder("{\"file\":\"").append(Inputs.jsonEscape(file)).append('"');
        String className = arg(ctx, "className");
        if (className != null && !className.isBlank()) {
            sb.append(",\"className\":\"").append(Inputs.jsonEscape(Inputs.requireId(className))).append('"');
        }
        return sb.append('}').toString();
    }

    /**
     * Artifact reference for a Livy batch, for example {@code s3a://bucket/jobs/etl.jar}. Bounded and
     * free of control characters so nothing unbounded reaches Livy.
     */
    private static String requireFileRef(String file) {
        if (file == null || file.isBlank()) {
            throw new Inputs.InvalidInput("file required");
        }
        if (file.length() > MAX_FILE_REF_CHARS) {
            throw new Inputs.InvalidInput("file exceeds max length " + MAX_FILE_REF_CHARS);
        }
        for (int i = 0; i < file.length(); i++) {
            if (file.charAt(i) < 0x20) {
                throw new Inputs.InvalidInput("file contains control characters");
            }
        }
        return file.trim();
    }

    private static void addHost(Set<String> hosts, String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            String host = URI.create(url).getHost();
            if (host != null && !host.isBlank()) {
                hosts.add(host);
            }
        } catch (IllegalArgumentException e) {
            // an unparsable endpoint contributes no allowed host, so egress stays closed
        }
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
