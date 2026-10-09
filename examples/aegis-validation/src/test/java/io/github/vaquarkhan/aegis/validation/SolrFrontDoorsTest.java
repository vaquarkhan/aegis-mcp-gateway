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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * examples/adopters/solr: the canonical {@code adopters/configurations/adapters/solr/solr.yaml} against a real
 * Apache Solr 9.10.1 node in SolrCloud mode with its embedded ZooKeeper, started with the JVM options
 * {@code bin/solr start -c -f} uses, through both front doors.
 *
 * <p>Needs {@code AEGIS_SOLR_HOME} (examples/adopters/solr/install-solr.ps1); skipped, never passed, without it.
 */
class SolrFrontDoorsTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static JavaServer solr;
    private static int port;

    @TempDir
    Path dir;

    @BeforeAll
    static void startSolr() throws Exception {
        String homeEnv = System.getenv("AEGIS_SOLR_HOME");
        Assumptions.assumeTrue(homeEnv != null && Files.isDirectory(Path.of(homeEnv)),
                "AEGIS_SOLR_HOME is not set: run examples/adopters/solr/install-solr.ps1 (SKIPPED, not passed)");
        Path tip = Path.of(homeEnv).toAbsolutePath();
        Path server = tip.resolve("server");
        Path work = Path.of("target", "solr-server").toAbsolutePath();
        if (Files.exists(work)) {
            try (var s = Files.walk(work)) {
                for (Path p : (Iterable<Path>) s.sorted(java.util.Comparator.reverseOrder())::iterator) {
                    Files.delete(p);
                }
            }
        }
        Path solrHome = Files.createDirectories(work.resolve("home"));
        Path logs = Files.createDirectories(work.resolve("logs"));
        Files.copy(server.resolve("solr").resolve("solr.xml"), solrHome.resolve("solr.xml"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.createDirectories(server.resolve("tmp"));
        // SolrCloud puts the embedded ZooKeeper on the Solr port + 1000.
        do {
            port = JavaServer.freePort();
        } while (port > 64000 || JavaServer.listening(port + 1000));

        // The options bin/solr.cmd assembles for "start -c -f" (Solr 9.10.1), with loopback bindings.
        List<String> cmd = new ArrayList<>(List.of(JavaServer.javaExecutable(), "-server", "-Xms512m", "-Xmx512m",
                "-Duser.timezone=UTC", "-XX:-OmitStackTraceInFastThrow", "-XX:+CrashOnOutOfMemoryError", "-XX:+UseG1GC",
                "-DdisableAdminUI=true", "-DzkClientTimeout=30000", "-DzkRun",
                "-Xss256k", "-Dsolr.jetty.host=127.0.0.1", "-Dsolr.zk.embedded.host=127.0.0.1", "-Dhost=127.0.0.1",
                "--add-modules", "jdk.incubator.vector",
                "-Dsolr.log.dir=" + logs,
                "-Dlog4j.configurationFile=" + server.resolve("resources").resolve("log4j2.xml"),
                "-DSTOP.PORT=" + (port - 1000 > 1024 ? port - 1000 : port + 2000), "-DSTOP.KEY=aegis-validation",
                "-Dsolr.solr.home=" + solrHome, "-Dsolr.install.dir=" + tip, "-Dsolr.install.symDir=" + tip,
                "-Dsolr.default.confdir=" + server.resolve("solr").resolve("configsets").resolve("_default").resolve("conf"),
                "-Djetty.port=" + port, "-Djetty.home=" + server, "-Djava.io.tmpdir=" + server.resolve("tmp"),
                "-jar", "start.jar", "--module=http"));
        solr = JavaServer.process(cmd, Map.of(), work.resolve("solr-console.log"), server);
        solr.awaitReady(() -> get("/solr/admin/collections?action=LIST&wt=json").contains("\"collections\""),
                Duration.ofSeconds(180), "Solr");
        for (String c : List.of("products", "scratch_ownhost", "scratch_knox")) {
            get("/solr/admin/collections?action=CREATE&name=" + c
                    + "&numShards=1&replicationFactor=1&collection.configName=_default&wt=json");
        }
        post("/solr/products/update?commit=true",
                "[{\"id\":\"p1\",\"name\":\"orders widget\"},{\"id\":\"p2\",\"name\":\"blue lamp\"}]");
    }

    @AfterAll
    static void stopSolr() throws Exception {
        if (solr != null) {
            solr.close();
        }
    }

    @ParameterizedTest(name = "solr through {0}")
    @ValueSource(strings = {FrontDoor.OWN_HOST, FrontDoor.KNOX})
    void governsARealSolrCloudNode(String door) throws Exception {
        String scratch = "scratch_" + door.replace("-", "");
        assertTrue(collections().contains("\"" + scratch + "\""), "Solr starts with collection " + scratch);

        AdopterScenario scenario = AdopterScenario.of("solr", AdopterScenario.canonical("adapters", "solr", "solr.yaml"),
                yaml -> yaml.replace("http://solr:8983/solr", "http://127.0.0.1:" + port + "/solr")
                        .replace("allowCidrs: [10.0.0.0/8]", "allowCidrs: [127.0.0.0/8]"),
                Map.of(Persona.READER, List.of("solr-users"),
                        Persona.OPERATOR, List.of("solr-operators"),
                        Persona.APPROVER, List.of("solr-approvers")),
                Map.of());

        try (FrontDoor d = FrontDoor.open(door, scenario, dir)) {
            d.note("real Solr 9.10.1 in SolrCloud mode on 127.0.0.1:" + port + "; collections products and " + scratch);
            GovernedFlow.read(d, "solr_system_info", Map.of(), "9.10.1");
            GovernedFlow.read(d, "solr_list_collections", Map.of(), "products");
            GovernedFlow.read(d, "solr_query", Map.of("collection", "products", "q", "name:orders"), "orders widget");
            GovernedFlow.destructive(d, "solr_delete_collection", Map.of("collection", scratch),
                    () -> assertTrue(collections().contains("\"" + scratch + "\""), "denied and previewed calls kept " + scratch),
                    () -> {
                        String now = collections();
                        assertFalse(now.contains("\"" + scratch + "\""), scratch + " is gone: " + now);
                        assertTrue(now.contains("\"products\""), "products untouched: " + now);
                        d.note("Solr no longer lists collection " + scratch + " and still has products");
                    });
        }
    }

    private static String collections() throws Exception {
        return get("/solr/admin/collections?action=LIST&wt=json");
    }

    private static String get(String path) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(60)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), path + ": " + r.body());
        return r.body();
    }

    private static void post(String path, String json) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(60)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), path + ": " + r.body());
    }
}
