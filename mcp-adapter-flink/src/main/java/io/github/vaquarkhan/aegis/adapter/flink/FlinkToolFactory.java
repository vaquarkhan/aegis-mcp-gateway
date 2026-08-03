/*
 * Licensed to the Aegis MCP Gateway project under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.vaquarkhan.aegis.adapter.flink;

import io.github.vaquarkhan.aegis.adapter.flink.client.FlinkRestClient;
import io.github.vaquarkhan.aegis.adapter.flink.client.OutboundAuth;
import io.github.vaquarkhan.aegis.adapter.flink.client.SqlGatewayClient;
import io.github.vaquarkhan.aegis.adapter.flink.client.SqlReadonlyGuard;
import io.github.vaquarkhan.aegis.core.config.GatewayConfig;
import io.github.vaquarkhan.aegis.core.observability.Metrics;
import io.github.vaquarkhan.aegis.core.spi.CallContext;
import io.github.vaquarkhan.aegis.core.spi.ResourceDef;
import io.github.vaquarkhan.aegis.core.spi.ToolClass;
import io.github.vaquarkhan.aegis.core.spi.ToolDef;
import io.github.vaquarkhan.aegis.core.util.Inputs;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Builds the Flink {@link ToolDef} catalog by joining the YAML manifest
 * ({@code adapters/flink/tools.yaml}, which owns names, classes, descriptions and input schemas)
 * with the backend functions implemented here over Flink REST and the SQL Gateway.
 *
 * <p>The factory never decides allow or deny. It returns every tool it can serve and the gateway
 * core filters registration by exposure, write unlock, scope and policy.
 *
 * @author Viquar Khan
 */
public final class FlinkToolFactory {

    /** Classpath location of the YAML tool manifest for this adapter. */
    public static final String MANIFEST_RESOURCE = "/adapters/flink/tools.yaml";

    private static final Logger LOG = LoggerFactory.getLogger(FlinkToolFactory.class);

    /** One entry of the YAML manifest. */
    public record ToolSpec(String name, ToolClass cls, String description, String inputSchema) {}

    private final GatewayConfig cfg;
    private final FlinkRestClient flink;
    private final SqlGatewayClient gateway;
    private final SqlReadonlyGuard sqlGuard;

    public FlinkToolFactory(GatewayConfig cfg) {
        this(cfg, new SqlReadonlyGuard(), new Metrics());
    }

    public FlinkToolFactory(GatewayConfig cfg, SqlReadonlyGuard sqlGuard, Metrics metrics) {
        this(cfg,
                sqlGuard,
                new FlinkRestClient(FlinkConfigKeys.restUrl(cfg), metrics, FlinkConfigKeys.restAuthHeader(cfg)),
                new SqlGatewayClient(
                        FlinkConfigKeys.gatewayUrl(cfg),
                        defaultJsonMapper(),
                        metrics,
                        FlinkConfigKeys.gatewayAuthHeader(cfg)));
    }

    public FlinkToolFactory(
            GatewayConfig cfg, SqlReadonlyGuard sqlGuard, FlinkRestClient flink, SqlGatewayClient gateway) {
        this.cfg = cfg;
        this.sqlGuard = sqlGuard;
        this.flink = flink;
        this.gateway = gateway;
    }

    /** Tool catalog in manifest order. Manifest entries without a backend are skipped. */
    public List<ToolDef> tools() {
        Map<String, Function<CallContext, String>> backends = backends();
        List<ToolDef> tools = new ArrayList<>();
        for (ToolSpec spec : loadManifest()) {
            Function<CallContext, String> backend = backends.get(spec.name());
            if (backend == null) {
                LOG.warn("flink manifest lists tool {} with no backend; skipping", spec.name());
                continue;
            }
            Function<CallContext, String> guarded = ctx -> OutboundAuth.withCallContext(ctx, () -> backend.apply(ctx));
            tools.add(new ToolDef(spec.name(), spec.cls(), spec.description(), spec.inputSchema(), guarded));
        }
        LOG.debug("flink tool catalog size={}", tools.size());
        return tools;
    }

    public List<ResourceDef> resources() {
        return List.of(
                new ResourceDef(
                        "flink://cluster/overview",
                        "cluster-overview",
                        "application/json",
                        ctx -> OutboundAuth.withCallContext(ctx, () -> flink.get("/overview")),
                        true),
                new ResourceDef(
                        "flink://jobs",
                        "jobs",
                        "application/json",
                        ctx -> OutboundAuth.withCallContext(ctx, () -> flink.get("/jobs/overview")),
                        true),
                new ResourceDef(
                        "flink://health",
                        "health",
                        "application/json",
                        ctx -> OutboundAuth.withCallContext(ctx, this::health),
                        false));
    }

    /** Reads the YAML manifest shipped with this module. */
    public static List<ToolSpec> loadManifest() {
        try (InputStream in = FlinkToolFactory.class.getResourceAsStream(MANIFEST_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing tool manifest on classpath: " + MANIFEST_RESOURCE);
            }
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Map<String, Object> root = yaml.load(in);
            if (root == null) {
                throw new IllegalStateException("empty tool manifest: " + MANIFEST_RESOURCE);
            }
            Object toolsNode = root.get("tools");
            if (!(toolsNode instanceof List<?> entries)) {
                throw new IllegalStateException("tool manifest has no tools list: " + MANIFEST_RESOURCE);
            }
            List<ToolSpec> specs = new ArrayList<>();
            for (Object entry : entries) {
                if (!(entry instanceof Map<?, ?> map)) {
                    throw new IllegalStateException("tool manifest entry is not a mapping: " + entry);
                }
                specs.add(new ToolSpec(
                        requireText(map, "name"),
                        ToolClass.valueOf(requireText(map, "class").toUpperCase(Locale.ROOT)),
                        requireText(map, "description"),
                        requireText(map, "inputSchema")));
            }
            return List.copyOf(specs);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read tool manifest " + MANIFEST_RESOURCE, e);
        }
    }

