package io.github.vaquarkhan.aegis.adapter.iceberg;

import io.github.vaquarkhan.aegis.core.config.GatewayConfig;
import io.github.vaquarkhan.aegis.core.spi.CallContext;
import io.github.vaquarkhan.aegis.core.spi.EngineAdapter;
import io.github.vaquarkhan.aegis.core.spi.ResourceDef;
import io.github.vaquarkhan.aegis.core.spi.ToolClass;
import io.github.vaquarkhan.aegis.core.spi.ToolDef;
import io.github.vaquarkhan.aegis.core.util.Inputs;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Iceberg lakehouse adapter over the REST catalog. Namespace and table reads, create_namespace,
 * alter_table, drop_table and commit_transaction are live REST calls; snapshot and file maintenance
 * stays dry-run only because catalogs expose it as an engine-side procedure rather than over REST.
 *
 * <p>Destructive tools are VRP-gated and vended storage credentials are never returned. Catalog
 * failures propagate instead of substituting a placeholder body, so the circuit breaker sees a real
 * failure and a caller is never told a broken catalog looks healthy.
 *
 * @author Viquar Khan
 */
public final class IcebergAdapter implements EngineAdapter {

    private static final int MAX_PROPERTY_VALUE_CHARS = 1024;

    @Override
    public String engineId() {
        return "iceberg";
    }

    @Override
    public String taxonomyClass() {
        return "lakehouse";
    }

    @Override
    public List<ToolDef> tools(GatewayConfig cfg) {
        IcebergRestClient client = new IcebergRestClient(catalogUrl(cfg));
        List<ToolDef> tools = new ArrayList<>();
        tools.add(tool("list_namespaces", ToolClass.READ, "List Iceberg namespaces",
                "{\"type\":\"object\",\"properties\":{}}",
                ctx -> client.get("/v1/namespaces")));
        tools.add(tool("list_tables", ToolClass.READ, "List tables in a namespace",
                "{\"type\":\"object\",\"properties\":{\"namespace\":{\"type\":\"string\"}},\"required\":[\"namespace\"]}",
                ctx -> client.get("/v1/namespaces/" + Inputs.requireNamespace(arg(ctx, "namespace")) + "/tables")));
        String tableSchema = "{\"type\":\"object\",\"properties\":{\"namespace\":{\"type\":\"string\"},"
                + "\"table\":{\"type\":\"string\"}},\"required\":[\"namespace\",\"table\"]}";
        Function<CallContext, String> readTable = ctx -> {
            String ns = Inputs.requireNamespace(arg(ctx, "namespace"));
            String table = Inputs.requireTable(arg(ctx, "table"));
            return client.get("/v1/namespaces/" + ns + "/tables/" + table);
        };
        tools.add(tool("get_table", ToolClass.READ, "Get Iceberg table metadata", tableSchema, readTable));
        tools.add(tool("get_table_metadata", ToolClass.READ,
                "Get Iceberg table metadata (alias of get_table)", tableSchema, readTable));
        tools.add(tool("create_namespace", ToolClass.MUTATE, "Create an Iceberg namespace",
                "{\"type\":\"object\",\"properties\":{\"namespace\":{\"type\":\"string\"},\"approvalToken\":{\"type\":\"string\"}},\"required\":[\"namespace\",\"approvalToken\"]}",
                ctx -> client.post("/v1/namespaces",
                        createNamespaceBody(Inputs.requireNamespace(arg(ctx, "namespace"))))));
        tools.add(tool("alter_table", ToolClass.MUTATE, "Alter Iceberg table properties",
                "{\"type\":\"object\",\"properties\":{\"namespace\":{\"type\":\"string\"},\"table\":{\"type\":\"string\"},\"properties\":{\"type\":\"object\"},\"approvalToken\":{\"type\":\"string\"}},\"required\":[\"namespace\",\"table\",\"properties\",\"approvalToken\"]}",
                ctx -> {
                    String ns = Inputs.requireNamespace(arg(ctx, "namespace"));
                    String table = Inputs.requireTable(arg(ctx, "table"));
                    return client.post("/v1/namespaces/" + ns + "/tables/" + table, setPropertiesBody(ctx));
                }));
        tools.add(tool("drop_table", ToolClass.DESTRUCTIVE, "Drop an Iceberg table (VRP-gated)",
                "{\"type\":\"object\",\"properties\":{\"namespace\":{\"type\":\"string\"},\"table\":{\"type\":\"string\"},\"approvalToken\":{\"type\":\"string\"},\"dryRun\":{\"type\":\"boolean\"}},\"required\":[\"namespace\",\"table\",\"approvalToken\"]}",
                ctx -> {
                    String ns = Inputs.requireNamespace(arg(ctx, "namespace"));
                    String table = Inputs.requireTable(arg(ctx, "table"));
                    if (isDryRun(ctx)) {
                        return dryRunJson("drop_table", ns + "." + table);
                    }
                    return client.delete("/v1/namespaces/" + ns + "/tables/" + table);
                }));
        for (String op : List.of("expire_snapshots", "remove_orphan_files", "rewrite_data_files")) {
            tools.add(tool(op, ToolClass.DESTRUCTIVE, op + " (VRP-gated; engine procedure)",
                    "{\"type\":\"object\",\"properties\":{\"namespace\":{\"type\":\"string\"},\"table\":{\"type\":\"string\"},\"approvalToken\":{\"type\":\"string\"},\"dryRun\":{\"type\":\"boolean\"}},\"required\":[\"namespace\",\"table\",\"approvalToken\"]}",
                    ctx -> {
                        String ns = Inputs.requireNamespace(arg(ctx, "namespace"));
                        String table = Inputs.requireTable(arg(ctx, "table"));
                        if (isDryRun(ctx)) {
                            return dryRunJson(op, ns + "." + table);
                        }
                        throw new IllegalStateException(
                                op + " requires an engine-side procedure; use dry_run_maintenance first");
                    }));
        }
        tools.add(tool("commit_transaction", ToolClass.DESTRUCTIVE,
                "Commit an Iceberg transaction (VRP-gated)",
                "{\"type\":\"object\",\"properties\":{\"namespace\":{\"type\":\"string\"},\"table\":{\"type\":\"string\"},\"approvalToken\":{\"type\":\"string\"},\"dryRun\":{\"type\":\"boolean\"},\"commitJson\":{\"type\":\"string\"}},\"required\":[\"namespace\",\"table\",\"approvalToken\"]}",
                ctx -> {
                    String ns = Inputs.requireNamespace(arg(ctx, "namespace"));
                    String table = Inputs.requireTable(arg(ctx, "table"));
                    if (isDryRun(ctx)) {
                        return dryRunJson("commit_transaction", ns + "." + table);
                    }
                    String body = arg(ctx, "commitJson");
                    if (body == null || body.isBlank()) {
                        throw new Inputs.InvalidInput("commitJson required for commit_transaction");
                    }
                    return client.post("/v1/namespaces/" + ns + "/tables/" + table, body);
                }));
        tools.add(tool("dry_run_maintenance", ToolClass.READ, "Read-only dry-run companion for destructive maintenance",
                "{\"type\":\"object\",\"properties\":{\"operation\":{\"type\":\"string\"},\"namespace\":{\"type\":\"string\"},\"table\":{\"type\":\"string\"}},\"required\":[\"operation\",\"namespace\",\"table\"]}",
                ctx -> {
                    String op = Inputs.requireId(arg(ctx, "operation"));
                    String ns = Inputs.requireNamespace(arg(ctx, "namespace"));
                    String table = Inputs.requireTable(arg(ctx, "table"));
                    return dryRunJson(op, ns + "." + table);
                }));
        return tools;
    }

