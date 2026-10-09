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
package io.github.vaquarkhan.aegis.examples;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;

/**
 * A real Apache Kafka 3.9.1 KRaft node (combined broker and controller) on loopback, started the way
 * {@code bin/kafka-storage.sh format} and {@code bin/kafka-server-start.sh} start one: {@code kafka.tools.StorageTool}
 * then {@code kafka.Kafka}, each in its own JVM, from the classpath that {@code aegis-validation-kafka-server}
 * resolves. Nothing is mocked; the test talks to it with the stock {@link Admin} client.
 */
final class RealKafkaBroker implements AutoCloseable {

    private final Path work;
    private final Process broker;
    final int port;

    private RealKafkaBroker(Path work, Process broker, int port) {
        this.work = work;
        this.broker = broker;
        this.port = port;
    }

    String bootstrap() {
        return "127.0.0.1:" + port;
    }

    Admin admin() {
        Properties p = new Properties();
        p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap());
        p.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 15_000);
        return Admin.create(p);
    }

    static RealKafkaBroker start(Path work) throws Exception {
        String cpFile = System.getProperty("aegis.kafka.serverClasspathFile");
        if (cpFile == null || !Files.isRegularFile(Path.of(cpFile))) {
            throw new IllegalStateException("aegis.kafka.serverClasspathFile is not set or missing (" + cpFile
                    + "); build examples/aegis-validation-kafka-server first");
        }
        String classpath = Files.readString(Path.of(cpFile), StandardCharsets.UTF_8).trim();
        int port = freePort();
        int controller = freePort();
        Files.createDirectories(work);
        String logs = Files.createDirectories(work.resolve("kraft-logs")).toString().replace('\\', '/');
        Path server = work.resolve("server.properties");
        Files.writeString(server, String.join("\n",
                "process.roles=broker,controller",
                "node.id=1",
                "controller.quorum.voters=1@127.0.0.1:" + controller,
                "listeners=PLAINTEXT://127.0.0.1:" + port + ",CONTROLLER://127.0.0.1:" + controller,
                "advertised.listeners=PLAINTEXT://127.0.0.1:" + port,
                "controller.listener.names=CONTROLLER",
                "listener.security.protocol.map=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT",
                "inter.broker.listener.name=PLAINTEXT",
                "log.dirs=" + logs,
                "num.partitions=1",
                "offsets.topic.replication.factor=1",
                "transaction.state.log.replication.factor=1",
                "transaction.state.log.min.isr=1",
                "group.initial.rebalance.delay.ms=0", ""));
        Process format = java(classpath, work.resolve("kafka-storage-format.log"), "kafka.tools.StorageTool",
                "format", "-t", org.apache.kafka.common.Uuid.randomUuid().toString(), "-c", server.toString());
        if (!format.waitFor(120, TimeUnit.SECONDS) || format.exitValue() != 0) {
            format.destroyForcibly();
            throw new IllegalStateException("kafka-storage format failed: " + Files.readString(work.resolve("kafka-storage-format.log")));
        }
        Process broker = java(classpath, work.resolve("kafka-broker.log"), "kafka.Kafka", server.toString());
        RealKafkaBroker b = new RealKafkaBroker(work, broker, port);
        long deadline = System.currentTimeMillis() + 120_000;
        Exception last = null;
        while (System.currentTimeMillis() < deadline) {
            if (!broker.isAlive()) {
                throw new IllegalStateException("Kafka broker exited: " + Files.readString(work.resolve("kafka-broker.log")));
            }
            try (Admin a = b.admin()) {
                a.describeCluster().nodes().get(10, TimeUnit.SECONDS);
                return b;
            } catch (Exception notYet) {
                last = notYet;
                Thread.sleep(500);
            }
        }
        b.close();
        throw new IllegalStateException("Kafka broker did not become ready", last);
    }

    private static Process java(String classpath, Path log, String main, String... args) throws IOException {
        List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx512m", "-cp", classpath, main));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile());
        // A JVM option inherited from the developer's shell must not change the broker.
        pb.environment().remove("JAVA_TOOL_OPTIONS");
        return pb.start();
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    Path brokerLog() {
        return work.resolve("kafka-broker.log");
    }

    @Override
    public void close() throws Exception {
        broker.destroy();
        if (!broker.waitFor(30, TimeUnit.SECONDS)) {
            broker.destroyForcibly();
            broker.waitFor(30, TimeUnit.SECONDS);
        }
    }
}
