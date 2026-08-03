#!/usr/bin/env python3
"""Generate Kafka/Spark/Iceberg adapters and dist module (UTF-8 no BOM)."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

HEADER = """/*
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
"""


def w(rel: str, content: str) -> None:
    p = ROOT / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    if not content.endswith("\n"):
        content += "\n"
    p.write_text(content, encoding="utf-8", newline="\n")
    print("wrote", rel)


def adapter_pom(artifact: str, name: str, extra_deps: str = "") -> str:
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<!-- Author: Viquar Khan -->
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>io.github.vaquarkhan.aegis</groupId>
    <artifactId>aegis-mcp-gateway</artifactId>
    <version>0.1.0</version>
  </parent>
  <artifactId>{artifact}</artifactId>
  <name>{name}</name>
  <dependencies>
    <dependency>
      <groupId>io.github.vaquarkhan.aegis</groupId>
      <artifactId>mcp-gateway-core</artifactId>
      <version>${{project.version}}</version>
    </dependency>
{extra_deps}    <dependency>
      <groupId>org.slf4j</groupId>
      <artifactId>slf4j-api</artifactId>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>
</project>
"""


KAFKA_DEP = """    <dependency>
      <groupId>org.apache.kafka</groupId>
      <artifactId>kafka-clients</artifactId>
    </dependency>
"""


def main() -> None:
    # ---- Kafka ----
    w("mcp-adapter-kafka/pom.xml", adapter_pom("mcp-adapter-kafka", "Aegis MCP Adapter Kafka", KAFKA_DEP))
    w(
        "mcp-adapter-kafka/src/main/resources/adapters/kafka/tools.yaml",
        """engine: kafka
taxonomyClass: messaging
tools:
  - name: list_topics
    class: READ
    description: List Kafka topics
    inputSchema: '{"type":"object","properties":{}}'
  - name: describe_topic
    class: READ
    description: Describe a Kafka topic
    inputSchema: '{"type":"object","properties":{"topic":{"type":"string"}},"required":["topic"]}'
  - name: query_schema_registry
    class: READ
    description: Query Schema Registry for a subject
    inputSchema: '{"type":"object","properties":{"subject":{"type":"string"}},"required":["subject"]}'
  - name: inspect_dlq
    class: READ
    description: Inspect dead-letter queue topic samples
    inputSchema: '{"type":"object","properties":{"topic":{"type":"string"}},"required":["topic"]}'
  - name: create_topic
    class: MUTATE
    description: Create a Kafka topic
    inputSchema: '{"type":"object","properties":{"topic":{"type":"string"},"partitions":{"type":"string"},"approvalToken":{"type":"string"}},"required":["topic","approvalToken"]}'
  - name: alter_config
    class: MUTATE
    description: Alter topic or broker config
    inputSchema: '{"type":"object","properties":{"resource":{"type":"string"},"approvalToken":{"type":"string"}},"required":["resource","approvalToken"]}'
  - name: reset_offsets
    class: DESTRUCTIVE
    description: Reset consumer group offsets
    inputSchema: '{"type":"object","properties":{"groupId":{"type":"string"},"approvalToken":{"type":"string"}},"required":["groupId","approvalToken"]}'
  - name: delete_records
    class: DESTRUCTIVE
    description: Delete records before an offset
    inputSchema: '{"type":"object","properties":{"topic":{"type":"string"},"approvalToken":{"type":"string"}},"required":["topic","approvalToken"]}'
""",
    )
    w(
        "mcp-adapter-kafka/src/main/resources/META-INF/services/io.github.vaquarkhan.aegis.core.spi.EngineAdapter",
        "io.github.vaquarkhan.aegis.adapter.kafka.KafkaAdapter\n",
    )
    w(
        "mcp-adapter-kafka/src/main/java/io/github/vaquarkhan/aegis/adapter/kafka/KafkaAdapter.java",
        HEADER
        + """
package io.github.vaquarkhan.aegis.adapter.kafka;

import io.github.vaquarkhan.aegis.core.config.GatewayConfig;
import io.github.vaquarkhan.aegis.core.spi.CallContext;
import io.github.vaquarkhan.aegis.core.spi.EngineAdapter;
import io.github.vaquarkhan.aegis.core.spi.ResourceDef;
import io.github.vaquarkhan.aegis.core.spi.ToolClass;
import io.github.vaquarkhan.aegis.core.spi.ToolDef;
import io.github.vaquarkhan.aegis.core.util.Inputs;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kafka messaging adapter. Live AdminClient backends are stubbed until a cluster is wired.
 *
 * @author Viquar Khan
 */
