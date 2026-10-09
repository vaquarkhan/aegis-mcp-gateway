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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.validation.frontdoor.AdopterScenario;
import io.github.vaquarkhan.aegis.validation.frontdoor.FrontDoor;
import io.github.vaquarkhan.aegis.validation.frontdoor.GovernedFlow;
import io.github.vaquarkhan.aegis.validation.frontdoor.JavaServer;
import io.github.vaquarkhan.aegis.validation.frontdoor.Persona;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * examples/adopters/pulsar: the canonical {@code adopters/configurations/adapters/pulsar/pulsar.yaml} against a real
 * Apache Pulsar 4.0.13 standalone (broker, bookie and metadata store in one process, as {@code bin/pulsar
 * standalone} runs it), through both front doors. Besides the approval flow, the profile's DENY rule keeps the
 * broker's own {@code pulsar} tenant out of reach even for an operator.
 *
 * <p>Needs {@code AEGIS_PULSAR_HOME} (examples/adopters/pulsar/install-pulsar.ps1); skipped, never passed, without it.
 */
class PulsarFrontDoorsTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static JavaServer pulsar;
    private static JavaServer metadataZooKeeper;
    private static int webPort;

    @TempDir
    Path dir;

    @BeforeAll
    static void startPulsar() throws Exception {
        String homeEnv = System.getenv("AEGIS_PULSAR_HOME");
        Assumptions.assumeTrue(homeEnv != null && Files.isDirectory(Path.of(homeEnv)),
                "AEGIS_PULSAR_HOME is not set: run examples/adopters/pulsar/install-pulsar.ps1 (SKIPPED, not passed)");
        Path home = Path.of(homeEnv).toAbsolutePath();
        Path work = Path.of("target", "pulsar-standalone").toAbsolutePath();
        if (Files.exists(work)) {
            try (var s = Files.walk(work)) {
                for (Path p : (Iterable<Path>) s.sorted(java.util.Comparator.reverseOrder())::iterator) {
                    Files.delete(p);
                }
            }
        }
        Files.createDirectories(work);
        webPort = JavaServer.freePort();
        int brokerPort = JavaServer.freePort();
        int bookiePort = JavaServer.freePort();
        int zkPort = JavaServer.freePort();
        Path zooCfg = work.resolve("zoo.cfg");
        Files.writeString(zooCfg, "tickTime=2000\ndataDir=" + work.resolve("zookeeper").toString().replace('\\', '/')
                + "\nclientPort=" + zkPort + "\nclientPortAddress=127.0.0.1\nadmin.enableServer=false\n");
        metadataZooKeeper = new JavaServer("aegis.zookeeper.serverClasspathFile", List.of("-Xmx256m"),
                "org.apache.zookeeper.server.ZooKeeperServerMain", List.of(zooCfg.toString()), work.resolve("metadata-zookeeper.log"));
        metadataZooKeeper.awaitReady(() -> JavaServer.listening(zkPort), Duration.ofSeconds(90),
                "external ZooKeeper metadata store for Pulsar");
        io.github.vaquarkhan.aegis.validation.frontdoor.RunRecord.engineFile(zooCfg,
                "the external ZooKeeper metadata store configuration. Pulsar's default standalone RocksDB metadata store cannot start on Windows because it sets POSIX file permissions.");
        // The shipped standalone.conf, with loopback addresses and free ports.
        String conf = Files.readString(home.resolve("conf").resolve("standalone.conf"))
                .replaceFirst("(?m)^webServicePort=8080$", "webServicePort=" + webPort)
                .replaceFirst("(?m)^brokerServicePort=6650$", "brokerServicePort=" + brokerPort)
                .replaceFirst("(?m)^bindAddress=0\\.0\\.0\\.0$", "bindAddress=127.0.0.1")
                .replaceFirst("(?m)^advertisedAddress=$", "advertisedAddress=127.0.0.1")
                // A Knox topology takes longer to start than Pulsar's default inactive-topic sweep. The
                // validation topics are the test fixture, so retain them until the assertions delete one.
                .replaceFirst("(?m)^brokerDeleteInactiveTopicsEnabled=true$", "brokerDeleteInactiveTopicsEnabled=false");
        Path standalone = work.resolve("standalone.conf");
        Files.writeString(standalone, conf);

        String fwd = work.toString().replace('\\', '/');
        // The JVM options bin/pulsar standalone sets on Java 17 (from bin/pulsar and conf/pulsar_env.sh), smaller heap.
        List<String> cmd = List.of(JavaServer.javaExecutable(), "-cp",
                home.resolve("conf") + java.io.File.pathSeparator + home.resolve("lib") + java.io.File.separator + "*",
                "-Xms1g", "-Xmx1g", "-XX:MaxDirectMemorySize=1g",
                "-Djava.net.preferIPv4Stack=true", "-Djute.maxbuffer=10485760", "-Dzookeeper.clientTcpKeepAlive=true",
                "--add-opens", "java.base/java.io=ALL-UNNAMED", "--add-opens", "java.base/java.util.zip=ALL-UNNAMED",
                "--add-opens", "java.management/sun.management=ALL-UNNAMED",
                "--add-opens", "jdk.management/com.sun.management.internal=ALL-UNNAMED",
                "-Dio.netty.tryReflectionSetAccessible=true", "-Dorg.apache.pulsar.shade.io.netty.tryReflectionSetAccessible=true",
                "--add-opens", "java.base/java.nio=ALL-UNNAMED", "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
                "--add-opens", "java.base/jdk.internal.platform=ALL-UNNAMED",
                "-Dlog4j.configurationFile=log4j2.yaml", "-Dpulsar.log.dir=" + fwd + "/logs",
                "-Dpulsar.log.file=pulsar-standalone.log", "-Dpulsar.config.file=" + standalone,
                "org.apache.pulsar.PulsarStandaloneStarter",
                "--config", standalone.toString(), "-nfw", "-nss",
                // The default RocksDB metadata store does not start on Windows (it sets POSIX file permissions), so use
                // the real external ZooKeeper server above. Supplying metadata-url tells standalone not to launch its own.
                "--bookkeeper-dir", fwd + "/bookkeeper", "--metadata-url", "zk:127.0.0.1:" + zkPort,
                "--bookkeeper-port", Integer.toString(bookiePort));
        pulsar = JavaServer.process(cmd, Map.of(), work.resolve("pulsar-console.log"), home);
        io.github.vaquarkhan.aegis.validation.frontdoor.RunRecord.engineFile(standalone,
                "the shipped `conf/standalone.conf` with loopback addresses and the ports this run picked.");
        pulsar.awaitReady(() -> get("/admin/v2/clusters").contains("standalone"), Duration.ofSeconds(240), "Pulsar standalone");
        // Standalone answers /clusters before it has created the public/default namespace; observed on
        // 2026-10-03 as "Namespace not found" on the first topic PUT. Wait for the namespace itself.
        pulsar.awaitReady(() -> {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + webPort
                    + "/admin/v2/namespaces/public")).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 && r.body().contains("public/default");
        }, Duration.ofSeconds(120), "Pulsar namespace public/default");
        // The topics are created through the admin API, outside Aegis.
        for (String t : List.of("orders", "scratch_ownhost", "scratch_knox")) {
            send("PUT", "/admin/v2/persistent/public/default/" + t, 204);
        }
    }

    @AfterAll
    static void stopPulsar() throws Exception {
        if (pulsar != null) {
            pulsar.close();
        }
        if (metadataZooKeeper != null) {
            metadataZooKeeper.close();
        }
    }

    @ParameterizedTest(name = "pulsar through {0}")
    @ValueSource(strings = {FrontDoor.OWN_HOST, FrontDoor.KNOX})
    void governsARealPulsarStandalone(String door) throws Exception {
        String scratch = "scratch_" + door.replace("-", "");
        assertTrue(topics().contains("/" + scratch + "\""), "Pulsar starts with topic " + scratch);

        AdopterScenario scenario = AdopterScenario.of("pulsar", AdopterScenario.canonical("adapters", "pulsar", "pulsar.yaml"),
                yaml -> yaml.replace("http://pulsar-broker:8080", "http://127.0.0.1:" + webPort)
                        .replace("allowCidrs: [10.0.0.0/8]", "allowCidrs: [127.0.0.0/8]"),
                Map.of(Persona.READER, List.of("pulsar-users"),
                        Persona.OPERATOR, List.of("pulsar-operators"),
                        Persona.APPROVER, List.of("pulsar-approvers")),
                Map.of());

        try (FrontDoor d = FrontDoor.open(door, scenario, dir)) {
            d.note("real Pulsar 4.0.13 standalone, admin REST on 127.0.0.1:" + webPort + "; topics public/default/orders and " + scratch);
            GovernedFlow.read(d, "pulsar_list_clusters", Map.of(), "standalone");
            GovernedFlow.read(d, "pulsar_list_topics", Map.of("tenant", "public", "namespace", "default"), scratch);
            GovernedFlow.refused(d, "delete a topic in the broker's own pulsar tenant (DENY rule)", Persona.OPERATOR,
                    "pulsar_delete_topic", Map.of("tenant", "pulsar", "namespace", "system", "topic", "anything"),
                    "POLICY_DENIED", () -> { });
            GovernedFlow.destructive(d, "pulsar_delete_topic", Map.of("tenant", "public", "namespace", "default", "topic", scratch),
                    () -> assertTrue(topics().contains("/" + scratch + "\""), "denied and previewed calls kept " + scratch),
                    () -> {
                        String now = topics();
                        assertFalse(now.contains("/" + scratch + "\""), scratch + " is gone: " + now);
                        assertTrue(now.contains("/orders\""), "orders untouched: " + now);
                        d.note("Pulsar no longer lists " + scratch + " in public/default and still has orders");
                    });
        }
    }

    private static String topics() throws Exception {
        return get("/admin/v2/persistent/public/default");
    }

    private static String get(String path) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + webPort + path))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), path + ": " + r.body());
        return r.body();
    }

    private static void send(String method, String path, int expected) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + webPort + path))
                .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(expected, r.statusCode(), method + " " + path + ": " + r.body());
    }
}
