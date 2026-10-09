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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.vaquarkhan.aegis.validation.frontdoor.AdopterScenario;
import io.github.vaquarkhan.aegis.validation.frontdoor.FrontDoor;
import io.github.vaquarkhan.aegis.validation.frontdoor.GovernedFlow;
import io.github.vaquarkhan.aegis.validation.frontdoor.JavaServer;
import io.github.vaquarkhan.aegis.validation.frontdoor.Persona;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * examples/adopters/zookeeper: the canonical {@code adopters/configurations/adapters/zookeeper/zookeeper.yaml}
 * against a REAL Apache ZooKeeper 3.9.3 server ({@code ZooKeeperServerMain}, as {@code bin/zkServer.sh}
 * runs it) in its own JVM, through both front doors. The profile has two sources and both are exercised:
 * the AdminServer's HTTP commands, and the data tree through the {@code zookeeper} source plugin, which with
 * Knox is installed in Knox's {@code ext/}.
 */
class ZooKeeperFrontDoorsTest {

    private static JavaServer server;
    private static ZooKeeper client;
    private static int clientPort;
    private static int adminPort;

    @TempDir
    Path dir;

    @BeforeAll
    static void startZooKeeper() throws Exception {
        clientPort = JavaServer.freePort();
        adminPort = JavaServer.freePort();
        Path work = Path.of("target", "zookeeper-server").toAbsolutePath();
        Files.createDirectories(work.resolve("data"));
        Path cfg = work.resolve("zoo.cfg");
        Files.writeString(cfg, String.join("\n",
                "tickTime=2000",
                "dataDir=" + work.resolve("data").toString().replace('\\', '/'),
                "clientPort=" + clientPort,
                "clientPortAddress=127.0.0.1",
                "admin.enableServer=true",
                "admin.serverAddress=127.0.0.1",
                "admin.serverPort=" + adminPort, ""));
        server = new JavaServer("aegis.zookeeper.serverClasspathFile", List.of(),
                "org.apache.zookeeper.server.ZooKeeperServerMain", List.of(cfg.toString()), work.resolve("zookeeper-server.log"));
        HttpClient http = HttpClient.newHttpClient();
        server.awaitReady(() -> http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + adminPort + "/commands/ruok"))
                .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode() == 200,
                Duration.ofSeconds(90), "ZooKeeper AdminServer");

        CountDownLatch connected = new CountDownLatch(1);
        client = new ZooKeeper("127.0.0.1:" + clientPort, 15_000, e -> {
            if (e.getState() == Watcher.Event.KeeperState.SyncConnected) {
                connected.countDown();
            }
        });
        if (!connected.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("could not connect to ZooKeeper on " + clientPort);
        }
        client.addAuthInfo("digest", "aegis:secret".getBytes(StandardCharsets.UTF_8));
        create("/aegis", "");
        create("/aegis/orders", "order-service-config");
    }

    @AfterAll
    static void stopZooKeeper() throws Exception {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
    }

    @ParameterizedTest(name = "zookeeper through {0}")
    @ValueSource(strings = {FrontDoor.OWN_HOST, FrontDoor.KNOX})
    void governsARealZooKeeperServer(String door) throws Exception {
        String node = "/aegis/stale-lock-" + door.replace("-", "");
        create(node, "held by worker-7");

        AdopterScenario scenario = AdopterScenario.of("zookeeper",
                AdopterScenario.canonical("adapters", "zookeeper", "zookeeper.yaml"),
                yaml -> yaml.replace("http://zookeeper:8080", "http://127.0.0.1:" + adminPort)
                        .replace("connectString: zookeeper:2181", "connectString: 127.0.0.1:" + clientPort)
                        .replace("allowCidrs: [10.0.0.0/8]", "allowCidrs: [127.0.0.0/8]"),
                Map.of(Persona.READER, List.of("zookeeper-users"),
                        Persona.OPERATOR, List.of("zookeeper-operators"),
                        Persona.APPROVER, List.of("zookeeper-approvers")),
                Map.of("AEGIS_ZOOKEEPER_DIGEST", "aegis:secret")).withKnoxPlugin(AdopterScenario.plugin("aegis.zookeeper.pluginJar", "aegis.zookeeper.pluginClasspathFile"),
                List.of());

        try (FrontDoor d = FrontDoor.open(door, scenario, dir)) {
            d.note("real ZooKeeper 3.9.3: client port " + clientPort + ", AdminServer " + adminPort + "; znode " + node + " exists");
            GovernedFlow.read(d, "zookeeper_stat", Map.of(), "server_stats");
            GovernedFlow.read(d, "zookeeper_list_children", Map.of("path", "/aegis"), "orders");
            GovernedFlow.destructive(d, "zookeeper_delete_node", Map.of("path", node, "version", 0),
                    () -> assertNotNull(client.exists(node, false), "denied and previewed calls did not delete " + node),
                    () -> {
                        assertNull(client.exists(node, false), node + " is really gone");
                        d.note("ZooKeeper reports " + node + " no longer exists");
                    });
        }
    }

    private static void create(String path, String data) throws Exception {
        if (client.exists(path, false) == null) {
            client.create(path, data.getBytes(StandardCharsets.UTF_8), ZooDefs.Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT);
        }
    }
}