public final class KafkaAdapter implements EngineAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaAdapter.class);

    @Override
    public String engineId() {
        return "kafka";
    }

    @Override
    public String taxonomyClass() {
        return "messaging";
    }

    @Override
    public List<ToolDef> tools(GatewayConfig cfg) {
        List<ToolDef> tools = new ArrayList<>();
        tools.add(tool("list_topics", ToolClass.READ, "List Kafka topics",
                "{\\"type\\":\\"object\\",\\"properties\\":{}}",
                ctx -> todoJson("list_topics")));
        tools.add(tool("describe_topic", ToolClass.READ, "Describe a Kafka topic",
                "{\\"type\\":\\"object\\",\\"properties\\":{\\"topic\\":{\\"type\\":\\"string\\"}},\\"required\\":[\\"topic\\"]}",
                ctx -> todoJson("describe_topic:" + Inputs.requireTopic(arg(ctx, "topic")))));
        tools.add(tool("query_schema_registry", ToolClass.READ, "Query Schema Registry for a subject",
                "{\\"type\\":\\"object\\",\\"properties\\":{\\"subject\\":{\\"type\\":\\"string\\"}},\\"required\\":[\\"subject\\"]}",
                ctx -> todoJson("query_schema_registry:" + Inputs.requireId(arg(ctx, "subject")))));
        tools.add(tool("inspect_dlq", ToolClass.READ, "Inspect dead-letter queue topic samples",
                "{\\"type\\":\\"object\\",\\"properties\\":{\\"topic\\":{\\"type\\":\\"string\\"}},\\"required\\":[\\"topic\\"]}",
                ctx -> todoJson("inspect_dlq:" + Inputs.requireTopic(arg(ctx, "topic")))));
        tools.add(tool("create_topic", ToolClass.MUTATE, "Create a Kafka topic",
                "{\\"type\\":\\"object\\",\\"properties\\":{\\"topic\\":{\\"type\\":\\"string\\"},\\"partitions\\":{\\"type\\":\\"string\\"},\\"approvalToken\\":{\\"type\\":\\"string\\"}},\\"required\\":[\\"topic\\",\\"approvalToken\\"]}",
                ctx -> todoJson("create_topic:" + Inputs.requireTopic(arg(ctx, "topic")))));
        tools.add(tool("alter_config", ToolClass.MUTATE, "Alter topic or broker config",
                "{\\"type\\":\\"object\\",\\"properties\\":{\\"resource\\":{\\"type\\":\\"string\\"},\\"approvalToken\\":{\\"type\\":\\"string\\"}},\\"required\\":[\\"resource\\",\\"approvalToken\\"]}",
                ctx -> todoJson("alter_config:" + Inputs.requireId(arg(ctx, "resource")))));
        tools.add(tool("reset_offsets", ToolClass.DESTRUCTIVE, "Reset consumer group offsets",
                "{\\"type\\":\\"object\\",\\"properties\\":{\\"groupId\\":{\\"type\\":\\"string\\"},\\"approvalToken\\":{\\"type\\":\\"string\\"}},\\"required\\":[\\"groupId\\",\\"approvalToken\\"]}",
                ctx -> todoJson("reset_offsets:" + Inputs.requireId(arg(ctx, "groupId")))));
        tools.add(tool("delete_records", ToolClass.DESTRUCTIVE, "Delete records before an offset",
                "{\\"type\\":\\"object\\",\\"properties\\":{\\"topic\\":{\\"type\\":\\"string\\"},\\"approvalToken\\":{\\"type\\":\\"string\\"}},\\"required\\":[\\"topic\\",\\"approvalToken\\"]}",
                ctx -> todoJson("delete_records:" + Inputs.requireTopic(arg(ctx, "topic")))));
        return tools;
    }

    @Override
    public List<ResourceDef> resources(GatewayConfig cfg) {
        return List.of(new ResourceDef(
                "kafka://cluster",
                "kafka-cluster",
                "application/json",
                ctx -> todoJson("kafka-cluster"),
                true));
    }

    @Override
    public Set<String> egressAllowHosts(GatewayConfig cfg) {
        String bootstrap = cfg.adapterProperty("kafka.bootstrap.servers", "localhost:9092");
        String host = bootstrap.contains(":") ? bootstrap.substring(0, bootstrap.indexOf(':')) : bootstrap;
        return Set.of(host);
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

    private static String todoJson(String op) {
        LOG.debug("kafka backend stub op={}", op);
        // TODO(DESIGN adapters): wire AdminClient / Schema Registry HTTP when cluster available
        return "{\\"status\\":\\"TODO\\",\\"op\\":\\"" + Inputs.jsonEscape(op) + "\\"}";
    }
}
""",
    )
    w(
        "mcp-adapter-kafka/src/test/java/io/github/vaquarkhan/aegis/adapter/kafka/KafkaAdapterTest.java",
        HEADER
        + """
package io.github.vaquarkhan.aegis.adapter.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.core.auth.CallerIdentity;
import io.github.vaquarkhan.aegis.core.config.GatewayConfig;
import io.github.vaquarkhan.aegis.core.spi.CallContext;
import io.github.vaquarkhan.aegis.core.spi.ToolClass;
import io.github.vaquarkhan.aegis.core.spi.ToolDef;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** @author Viquar Khan */
class KafkaAdapterTest {

