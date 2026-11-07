/*
 * Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vaquarkhan.aegis.sparkconnect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.config.BuildContext;
import io.github.vaquarkhan.aegis.config.Gateway;
import io.github.vaquarkhan.aegis.config.GatewayFactory;
import io.github.vaquarkhan.aegis.engine.CallOutcome;
import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.model.ControlTokens;
import io.github.vaquarkhan.aegis.engine.model.DenyCodes;
import io.github.vaquarkhan.aegis.engine.model.ToolCall;
import java.io.File;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The canonical adopters/configurations/spark-connect/spark-connect.yaml against a REAL Apache Spark 4.0.1 Spark Connect server
 * started in its own JVM ({@code org.apache.spark.sql.connect.service.SparkConnectServer}, the class
 * {@code sbin/start-connect-server.sh} runs). Opt-in: {@code mvn -Preal-engines verify}. Without
 * the profile the test is reported as skipped, never as passed.
 */
class RealSparkConnectServerTest {

    private static Process server;
    private static int port;
    private static Path work;

    static final CallerIdentity ANA = CallerIdentity.of("ana", Set.of("spark-users"), "idp", "jwt");
    static final CallerIdentity OSCAR = CallerIdentity.of("oscar", Set.of("spark-operators"), "idp", "jwt");
    static final CallerIdentity BOB = CallerIdentity.of("bob", Set.of("spark-approvers"), "idp", "jwt");

    @TempDir
    Path dir;

    @BeforeAll
    static void startServer() throws Exception {
        String cpFile = System.getProperty("aegis.sparkConnect.serverClasspathFile", "");
        Assumptions.assumeTrue(!cpFile.isBlank() && Files.exists(Path.of(cpFile)),
                "real Spark Connect server not configured (run with -Preal-engines)");
        work = Files.createTempDirectory("aegis-spark-connect");
        // Pre-created: on Windows Hadoop needs winutils.exe to set permissions on a directory it creates.
        Files.createDirectories(work.resolve("warehouse"));
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        String java = ProcessHandle.current().info().command().orElse("java");
        List<String> cmd = new ArrayList<>(List.of(java,
                "--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
                "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.net=ALL-UNNAMED", "--add-opens=java.base/java.nio=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED", "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
                "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED", "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
                "--add-opens=java.base/sun.nio.cs=ALL-UNNAMED", "--add-opens=java.base/sun.security.action=ALL-UNNAMED",
                "--add-opens=java.base/sun.util.calendar=ALL-UNNAMED", "-Djdk.reflect.useDirectMethodHandle=false",
                "-Dio.netty.tryReflectionSetAccessible=true",
                "-Dspark.master=local[2]", "-Dspark.app.name=aegis-spark-connect-validation",
                "-Dspark.connect.grpc.binding.address=127.0.0.1", "-Dspark.connect.grpc.binding.port=" + port,
                "-Dspark.driver.host=127.0.0.1", "-Dspark.driver.bindAddress=127.0.0.1", "-Dspark.ui.enabled=false",
                "-Dspark.sql.warehouse.dir=" + work.resolve("warehouse").toUri(),
                "-cp", Files.readString(Path.of(cpFile), StandardCharsets.UTF_8).trim(),
                "org.apache.spark.sql.connect.service.SparkConnectServer"));
        File log = work.resolve("spark-connect-server.log").toFile();
        server = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log).start();