    @Override
    public List<ResourceDef> resources(GatewayConfig cfg) {
        IcebergRestClient client = new IcebergRestClient(catalogUrl(cfg));
        return List.of(new ResourceDef(
                "iceberg://catalog",
                "iceberg-catalog",
                "application/json",
                ctx -> client.get("/v1/namespaces"),
                true));
    }

    @Override
    public Set<String> egressAllowHosts(GatewayConfig cfg) {
        try {
            String host = URI.create(catalogUrl(cfg)).getHost();
            return host == null || host.isBlank() ? Set.of() : Set.of(host);
        } catch (IllegalArgumentException e) {
            // an unparsable catalog url contributes no allowed host, so egress stays closed
            return Set.of();
        }
    }

    static String catalogUrl(GatewayConfig cfg) {
        return cfg.adapterProperty(
                "iceberg.rest.catalog.url",
                cfg.adapterProperty("ICEBERG_REST_CATALOG_URL", "http://localhost:8181"));
    }

    /**
     * Iceberg REST models a namespace as an array of levels, so a dotted namespace such as
     * {@code bronze.sales} is sent as two levels rather than one literal name with a dot in it.
     */
    private static String createNamespaceBody(String namespace) {
        StringBuilder sb = new StringBuilder("{\"namespace\":[");
        String[] levels = namespace.split("\\.");
        for (int i = 0; i < levels.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(Inputs.jsonEscape(levels[i])).append('"');
        }
        return sb.append("],\"properties\":{}}").toString();
    }

    /**
     * Builds the Iceberg REST {@code set-properties} update from the caller supplied properties
     * object. Keys are validated and values are length bounded, so no unvalidated JSON body reaches
     * the catalog. An empty requirement list keeps the commit unconditional, which matches the
     * property-only change this tool exposes.
     */
    private static String setPropertiesBody(CallContext ctx) {
        Map<String, Object> args = ctx.arguments();
        Object raw = args == null ? null : args.get("properties");
        if (!(raw instanceof Map<?, ?> properties)) {
            throw new Inputs.InvalidInput("properties must be an object of table property names to values");
        }
        if (properties.isEmpty()) {
            throw new Inputs.InvalidInput("properties must not be empty");
        }
        StringBuilder sb = new StringBuilder(
                "{\"requirements\":[],\"updates\":[{\"action\":\"set-properties\",\"updates\":{");
        boolean first = true;
        for (Map.Entry<?, ?> e : properties.entrySet()) {
            String key = Inputs.requireId(e.getKey() == null ? null : String.valueOf(e.getKey()));
            String value = e.getValue() == null ? "" : String.valueOf(e.getValue());
            if (value.length() > MAX_PROPERTY_VALUE_CHARS) {
                throw new Inputs.InvalidInput("property value exceeds max length " + MAX_PROPERTY_VALUE_CHARS);
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(Inputs.jsonEscape(key)).append("\":\"")
                    .append(Inputs.jsonEscape(value)).append('"');
        }
        return sb.append("}}]}").toString();
    }

    private static boolean isDryRun(CallContext ctx) {
        return Boolean.parseBoolean(String.valueOf(ctx.arguments().getOrDefault("dryRun", "false")));
    }

    private static String dryRunJson(String op, String table) {
        return "{\"status\":\"dry_run\",\"op\":\"" + Inputs.jsonEscape(op)
                + "\",\"table\":\"" + Inputs.jsonEscape(table) + "\"}";
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