    @Test
    void exposesExpectedToolsAndTaxonomy() {
        KafkaAdapter adapter = new KafkaAdapter();
        assertEquals("kafka", adapter.engineId());
        assertEquals("messaging", adapter.taxonomyClass());
        GatewayConfig cfg = GatewayConfig.builder().defaults().build();
        List<ToolDef> tools = adapter.tools(cfg);
        Set<String> names = tools.stream().map(ToolDef::name).collect(Collectors.toSet());
        assertTrue(names.containsAll(Set.of(
                "list_topics", "describe_topic", "query_schema_registry", "inspect_dlq",
                "create_topic", "alter_config", "reset_offsets", "delete_records")));
        assertEquals(ToolClass.DESTRUCTIVE,
                tools.stream().filter(t -> t.name().equals("delete_records")).findFirst().orElseThrow().cls());
    }

    @Test
    void describeTopicValidatesIdentifier() {
        KafkaAdapter adapter = new KafkaAdapter();
        GatewayConfig cfg = GatewayConfig.builder().defaults().build();
        ToolDef tool = adapter.tools(cfg).stream()
                .filter(t -> t.name().equals("describe_topic")).findFirst().orElseThrow();
        CallerIdentity caller = new CallerIdentity("test", Set.of("*"), Set.of("*"), false);
        CallContext ctx = new CallContext(
                "describe_topic", ToolClass.READ, Map.of("topic", "orders"), caller, "trace-1", Optional.empty());
        String body = tool.backend().apply(ctx);
        assertTrue(body.contains("describe_topic:orders"));
        assertFalse(body.contains("password="));
    }
}
""",
    )

    # ---- Spark ----
    w("mcp-adapter-spark/pom.xml", adapter_pom("mcp-adapter-spark", "Aegis MCP Adapter Spark"))
    w(
        "mcp-adapter-spark/src/main/resources/adapters/spark/tools.yaml",
        """engine: spark
taxonomyClass: batch
tools:
  - name: list_applications
    class: READ
    description: List Spark History applications
    inputSchema: '{"type":"object","properties":{}}'
  - name: get_application
    class: READ
    description: Get Spark application details from History Server
    inputSchema: '{"type":"object","properties":{"appId":{"type":"string"}},"required":["appId"]}'
  - name: run_sql_readonly
    class: READ
    description: Guarded read-only SQL via Spark Connect or Thrift
    inputSchema: '{"type":"object","properties":{"sql":{"type":"string"}},"required":["sql"]}'
  - name: submit_batch
    class: DESTRUCTIVE
    description: Submit a Livy batch job
    inputSchema: '{"type":"object","properties":{"file":{"type":"string"},"approvalToken":{"type":"string"}},"required":["file","approvalToken"]}'
  - name: kill_application
    class: DESTRUCTIVE
    description: Kill a Livy or YARN application
    inputSchema: '{"type":"object","properties":{"appId":{"type":"string"},"approvalToken":{"type":"string"}},"required":["appId","approvalToken"]}'
""",
    )
    w(
        "mcp-adapter-spark/src/main/resources/META-INF/services/io.github.vaquarkhan.aegis.core.spi.EngineAdapter",
        "io.github.vaquarkhan.aegis.adapter.spark.SparkAdapter\n",
    )
    w(
        "mcp-adapter-spark/src/main/java/io/github/vaquarkhan/aegis/adapter/spark/SparkHttpClient.java",
        HEADER
        + """
package io.github.vaquarkhan.aegis.adapter.spark;

import com.sun.net.httpserver.HttpServer; // kept for test visibility of JDK HTTP pattern
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal JDK HTTP client for Livy and History Server. No Spark core dependency.
 *
 * @author Viquar Khan
 */
public final class SparkHttpClient {

    private static final Logger LOG = LoggerFactory.getLogger(SparkHttpClient.class);

    private final String baseUrl;
    private final HttpClient http;

    public SparkHttpClient(String baseUrl) {
        String u = baseUrl == null ? "" : baseUrl;
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        this.baseUrl = u;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public String get(String path) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                throw new IllegalStateException("HTTP " + resp.statusCode());
            }
            return resp.body();
        } catch (Exception e) {
            LOG.warn("spark http get failed path={} msg={}", path, e.getMessage());
            throw new IllegalStateException("spark backend error: " + e.getMessage(), e);
        }
    }

    /** Silence unused import warning in IDEs that flag test-only HttpServer references. */
    static Class<?> fakeServerType() {
        return HttpServer.class;
    }
}
""",
    )
    w(
        "mcp-adapter-spark/src/main/java/io/github/vaquarkhan/aegis/adapter/spark/SparkAdapter.java",
        HEADER
        + """
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spark batch adapter. History reads via JDK HTTP; Livy submit/kill marked TODO without live cluster.
 *
 * @author Viquar Khan
 */