        // Ready when a real client can run a query (not merely when the port is open).
        long deadline = System.currentTimeMillis() + 180_000;
        Exception last = null;
        while (System.currentTimeMillis() < deadline) {
            if (!server.isAlive()) {
                throw new IllegalStateException("Spark Connect server exited; log: " + Files.readString(log.toPath()));
            }
            try (org.apache.spark.sql.connect.SparkSession setup = org.apache.spark.sql.connect.SparkSession.builder()
                    .remote("sc://127.0.0.1:" + port).create()) {
                setup.sql("CREATE OR REPLACE GLOBAL TEMP VIEW orders AS SELECT * FROM VALUES "
                        + "(1, 'widget', 9.5), (2, 'gadget', 12.0), (3, 'gizmo', 3.25) AS t(id, item, price)").collectAsList();
                setup.sql("CREATE OR REPLACE GLOBAL TEMP VIEW scratch AS SELECT 1 AS x").collectAsList();
                return;
            } catch (Exception e) {
                last = e;
                Thread.sleep(2_000);
            }
        }
        throw new IllegalStateException("Spark Connect server did not become ready", last);
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (server != null) {
            server.destroy();
            if (!server.waitFor(20, TimeUnit.SECONDS)) {
                server.destroyForcibly();
            }
        }
    }

    private Gateway gateway() throws Exception {
        String yaml = Files.readString(Path.of("..", "..", "adopters", "configurations", "spark-connect", "spark-connect.yaml"))
                .replace("host: spark-connect", "host: 127.0.0.1")
                .replace("port: 15002", "port: " + port)
                .replace("allowHosts: [spark-connect]", "allowHosts: [127.0.0.1]")
                .replace("allowCidrs: [10.0.0.0/8]", "allowCidrs: [127.0.0.0/8]");
        Path cfg = dir.resolve("spark-connect.yaml");
        Files.writeString(cfg, yaml);
        Map<String, String> env = Map.of("AEGIS_SIGNING_KEY", "real-spark-connect-validation-key-0123456789");
        return GatewayFactory.build(cfg, BuildContext.defaults(dir).withEnv(env::get));
    }

    private static String out(CallOutcome o) {
        return Json.write(o.toMcpResult());
    }

    @Test
    void readToolsRunAgainstARealSparkConnectServer() throws Exception {
        try (Gateway g = gateway()) {
            CallOutcome dbs = g.engine().call(ANA, new ToolCall("1", "spark_list_databases", Map.of(), Map.of(), ""));
            assertTrue(dbs.allowed() && out(dbs).contains("default"), out(dbs));

            CallOutcome tables = g.engine().call(ANA, new ToolCall("2", "spark_list_tables", Map.of("database", "global_temp"), Map.of(), ""));
            assertTrue(tables.allowed() && out(tables).contains("orders"), out(tables));

            CallOutcome desc = g.engine().call(ANA, new ToolCall("3", "spark_describe_table", Map.of("table", "global_temp.orders"), Map.of(), ""));
            String d = out(desc);
            assertTrue(desc.allowed() && d.contains("\"item\"") && d.contains("\"price\""), d);

            CallOutcome preview = g.engine().call(ANA, new ToolCall("4", "spark_preview_table",
                    Map.of("table", "global_temp.orders", "limit", 2L), Map.of(), ""));
            String p = out(preview);
            assertTrue(preview.allowed() && p.contains("widget") && p.contains("gadget") && !p.contains("gizmo"), p);

            CallOutcome explain = g.engine().call(ANA, new ToolCall("5", "spark_explain_sql",
                    Map.of("query", "SELECT item FROM global_temp.orders WHERE price > 5"), Map.of(), ""));
            assertTrue(explain.allowed() && out(explain).contains("Physical Plan"), out(explain));

            // Spark's own analysis errors are caller errors (isError), not backend outages.
            CallOutcome missing = g.engine().call(ANA, new ToolCall("6", "spark_describe_table", Map.of("table", "global_temp.nope"), Map.of(), ""));
            assertTrue(missing.allowed() && out(missing).contains("Spark rejected the request") && out(missing).contains("TABLE_OR_VIEW_NOT_FOUND"),
                    out(missing));
            // Identifier injection never reaches Spark as SQL.
            CallOutcome inj = g.engine().call(ANA, new ToolCall("7", "spark_describe_table",
                    Map.of("table", "global_temp.orders; DROP VIEW global_temp.orders"), Map.of(), ""));
            assertTrue(out(inj).contains("\"isError\":true"), out(inj));

            // Readers cannot run SQL at all.
            assertEquals(DenyCodes.READONLY_CALLER, g.engine().call(ANA, new ToolCall("8", "spark_sql",
                    Map.of("query", "SELECT 1"), Map.of(), "")).decision().code());
        }
    }

    @Test
    void dropNeedsDryRunAndApprovalThenReallyDrops() throws Exception {
        try (Gateway g = gateway()) {
            Map<String, Object> args = Map.of("table", "global_temp.scratch");
            // 1. Without receipt and approval: denied, nothing happens.
            CallOutcome blocked = g.engine().call(OSCAR, new ToolCall("1", "spark_drop_table", args, Map.of(), ""));
            assertFalse(blocked.allowed());

            // 2. Dry run (TOOL mode): runs spark_describe_table instead, returns a receipt and an approval request.
            CallOutcome dry = g.engine().call(OSCAR, new ToolCall("2", "spark_drop_table", args,
                    Map.of(ControlTokens.META_DRY_RUN, true), ""));
            assertTrue(dry.allowed() && out(dry).contains("\"x\""), out(dry));
            String receipt = (String) dry.governance().get("dryRunReceipt");
            String requestId = (String) dry.governance().get("approvalRequestId");
            assertNotNull(receipt, out(dry));
            assertNotNull(requestId, out(dry));
            assertTrue(out(g.engine().call(ANA, new ToolCall("3", "spark_list_tables", Map.of("database", "global_temp"), Map.of(), "")))
                    .contains("scratch"), "the dry run did not drop anything");

            // 3. A different person approves; the requester promotes with token + receipt.
            g.engine().approvals().decide(requestId, BOB, true);
            String token = (String) g.engine().approvals().get(requestId, OSCAR).get("token");
            CallOutcome drop = g.engine().call(OSCAR, new ToolCall("4", "spark_drop_table", args,
                    Map.of(ControlTokens.META_APPROVAL, token, ControlTokens.META_RECEIPT, receipt), ""));
            assertTrue(drop.allowed(), out(drop));
            assertFalse(out(g.engine().call(ANA, new ToolCall("5", "spark_list_tables", Map.of("database", "global_temp"), Map.of(), "")))
                    .contains("scratch"), "the view is really gone");

            // 4. Replay of the same token and receipt is refused.
            assertEquals(DenyCodes.APPROVAL_REQUIRED, g.engine().call(OSCAR, new ToolCall("6", "spark_drop_table", args,
                    Map.of(ControlTokens.META_APPROVAL, token, ControlTokens.META_RECEIPT, receipt), "")).decision().code());
        }
    }
}
