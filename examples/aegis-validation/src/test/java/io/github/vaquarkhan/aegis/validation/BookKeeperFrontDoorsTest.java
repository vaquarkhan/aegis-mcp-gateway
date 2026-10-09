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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * examples/adopters/bookkeeper: the canonical {@code adopters/configurations/adapters/bookkeeper/bookkeeper.yaml}
 * against a real Apache BookKeeper 4.18.1 bookie with its HTTP admin server, through both front doors. The
 * processes are what a deployment runs: a ZooKeeper server for metadata, {@code bookkeeper shell initnewcluster},
 * the bookie ({@code org.apache.bookkeeper.server.Main}, as {@code bin/bookkeeper bookie} runs it), and ledgers
 * written with BookKeeper's own client.
 */
class BookKeeperFrontDoorsTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final List<JavaServer> SERVERS = new ArrayList<>();
    private static int httpPort;
    private static final List<String> LEDGERS = new ArrayList<>();

    @TempDir
    Path dir;

    @BeforeAll
    static void startBookie() throws Exception {
        Path work = Path.of("target", "bookkeeper-server").toAbsolutePath();
        if (Files.exists(work)) {
            try (var s = Files.walk(work)) {
                for (Path p : (Iterable<Path>) s.sorted(java.util.Comparator.reverseOrder())::iterator) {
                    Files.delete(p);
                }
            }
        }
        Files.createDirectories(work.resolve("zk"));
        Files.createDirectories(work.resolve("journal"));
        Files.createDirectories(work.resolve("ledgers"));
        String fwd = work.toString().replace('\\', '/');
        int zkPort = JavaServer.freePort();
        int bookiePort = JavaServer.freePort();
        httpPort = JavaServer.freePort();

        Path zoo = work.resolve("zoo.cfg");
        Files.writeString(zoo, "tickTime=2000\ndataDir=" + fwd + "/zk\nclientPort=" + zkPort
                + "\nclientPortAddress=127.0.0.1\nadmin.enableServer=false\n");
        SERVERS.add(new JavaServer("aegis.zookeeper.serverClasspathFile", List.of("-Xmx256m"),
                "org.apache.zookeeper.server.ZooKeeperServerMain", List.of(zoo.toString()), work.resolve("zookeeper.log")));
        SERVERS.get(0).awaitReady(() -> JavaServer.listening(zkPort), Duration.ofSeconds(90), "ZooKeeper for BookKeeper metadata");

        String metadata = "zk+hierarchical://127.0.0.1:" + zkPort + "/ledgers";
        Path conf = work.resolve("bk_server.conf");
        Files.writeString(conf, String.join("\n",
                "bookiePort=" + bookiePort,
                "advertisedAddress=127.0.0.1",
                "allowLoopback=true",
                "useHostNameAsBookieID=false",
                "metadataServiceUri=" + metadata,
                "journalDirectories=" + fwd + "/journal",
                "ledgerDirectories=" + fwd + "/ledgers",
                "ledgerStorageClass=org.apache.bookkeeper.bookie.InterleavedLedgerStorage",
                "httpServerEnabled=true",
                "httpServerHost=127.0.0.1",
                "httpServerPort=" + httpPort,
                "httpServerClass=org.apache.bookkeeper.http.vertx.VertxHttpServer", ""));

        JavaServer init = new JavaServer("aegis.bookkeeper.serverClasspathFile", List.of("-Xmx256m"),
                "org.apache.bookkeeper.bookie.BookieShell", List.of("-conf", conf.toString(), "initnewcluster"),
                Map.of(), work.resolve("initnewcluster.log"));
        assertEquals(0, init.awaitExit(Duration.ofSeconds(120)), "initnewcluster: " + Files.readString(init.log()));

        SERVERS.add(new JavaServer("aegis.bookkeeper.serverClasspathFile", List.of("-Xmx512m"),
                "org.apache.bookkeeper.server.Main", List.of("--conf", conf.toString()), Map.of(), work.resolve("bookie.log")));
        SERVERS.get(1).awaitReady(() -> get("/api/v1/bookie/info").contains("freeSpace"), Duration.ofSeconds(120),
                "BookKeeper bookie HTTP admin");

        Path seeder = Path.of("..", "adopters", "bookkeeper", "CreateLedgers.java").toAbsolutePath().normalize();
        // The JDK source launcher runs the .java file directly: java -cp <bookkeeper jars> CreateLedgers.java <uri> 3
        JavaServer create = new JavaServer("aegis.bookkeeper.serverClasspathFile", List.of(), seeder.toString(),
                List.of(metadata, "3"), Map.of(), work.resolve("create-ledgers.log"));
        assertEquals(0, create.awaitExit(Duration.ofSeconds(120)), "CreateLedgers: " + Files.readString(create.log()));
        for (String line : Files.readAllLines(create.log())) {
            if (line.startsWith("LEDGER ")) {
                LEDGERS.add(line.substring(7).trim());
            }
        }
        assertEquals(3, LEDGERS.size(), "three ledgers written: " + LEDGERS);
    }

    @AfterAll
    static void stopBookie() throws Exception {
        for (int i = SERVERS.size() - 1; i >= 0; i--) {
            SERVERS.get(i).close();
        }
    }

    @ParameterizedTest(name = "bookkeeper through {0}")
    @ValueSource(strings = {FrontDoor.OWN_HOST, FrontDoor.KNOX})
    void governsARealBookKeeperBookie(String door) throws Exception {
        // Ledger 0 is kept for both doors; the own host deletes the second, Knox the third.
        String keep = LEDGERS.get(0);
        String target = FrontDoor.OWN_HOST.equals(door) ? LEDGERS.get(1) : LEDGERS.get(2);
        assertTrue(ledgers().contains("\"" + target + "\""), "the bookie starts with ledger " + target);

        AdopterScenario scenario = AdopterScenario.of("bookkeeper",
                AdopterScenario.canonical("adapters", "bookkeeper", "bookkeeper.yaml"),
                yaml -> yaml.replace("http://bookkeeper:8000", "http://127.0.0.1:" + httpPort)
                        .replace("allowCidrs: [10.0.0.0/8]", "allowCidrs: [127.0.0.0/8]"),
                Map.of(Persona.READER, List.of("bookkeeper-users"),
                        Persona.OPERATOR, List.of("bookkeeper-operators"),
                        Persona.APPROVER, List.of("bookkeeper-approvers")),
                Map.of());

        try (FrontDoor d = FrontDoor.open(door, scenario, dir)) {
            d.note("real BookKeeper 4.18.1 bookie, HTTP admin on 127.0.0.1:" + httpPort + "; ledgers " + LEDGERS);
            GovernedFlow.read(d, "bookkeeper_bookie_info", Map.of(), "freeSpace");
            GovernedFlow.read(d, "bookkeeper_list_bookies", Map.of(), "127.0.0.1");
            GovernedFlow.read(d, "bookkeeper_list_ledgers", Map.of(), "\"" + target + "\"");
            GovernedFlow.destructive(d, "bookkeeper_delete_ledger", Map.of("ledgerId", target),
                    () -> assertTrue(ledgers().contains("\"" + target + "\""), "denied and previewed calls kept ledger " + target),
                    () -> {
                        String now = ledgers();
                        assertFalse(now.contains("\"" + target + "\""), "ledger " + target + " is gone: " + now);
                        assertTrue(now.contains("\"" + keep + "\""), "ledger " + keep + " untouched: " + now);
                        d.note("the bookie no longer lists ledger " + target + " and still has ledger " + keep);
                    });
        }
    }

    private static String ledgers() throws Exception {
        return get("/api/v1/ledger/list");
    }

    private static String get(String path) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + httpPort + path))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), path + ": " + r.body());
        return r.body();
    }
}