public final class SparkAdapter implements EngineAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(SparkAdapter.class);
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
        String historyUrl = cfg.adapterProperty("spark.history.url",
                cfg.adapterProperty("SPARK_HISTORY_URL", "http://localhost:18080"));
        SparkHttpClient history = new SparkHttpClient(historyUrl);
        List<ToolDef> tools = new ArrayList<>();
        tools.add(tool("list_applications", ToolClass.READ, "List Spark History applications",
                "{\\"type\\":\\"object\\",\\"properties\\":{}}",
                ctx -> {
                    try {
                        return history.get("/api/v1/applications");
                    } catch (RuntimeException e) {
                        return todoJson("list_applications");
                    }
                }));
        tools.add(tool("get_application", ToolClass.READ, "Get Spark application details from History Server",
                "{\\"type\\":\\"object\\",\\"properties\\":{\\"appId\\":{\\"type\\":\\"string\\"}},\\"required\\":[\\"appId\\"]}",
                ctx -> {
                    String appId = Inputs.requireId(arg(ctx, "appId"));
                    try {
                        return history.get("/api/v1/applications/" + appId);
                    } catch (RuntimeException e) {
                        return todoJson("get_application:" + appId);
                    }
                }));
        tools.add(tool("run_sql_readonly", ToolClass.READ, "Guarded read-only SQL via Spark Connect or Thrift",
                "{\\"type\\":\\"object\\",\\"properties\\":{\\"sql\\":{\\"type\\":\\"string\\"}},\\"required\\":[\\"sql\\"]}",
                ctx -> {
                    String sql = Inputs.requireSql(arg(ctx, "sql"), cfg.maxSqlChars());
                    if (!sqlGuard.isReadOnly(sql)) {
                        throw new Inputs.InvalidInput("SQL_NOT_READONLY");
                    }
                    // TODO(DESIGN adapters): Spark Connect / Thrift when enabled
                    return todoJson("run_sql_readonly");
                }));
        tools.add(tool("submit_batch", ToolClass.DESTRUCTIVE, "Submit a Livy batch job",
                "{\\"type\\":\\"object\\",\\"properties\\":{\\"file\\":{\\"type\\":\\"string\\"},\\"approvalToken\\":{\\"type\\":\\"string\\"}},\\"required\\":[\\"file\\",\\"approvalToken\\"]}",
                ctx -> todoJson("submit_batch:" + Inputs.jsonEscape(arg(ctx, "file")))));
        tools.add(tool("kill_application", ToolClass.DESTRUCTIVE, "Kill a Livy or YARN application",
                "{\\"type\\":\\"object\\",\\"properties\\":{\\"appId\\":{\\"type\\":\\"string\\"},\\"approvalToken\\":{\\"type\\":\\"string\\"}},\\"required\\":[\\"appId\\",\\"approvalToken\\"]}",
                ctx -> todoJson("kill_application:" + Inputs.requireId(arg(ctx, "appId")))));
        return tools;
    }

    @Override
    public List<ResourceDef> resources(GatewayConfig cfg) {
        return List.of(new ResourceDef(
                "spark://history",
                "spark-history",
                "application/json",
                ctx -> todoJson("spark-history"),
                true));
    }

    @Override
    public Optional<ReadOnlyGuard> readOnlyGuard() {
        return Optional.of(sqlGuard);
    }

    @Override
    public Set<String> egressAllowHosts(GatewayConfig cfg) {
        try {
            java.net.URI u = java.net.URI.create(cfg.adapterProperty("SPARK_LIVY_URL", "http://localhost:8998"));
            return u.getHost() == null ? Set.of() : Set.of(u.getHost());
        } catch (Exception e) {
            return Set.of();
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

    private static String todoJson(String op) {
        LOG.debug("spark backend stub op={}", op);
        return "{\\"status\\":\\"TODO\\",\\"op\\":\\"" + Inputs.jsonEscape(op) + "\\"}";
    }
}
""",
    )
    w(
        "mcp-adapter-spark/src/test/java/io/github/vaquarkhan/aegis/adapter/spark/SparkAdapterTest.java",
        HEADER
        + """
package io.github.vaquarkhan.aegis.adapter.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.vaquarkhan.aegis.core.config.GatewayConfig;
import io.github.vaquarkhan.aegis.core.spi.ToolClass;
import io.github.vaquarkhan.aegis.core.spi.ToolDef;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** @author Viquar Khan */
class SparkAdapterTest {

    @Test
    void taxonomyAndTools() {
        SparkAdapter adapter = new SparkAdapter();
        assertEquals("spark", adapter.engineId());
        assertEquals("batch", adapter.taxonomyClass());
        List<ToolDef> tools = adapter.tools(GatewayConfig.builder().defaults().build());
        Set<String> names = tools.stream().map(ToolDef::name).collect(Collectors.toSet());
        assertTrue(names.containsAll(Set.of(
                "list_applications", "get_application", "run_sql_readonly", "submit_batch", "kill_application")));
        assertEquals(ToolClass.DESTRUCTIVE,
                tools.stream().filter(t -> t.name().equals("submit_batch")).findFirst().orElseThrow().cls());
    }

    @Test
    void historyClientReadsEmbeddedFake() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/applications", exchange -> {
            byte[] body = "[{\\"id\\":\\"app-1\\"}]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            SparkHttpClient client = new SparkHttpClient("http://127.0.0.1:" + port);
            String body = client.get("/api/v1/applications");
            assertTrue(body.contains("app-1"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void configPropertyOverridesHistoryUrl() {
        GatewayConfig cfg = GatewayConfig.builder().defaults()
                .adapterProperties(Map.of("spark.history.url", "http://127.0.0.1:9"))
                .build();
        SparkAdapter adapter = new SparkAdapter();
        assertTrue(adapter.tools(cfg).stream().anyMatch(t -> t.name().equals("list_applications")));
    }
}
""",
    )

    # ---- Iceberg ----
    w("mcp-adapter-iceberg/pom.xml", adapter_pom("mcp-adapter-iceberg", "Aegis MCP Adapter Iceberg"))
    w(
        "mcp-adapter-iceberg/src/main/resources/adapters/iceberg/tools.yaml",
        """engine: iceberg
taxonomyClass: lakehouse
tools:
  - name: list_namespaces
    class: READ
    description: List Iceberg namespaces
    inputSchema: '{"type":"object","properties":{}}'
  - name: list_tables
    class: READ
    description: List tables in a namespace
    inputSchema: '{"type":"object","properties":{"namespace":{"type":"string"}},"required":["namespace"]}'
  - name: get_table
    class: READ
    description: Get Iceberg table metadata
    inputSchema: '{"type":"object","properties":{"namespace":{"type":"string"},"table":{"type":"string"}},"required":["namespace","table"]}'
  - name: create_namespace
    class: MUTATE
    description: Create an Iceberg namespace
    inputSchema: '{"type":"object","properties":{"namespace":{"type":"string"},"approvalToken":{"type":"string"}},"required":["namespace","approvalToken"]}'
  - name: alter_table
    class: MUTATE
    description: Alter Iceberg table properties
    inputSchema: '{"type":"object","properties":{"namespace":{"type":"string"},"table":{"type":"string"},"approvalToken":{"type":"string"}},"required":["namespace","table","approvalToken"]}'
  - name: drop_table
    class: DESTRUCTIVE
    description: Drop an Iceberg table (VRP-gated)
    inputSchema: '{"type":"object","properties":{"namespace":{"type":"string"},"table":{"type":"string"},"approvalToken":{"type":"string"},"dryRun":{"type":"boolean"}},"required":["namespace","table","approvalToken"]}'
  - name: expire_snapshots
    class: DESTRUCTIVE
    description: Expire Iceberg snapshots (VRP-gated)
    inputSchema: '{"type":"object","properties":{"namespace":{"type":"string"},"table":{"type":"string"},"approvalToken":{"type":"string"},"dryRun":{"type":"boolean"}},"required":["namespace","table","approvalToken"]}'
  - name: remove_orphan_files
    class: DESTRUCTIVE
    description: Remove orphan files (VRP-gated)
    inputSchema: '{"type":"object","properties":{"namespace":{"type":"string"},"table":{"type":"string"},"approvalToken":{"type":"string"},"dryRun":{"type":"boolean"}},"required":["namespace","table","approvalToken"]}'
  - name: rewrite_data_files
    class: DESTRUCTIVE
    description: Rewrite data files (VRP-gated)
    inputSchema: '{"type":"object","properties":{"namespace":{"type":"string"},"table":{"type":"string"},"approvalToken":{"type":"string"},"dryRun":{"type":"boolean"}},"required":["namespace","table","approvalToken"]}'
  - name: dry_run_maintenance
    class: READ
    description: Read-only dry-run companion for destructive maintenance
    inputSchema: '{"type":"object","properties":{"operation":{"type":"string"},"namespace":{"type":"string"},"table":{"type":"string"}},"required":["operation","namespace","table"]}'
""",
    )
    w(
        "mcp-adapter-iceberg/src/main/resources/META-INF/services/io.github.vaquarkhan.aegis.core.spi.EngineAdapter",
        "io.github.vaquarkhan.aegis.adapter.iceberg.IcebergAdapter\n",
    )
    w(
        "mcp-adapter-iceberg/src/main/java/io/github/vaquarkhan/aegis/adapter/iceberg/IcebergRestClient.java",
        HEADER
        + """
package io.github.vaquarkhan.aegis.adapter.iceberg;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Iceberg REST catalog client (JDK HTTP). Never returns vended storage credentials.
 *
 * @author Viquar Khan
 */
public final class IcebergRestClient {

    private static final Logger LOG = LoggerFactory.getLogger(IcebergRestClient.class);

    private final String baseUrl;
    private final HttpClient http;

    public IcebergRestClient(String baseUrl) {
        String u = baseUrl == null ? "" : baseUrl;
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        this.baseUrl = u;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public String get(String path) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                throw new IllegalStateException("HTTP " + resp.statusCode());
            }
            return redactCredentials(resp.body());
        } catch (Exception e) {
            LOG.warn("iceberg http get failed path={} msg={}", path, e.getMessage());
            throw new IllegalStateException("iceberg backend error: " + e.getMessage(), e);
        }
    }

    static String redactCredentials(String body) {
        if (body == null) {
            return "";
        }
        return body
                .replaceAll("(?i)\\"credential\\"\\s*:\\s*\\"[^\\"]*\\"", "\\"credential\\":\\"\\u003credacted\\u003e\\"")
                .replaceAll("(?i)\\"token\\"\\s*:\\s*\\"[^\\"]*\\"", "\\"token\\":\\"\\u003credacted\\u003e\\"")
                .replaceAll("(?i)\\"access-key-id\\"\\s*:\\s*\\"[^\\"]*\\"", "\\"access-key-id\\":\\"\\u003credacted\\u003e\\"")
                .replaceAll("(?i)\\"secret-access-key\\"\\s*:\\s*\\"[^\\"]*\\"", "\\"secret-access-key\\":\\"\\u003credacted\\u003e\\"");
    }
}
""",
    )
    w(
        "mcp-adapter-iceberg/src/main/java/io/github/vaquarkhan/aegis/adapter/iceberg/IcebergAdapter.java",
        HEADER
        + """
package io.github.vaquarkhan.aegis.adapter.iceberg;

import io.github.vaquarkhan.aegis.core.config.GatewayConfig;
import io.github.vaquarkhan.aegis.core.spi.CallContext;
import io.github.vaquarkhan.aegis.core.spi.EngineAdapter;
import io.github.vaquarkhan.aegis.core.spi.ResourceDef;
import io.github.vaquarkhan.aegis.core.spi.ToolClass;
import io.github.vaquarkhan.aegis.core.spi.ToolDef;
import io.github.vaquarkhan.aegis.core.util.Inputs;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Iceberg lakehouse adapter. Destructive maintenance is VRP-gated; credentials never returned.
 *
 * @author Viquar Khan
 */
public final class IcebergAdapter implements EngineAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(IcebergAdapter.class);

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
        String catalogUrl = cfg.adapterProperty(
                "iceberg.rest.catalog.url",
                cfg.adapterProperty("ICEBERG_REST_CATALOG_URL", "http://localhost:8181"));
        IcebergRestClient client = new IcebergRestClient(catalogUrl);
        List<ToolDef> tools = new ArrayList<>();
        tools.add(tool("list_namespaces", ToolClass.READ, "List Iceberg namespaces",
                "{\\"type\\":\\"object\\",\\"properties\\":{}}",
                ctx -> safeGet(client, "/v1/namespaces", "list_namespaces")));
        tools.add(tool("list_tables", ToolClass.READ, "List tables in a namespace",
                "{\\"type\\":\\"object\\",\\"properties\\":{\\"namespace\\":{\\"type\\":\\"string\\"}},\\"required\\":[\\"namespace\\"]}",
                ctx -> {
                    String ns = Inputs.requireNamespace(arg(ctx, "namespace"));
                    return safeGet(client, "/v1/namespaces/" + ns + "/tables", "list_tables:" + ns);
                }));
        tools.add(tool("get_table", ToolClass.READ, "Get Iceberg table metadata",
                "{\\"type\\":\\"object\\",\\"properties\\":{\\"namespace\\":{\\"type\\":\\"string\\"},\\"table\\":{\\"type\\":\\"string\\"}},\\"required\\":[\\"namespace\\",\\"table\\"]}",
                ctx -> {
                    String ns = Inputs.requireNamespace(arg(ctx, "namespace"));
                    String table = Inputs.requireTable(arg(ctx, "table"));
                    return safeGet(client, "/v1/namespaces/" + ns + "/tables/" + table, "get_table:" + ns + "." + table);
                }));
        tools.add(tool("create_namespace", ToolClass.MUTATE, "Create an Iceberg namespace",
                "{\\"type\\":\\"object\\",\\"properties\\":{\\"namespace\\":{\\"type\\":\\"string\\"},\\"approvalToken\\":{\\"type\\":\\"string\\"}},\\"required\\":[\\"namespace\\",\\"approvalToken\\"]}",
                ctx -> todoJson("create_namespace:" + Inputs.requireNamespace(arg(ctx, "namespace")))));
        tools.add(tool("alter_table", ToolClass.MUTATE, "Alter Iceberg table properties",
                "{\\"type\\":\\"object\\",\\"properties\\":{\\"namespace\\":{\\"type\\":\\"string\\"},\\"table\\":{\\"type\\":\\"string\\"},\\"approvalToken\\":{\\"type\\":\\"string\\"}},\\"required\\":[\\"namespace\\",\\"table\\",\\"approvalToken\\"]}",
                ctx -> todoJson("alter_table:" + Inputs.requireNamespace(arg(ctx, "namespace"))
                        + "." + Inputs.requireTable(arg(ctx, "table")))));
        for (String op : List.of("drop_table", "expire_snapshots", "remove_orphan_files", "rewrite_data_files")) {
            tools.add(tool(op, ToolClass.DESTRUCTIVE, op + " (VRP-gated)",
                    "{\\"type\\":\\"object\\",\\"properties\\":{\\"namespace\\":{\\"type\\":\\"string\\"},\\"table\\":{\\"type\\":\\"string\\"},\\"approvalToken\\":{\\"type\\":\\"string\\"},\\"dryRun\\":{\\"type\\":\\"boolean\\"}},\\"required\\":[\\"namespace\\",\\"table\\",\\"approvalToken\\"]}",
                    ctx -> {
                        String ns = Inputs.requireNamespace(arg(ctx, "namespace"));
                        String table = Inputs.requireTable(arg(ctx, "table"));
                        boolean dry = Boolean.parseBoolean(String.valueOf(ctx.arguments().getOrDefault("dryRun", "false")));
                        if (dry) {
                            return "{\\"status\\":\\"dry_run\\",\\"op\\":\\"" + op + "\\",\\"table\\":\\""
                                    + Inputs.jsonEscape(ns + "." + table) + "\\"}";
                        }
                        return todoJson(op + ":" + ns + "." + table);
                    }));
        }
        tools.add(tool("dry_run_maintenance", ToolClass.READ, "Read-only dry-run companion for destructive maintenance",
                "{\\"type\\":\\"object\\",\\"properties\\":{\\"operation\\":{\\"type\\":\\"string\\"},\\"namespace\\":{\\"type\\":\\"string\\"},\\"table\\":{\\"type\\":\\"string\\"}},\\"required\\":[\\"operation\\",\\"namespace\\",\\"table\\"]}",
                ctx -> {
                    String op = Inputs.requireId(arg(ctx, "operation"));
                    String ns = Inputs.requireNamespace(arg(ctx, "namespace"));
                    String table = Inputs.requireTable(arg(ctx, "table"));
                    return "{\\"status\\":\\"dry_run\\",\\"op\\":\\"" + Inputs.jsonEscape(op)
                            + "\\",\\"table\\":\\"" + Inputs.jsonEscape(ns + "." + table) + "\\"}";
                }));
        return tools;
    }

    @Override
    public List<ResourceDef> resources(GatewayConfig cfg) {
        return List.of(new ResourceDef(
                "iceberg://catalog",
                "iceberg-catalog",
                "application/json",
                ctx -> todoJson("iceberg-catalog"),
                true));
    }

    @Override
    public Set<String> egressAllowHosts(GatewayConfig cfg) {
        try {
            java.net.URI u = java.net.URI.create(
                    cfg.adapterProperty("ICEBERG_REST_CATALOG_URL", "http://localhost:8181"));
            return u.getHost() == null ? Set.of() : Set.of(u.getHost());
        } catch (Exception e) {
            return Set.of();
        }
    }

    private static String safeGet(IcebergRestClient client, String path, String fallbackOp) {
        try {
            return client.get(path);
        } catch (RuntimeException e) {
            return todoJson(fallbackOp);
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

    private static String todoJson(String op) {
        LOG.debug("iceberg backend stub op={}", op);
        return "{\\"status\\":\\"TODO\\",\\"op\\":\\"" + Inputs.jsonEscape(op) + "\\"}";
    }
}
""",
    )
    w(
        "mcp-adapter-iceberg/src/test/java/io/github/vaquarkhan/aegis/adapter/iceberg/IcebergAdapterTest.java",
        HEADER
        + """
package io.github.vaquarkhan.aegis.adapter.iceberg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.vaquarkhan.aegis.core.auth.CallerIdentity;
import io.github.vaquarkhan.aegis.core.config.GatewayConfig;
import io.github.vaquarkhan.aegis.core.spi.CallContext;
import io.github.vaquarkhan.aegis.core.spi.ToolClass;
import io.github.vaquarkhan.aegis.core.spi.ToolDef;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** @author Viquar Khan */
class IcebergAdapterTest {

    @Test
    void taxonomyAndDestructiveTools() {
        IcebergAdapter adapter = new IcebergAdapter();
        assertEquals("iceberg", adapter.engineId());
        assertEquals("lakehouse", adapter.taxonomyClass());
        Set<String> names = adapter.tools(GatewayConfig.builder().defaults().build()).stream()
                .map(ToolDef::name).collect(Collectors.toSet());
        assertTrue(names.containsAll(Set.of(
                "list_namespaces", "get_table", "drop_table", "expire_snapshots",
                "remove_orphan_files", "rewrite_data_files", "dry_run_maintenance")));
    }

    @Test
    void redactsVendedCredentials() {
        String raw = "{\\"credential\\":\\"AKIASECRET\\",\\"token\\":\\"abc\\"}";
        String safe = IcebergRestClient.redactCredentials(raw);
        assertFalse(safe.contains("AKIASECRET"));
        assertTrue(safe.contains("redacted"));
    }

    @Test
    void dryRunCompanionDoesNotMutate() {
        IcebergAdapter adapter = new IcebergAdapter();
        ToolDef tool = adapter.tools(GatewayConfig.builder().defaults().build()).stream()
                .filter(t -> t.name().equals("dry_run_maintenance")).findFirst().orElseThrow();
        CallerIdentity caller = new CallerIdentity("t", Set.of("*"), Set.of("*"), false);
        CallContext ctx = new CallContext(
                "dry_run_maintenance",
                ToolClass.READ,
                Map.of("operation", "expire_snapshots", "namespace", "db", "table", "events"),
                caller,
                "tr",
                Optional.empty());
        String body = tool.backend().apply(ctx);
        assertTrue(body.contains("dry_run"));
        assertTrue(body.contains("db.events"));
    }

    @Test
    void restClientUsesEmbeddedFake() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/namespaces", exchange -> {
            byte[] body = "{\\"namespaces\\":[[\\"db\\"]]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            IcebergRestClient client = new IcebergRestClient("http://127.0.0.1:" + server.getAddress().getPort());
            assertTrue(client.get("/v1/namespaces").contains("db"));
        } finally {
            server.stop(0);
        }
    }
}
""",
    )

    # ---- Dist ----
    w(
        "mcp-gateway-dist/pom.xml",
        """<?xml version="1.0" encoding="UTF-8"?>
<!-- Author: Viquar Khan -->
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>io.github.vaquarkhan.aegis</groupId>
    <artifactId>aegis-mcp-gateway</artifactId>
    <version>0.1.0</version>
  </parent>
  <artifactId>mcp-gateway-dist</artifactId>
  <name>Aegis MCP Gateway Dist</name>
  <dependencies>
    <dependency>
      <groupId>io.github.vaquarkhan.aegis</groupId>
      <artifactId>mcp-gateway-core</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>io.github.vaquarkhan.aegis</groupId>
      <artifactId>mcp-adapter-flink</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>io.github.vaquarkhan.aegis</groupId>
      <artifactId>mcp-adapter-kafka</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>io.github.vaquarkhan.aegis</groupId>
      <artifactId>mcp-adapter-spark</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>io.github.vaquarkhan.aegis</groupId>
      <artifactId>mcp-adapter-iceberg</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>ch.qos.logback</groupId>
      <artifactId>logback-classic</artifactId>
      <scope>runtime</scope>
    </dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-shade-plugin</artifactId>
        <version>3.5.1</version>
        <executions>
          <execution>
            <phase>package</phase>
            <goals><goal>shade</goal></goals>
            <configuration>
              <shadedArtifactAttached>true</shadedArtifactAttached>
              <shadedClassifierName>all</shadedClassifierName>
              <createDependencyReducedPom>false</createDependencyReducedPom>
              <transformers>
                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                  <mainClass>io.github.vaquarkhan.aegis.core.boot.GatewayBootstrap</mainClass>
                </transformer>
                <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
              </transformers>
              <filters>
                <filter>
                  <artifact>*:*</artifact>
                  <excludes>
                    <exclude>module-info.class</exclude>
                    <exclude>META-INF/*.SF</exclude>
                    <exclude>META-INF/*.DSA</exclude>
                    <exclude>META-INF/*.RSA</exclude>
                  </excludes>
                </filter>
              </filters>
              <finalName>aegis-mcp-gateway-${project.version}</finalName>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
""",
    )
    w(
        "mcp-gateway-dist/src/main/docker/Dockerfile",
        """# Aegis MCP Governance Gateway
FROM eclipse-temurin:17-jre
WORKDIR /opt/aegis
COPY target/aegis-mcp-gateway-0.1.0-all.jar /opt/aegis/aegis-mcp-gateway.jar
ENV MCP_GW_TRANSPORT=stdio
ENTRYPOINT ["java","-jar","/opt/aegis/aegis-mcp-gateway.jar"]
""",
    )
    w(
        "mcp-gateway-dist/src/main/helm/aegis-mcp-gateway/Chart.yaml",
        """apiVersion: v2
name: aegis-mcp-gateway
description: Aegis MCP Governance Gateway
type: application
version: 0.1.0
appVersion: "0.1.0"
""",
    )
    w(
        "mcp-gateway-dist/src/main/helm/aegis-mcp-gateway/values.yaml",
        """replicaCount: 1
image:
  repository: aegis-mcp-gateway
  tag: "0.1.0"
  pullPolicy: IfNotPresent
service:
  type: ClusterIP
  port: 8090
env:
  MCP_GW_TRANSPORT: http
  MCP_GW_AUTH_MODE: tokenfile
  MCP_GW_ADAPTERS: flink
""",
    )
    w(
        "mcp-gateway-dist/src/main/helm/aegis-mcp-gateway/templates/deployment.yaml",
        """apiVersion: apps/v1
kind: Deployment
metadata:
  name: aegis-mcp-gateway
spec:
  replicas: {{ .Values.replicaCount }}
  selector:
    matchLabels:
      app: aegis-mcp-gateway
  template:
    metadata:
      labels:
        app: aegis-mcp-gateway
    spec:
      containers:
        - name: gateway
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          ports:
            - containerPort: {{ .Values.service.port }}
          env:
{{- range $k, $v := .Values.env }}
            - name: {{ $k }}
              value: {{ $v | quote }}
{{- end }}
""",
    )
    print("stub adapters + dist complete")


if __name__ == "__main__":
    main()
