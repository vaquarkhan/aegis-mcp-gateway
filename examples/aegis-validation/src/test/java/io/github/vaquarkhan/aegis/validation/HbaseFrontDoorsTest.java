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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.validation.frontdoor.AdopterScenario;
import io.github.vaquarkhan.aegis.validation.frontdoor.DockerContainer;
import io.github.vaquarkhan.aegis.validation.frontdoor.FrontDoor;
import io.github.vaquarkhan.aegis.validation.frontdoor.GovernedFlow;
import io.github.vaquarkhan.aegis.validation.frontdoor.JavaServer;
import io.github.vaquarkhan.aegis.validation.frontdoor.Persona;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code adopters/configurations/adapters/hbase/hbase.yaml} against a real HBase REST (Stargate)
 * endpoint through both front doors. Uses the {@code dajobe/hbase} all-in-one image when available.
 */
class HbaseFrontDoorsTest {

    private static final String IMAGE = "dajobe/hbase:latest";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static DockerContainer hbase;
    private static int port;
    private static final String KEEP = "aegis_orders";
    private static String ownHostVictim;
    private static String knoxVictim;

    @TempDir
    Path dir;

    @BeforeAll
    static void startHbase() throws Exception {
        DockerContainer.requireDocker();
        assumeOrPull(IMAGE);
        port = JavaServer.freePort();
        Path log = Path.of("target", "hbase-server", "docker.log").toAbsolutePath();
        // dajobe/hbase exposes REST on 8080 and Master UI on 16010.
        hbase = DockerContainer.start("aegis-val-hbase", IMAGE,
                List.of("-p", "127.0.0.1:" + port + ":8080"),
                log);
        hbase.awaitReady(() -> {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(base() + "/version/cluster"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200;
        }, Duration.ofMinutes(5), "HBase REST /version/cluster");
        createTable(KEEP, "cf");
        ownHostVictim = "aegis_scratch_ownhost";
        knoxVictim = "aegis_scratch_knox";
        createTable(ownHostVictim, "cf");
        createTable(knoxVictim, "cf");
    }

    @AfterAll
    static void stopHbase() throws Exception {
        if (hbase != null) {
            hbase.close();
        }
    }

    @ParameterizedTest(name = "hbase through {0}")
    @ValueSource(strings = {FrontDoor.OWN_HOST, FrontDoor.KNOX})
    void governsARealHbase(String door) throws Exception {
        String victim = FrontDoor.OWN_HOST.equals(door) ? ownHostVictim : knoxVictim;
        assertTrue(listTables().contains(victim), victim + " present");

        String url = base();
        AdopterScenario scenario = AdopterScenario.of("hbase",
                AdopterScenario.canonical("adapters", "hbase", "hbase.yaml"),
                yaml -> yaml.replace("http://hbase-rest:8080", url)
                        .replace("allowCidrs: [10.0.0.0/8]", "allowCidrs: [127.0.0.0/8]"),
                Map.of(Persona.READER, List.of("hbase-users"),
                        Persona.OPERATOR, List.of("hbase-operators"),
                        Persona.APPROVER, List.of("hbase-approvers")),
                Map.of());

        try (FrontDoor d = FrontDoor.open(door, scenario, dir)) {
            d.note(IMAGE + " REST on " + url + "; delete table " + victim);
            GovernedFlow.read(d, "hbase_cluster_version", Map.of(), "Version");
            GovernedFlow.read(d, "hbase_list_tables", Map.of(), KEEP);
            GovernedFlow.read(d, "hbase_get_table_schema", Map.of("table", KEEP), "cf");
            GovernedFlow.destructive(d, "hbase_delete_table", Map.of("table", victim),
                    () -> assertTrue(listTables().contains(victim), victim + " still listed"),
                    () -> {
                        assertFalse(listTables().contains(victim), victim + " deleted");
                        assertTrue(listTables().contains(KEEP), KEEP + " untouched");
                        d.note("HBase deleted " + victim + " and still has " + KEEP);
                    });
        }
    }

    private static void createTable(String name, String cf) throws Exception {
        // HBase REST create: POST / table schema XML/JSON. Prefer JSON.
        String body = "{\"name\":\"" + name + "\",\"ColumnSchema\":[{\"name\":\"" + cf + "\"}]}";
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(base() + "/" + name + "/schema"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(r.statusCode() == 201 || r.statusCode() == 200 || r.statusCode() == 409,
                "create " + name + ": " + r.statusCode() + " " + r.body());
    }

    private static String listTables() throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(base() + "/"))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
        return r.body();
    }

    private static String base() {
        return "http://127.0.0.1:" + port;
    }

    private static void assumeOrPull(String image) {
        try {
            Process p = new ProcessBuilder("docker", "image", "inspect", image).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            if (p.waitFor() == 0) {
                return;
            }
            Process pull = new ProcessBuilder("docker", "pull", image).redirectErrorStream(true).start();
            String out = new String(pull.getInputStream().readAllBytes());
            org.junit.jupiter.api.Assumptions.assumeTrue(pull.waitFor() == 0,
                    "HBase image unavailable (SKIPPED, not passed): " + out);
        } catch (Exception e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "HBase image unavailable: " + e);
        }
    }
}
