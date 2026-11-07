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
package io.github.vaquarkhan.aegis.validation;

import java.io.File;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.spark.sql.connect.SparkSession;

/**
 * A real Apache Spark 4.0.1 Spark Connect server in its own JVM: the class
 * {@code sbin/start-connect-server.sh} runs, with the classpath Maven resolved for
 * {@code aegis-validation-spark-connect-server}. The test talks to it only as a client would.
 */
final class SparkConnectServer implements AutoCloseable {

    /** The JVM options Spark documents for Java 17, shared by the server and by any client JVM (Arrow). */
    static final List<String> JAVA17_OPENS = List.of(
            "--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
            "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED",
            "--add-opens=java.base/java.net=ALL-UNNAMED", "--add-opens=java.base/java.nio=ALL-UNNAMED",
            "--add-opens=java.base/java.util=ALL-UNNAMED", "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
            "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED", "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
            "--add-opens=java.base/sun.nio.cs=ALL-UNNAMED", "--add-opens=java.base/sun.security.action=ALL-UNNAMED",
            "--add-opens=java.base/sun.util.calendar=ALL-UNNAMED", "-Djdk.reflect.useDirectMethodHandle=false",
            "-Dio.netty.tryReflectionSetAccessible=true");

    private final Process server;
    private final int port;
    private final Path log;

    SparkConnectServer(Path work) throws Exception {
        this(work, List.of());
    }

    /**
     * @param sparkConf additional {@code -Dspark.*} options, for example an Iceberg catalog; passed exactly
     *                  as {@code spark-defaults.conf} or {@code --conf} would pass them
     */
    SparkConnectServer(Path work, List<String> sparkConf) throws Exception {
        this(work, sparkConf, List.of());
    }

    /** As above, with jars added after the Spark Connect server classpath (as {@code --jars} would). */
    SparkConnectServer(Path work, List<String> sparkConf, List<Path> extraClasspath) throws Exception {
        String cpFile = System.getProperty("aegis.sparkConnect.serverClasspathFile", "");
        if (cpFile.isBlank() || !Files.isRegularFile(Path.of(cpFile))) {
            throw new IllegalStateException("Spark Connect server classpath missing (build with -Preal-engines): " + cpFile);
        }
        // Pre-created: on Windows Hadoop needs winutils.exe to set permissions on a directory it creates.
        Files.createDirectories(work.resolve("warehouse"));
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString()));
        cmd.addAll(JAVA17_OPENS);
        cmd.addAll(List.of("-Dspark.master=local[2]", "-Dspark.app.name=aegis-spark-connect-example",
                "-Dspark.connect.grpc.binding.address=127.0.0.1", "-Dspark.connect.grpc.binding.port=" + port,
                "-Dspark.driver.host=127.0.0.1", "-Dspark.driver.bindAddress=127.0.0.1", "-Dspark.ui.enabled=false",
                "-Dspark.sql.warehouse.dir=" + work.resolve("warehouse").toUri()));
        cmd.addAll(sparkConf);
        StringBuilder classpath = new StringBuilder(Files.readString(Path.of(cpFile), StandardCharsets.UTF_8).trim());
        for (Path extra : extraClasspath) {
            classpath.append(File.pathSeparator).append(extra.toAbsolutePath());
        }
        cmd.addAll(List.of("-cp", classpath.toString(),
                "org.apache.spark.sql.connect.service.SparkConnectServer"));
        log = work.resolve("spark-connect-server.log");
        File out = log.toFile();
        server = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(out).start();
        io.github.vaquarkhan.aegis.validation.frontdoor.RunRecord.engineProcess(cmd, null, java.util.Map.of(), cpFile);
        long deadline = System.currentTimeMillis() + 180_000;
        Exception last = null;
        while (System.currentTimeMillis() < deadline) {
            if (!server.isAlive()) {
                throw new IllegalStateException("Spark Connect server exited; log: " + Files.readString(log));
            }
            try {
                sql("CREATE OR REPLACE GLOBAL TEMP VIEW orders AS SELECT * FROM VALUES "
                        + "(1, 'widget', 9.5), (2, 'gadget', 12.0), (3, 'gizmo', 3.25) AS t(id, item, price)");
                return;
            } catch (Exception e) {
                last = e;
                Thread.sleep(2_000);
            }
        }
        throw new IllegalStateException("Spark Connect server did not become ready", last);
    }

    int port() {
        return port;
    }

    /** Runs SQL as an ordinary Spark Connect client, outside Aegis, to set up and check real state. */
    List<String> sql(String query) {
        try (SparkSession s = SparkSession.builder().remote("sc://127.0.0.1:" + port).create()) {
            List<String> rows = new ArrayList<>();
            for (Object r : s.sql(query).collectAsList()) {
                rows.add(String.valueOf(r));
            }
            return rows;
        }
    }

    boolean globalTempViewExists(String name) {
        return sql("SHOW VIEWS IN global_temp").stream().anyMatch(r -> r.contains("," + name + ",") || r.contains("," + name + "]"));
    }

    @Override
    public void close() throws Exception {
        server.destroy();
        if (!server.waitFor(20, TimeUnit.SECONDS)) {
            server.destroyForcibly();
        }
    }
}