    private Map<String, Function<CallContext, String>> backends() {
        Map<String, Function<CallContext, String>> b = new LinkedHashMap<>();

        b.put("get_cluster_info", ctx -> flink.get("/overview"));
        b.put("get_flink_config", ctx -> flink.get("/config"));
        b.put("list_taskmanagers", ctx -> flink.get("/taskmanagers"));
        b.put("list_jobs", ctx -> flink.get("/jobs/overview"));
        b.put("list_jars", ctx -> flink.get("/jars"));
        b.put("get_job", ctx -> flink.get("/jobs/" + jobId(ctx)));
        b.put("get_job_status", ctx -> flink.get("/jobs/" + jobId(ctx) + "/status"));
        b.put("get_job_config", ctx -> flink.get("/jobs/" + jobId(ctx) + "/config"));
        b.put("get_job_exceptions", ctx -> flink.get("/jobs/" + jobId(ctx) + "/exceptions"));
        b.put("get_job_metrics", ctx -> flink.get("/jobs/" + jobId(ctx) + "/metrics"));
        b.put("list_checkpoints", ctx -> flink.get("/jobs/" + jobId(ctx) + "/checkpoints"));

        b.put("run_sql_readonly", ctx -> {
            String sql = Inputs.requireSql(arg(ctx, "sql"), cfg.maxSqlChars());
            if (!sqlGuard.isReadOnly(sql)) {
                throw new Inputs.InvalidInput("SQL_NOT_READONLY");
            }
            return gateway.execute(sql);
        });

        b.put("trigger_savepoint", ctx ->
                flink.post("/jobs/" + jobId(ctx) + "/savepoints", targetDirectoryBody(ctx)));
        b.put("rescale_job", ctx -> flink.patch(
                "/jobs/" + jobId(ctx) + "/rescaling?parallelism=" + Inputs.requireInt(arg(ctx, "parallelism")),
                "{}"));
        b.put("upload_jar", ctx ->
                flink.uploadJar(Inputs.requireJarPath(arg(ctx, "path"), FlinkConfigKeys.jarUploadAllowDirs(cfg))));

        b.put("cancel_job", ctx -> flink.patch("/jobs/" + jobId(ctx), "{\"mode\":\"cancel\"}"));
        b.put("stop_job", ctx -> flink.post("/jobs/" + jobId(ctx) + "/stop", targetDirectoryBody(ctx)));
        b.put("run_jar", ctx -> flink.post("/jars/" + Inputs.requireId(arg(ctx, "jarId")) + "/run", runJarBody(ctx)));
        b.put("run_sql_ddl_dml", ctx ->
                gateway.execute(Inputs.requireSql(arg(ctx, "sql"), cfg.maxSqlChars())));

        return b;
    }

    private String health() {
        boolean restOk = flink.ping();
        boolean gatewayOk = gateway.ping();
        return "{\"engine\":\"flink\",\"flink_rest\":" + restOk + ",\"sql_gateway\":" + gatewayOk + "}";
    }

    private static String targetDirectoryBody(CallContext ctx) {
        String dir = arg(ctx, "targetDirectory");
        if (dir == null || dir.isBlank()) {
            return "{}";
        }
        return "{\"targetDirectory\":\"" + Inputs.jsonEscape(dir) + "\"}";
    }

    private static String runJarBody(CallContext ctx) {
        StringBuilder body = new StringBuilder("{");
        boolean first = true;
        String entryClass = arg(ctx, "entryClass");
        if (entryClass != null && !entryClass.isBlank()) {
            body.append("\"entryClass\":\"").append(Inputs.jsonEscape(entryClass)).append('"');
            first = false;
        }
        String programArgs = arg(ctx, "programArgs");
        if (programArgs != null && !programArgs.isBlank()) {
            if (!first) {
                body.append(',');
            }
            body.append("\"programArgs\":\"").append(Inputs.jsonEscape(programArgs)).append('"');
            first = false;
        }
        String parallelism = arg(ctx, "parallelism");
        if (parallelism != null && !parallelism.isBlank()) {
            if (!first) {
                body.append(',');
            }
            body.append("\"parallelism\":").append(Inputs.requireInt(parallelism));
        }
        body.append('}');
        return body.toString();
    }

    private static String jobId(CallContext ctx) {
        return Inputs.requireId(arg(ctx, "jobId"));
    }

    private static String arg(CallContext ctx, String key) {
        return ctx == null ? null : ctx.arg(key);
    }

    private static String requireText(Map<?, ?> map, String key) {
        Object v = map.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new IllegalStateException("tool manifest entry missing " + key);
        }
        return String.valueOf(v);
    }

    private static McpJsonMapper defaultJsonMapper() {
        return new JacksonMcpJsonMapperSupplier().get();
    }
}
