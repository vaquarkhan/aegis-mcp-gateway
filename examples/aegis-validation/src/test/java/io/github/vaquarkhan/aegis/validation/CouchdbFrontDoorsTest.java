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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code adopters/configurations/adapters/couchdb/couchdb.yaml} against a real Apache CouchDB 3.4.2
 * container (official {@code couchdb} image) through both front doors.
 */
class CouchdbFrontDoorsTest {

    private static final String USER = "aegis";
    private static final String PASS = "aegis-validation-couchdb";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static DockerContainer couch;
    private static int port;

    @TempDir
    Path dir;

    @BeforeAll
    static void startCouch() throws Exception {
        DockerContainer.requireDocker();
        port = JavaServer.freePort();
        Path log = Path.of("target", "couchdb-server", "docker.log").toAbsolutePath();
        couch = DockerContainer.start("aegis-val-couchdb", "couchdb:3.4.2",
                List.of("-p", "127.0.0.1:" + port + ":5984",
                        "-e", "COUCHDB_USER=" + USER,
                        "-e", "COUCHDB_PASSWORD=" + PASS),
                log);
        couch.awaitReady(() -> {
            HttpResponse<String> r = HTTP.send(request("GET", "/_up", null), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200;
        }, Duration.ofMinutes(2), "CouchDB 3.4.2");
        // Seed: a stable database the READ tools can see, outside Aegis.
        HttpResponse<String> orders = putDb("orders");
        assertTrue(orders.statusCode() == 201 || orders.statusCode() == 412, orders.body());
    }

    @AfterAll
    static void stopCouch() throws Exception {
        if (couch != null) {
            couch.close();
        }
    }

    @ParameterizedTest(name = "couchdb through {0}")
    @ValueSource(strings = {FrontDoor.OWN_HOST, FrontDoor.KNOX})
    void governsARealCouchDb(String door) throws Exception {
        String scratch = "scratch_" + door.replace("-", "");
        HttpResponse<String> created = putDb(scratch);
        assertTrue(created.statusCode() == 201 || created.statusCode() == 412, created.body());

        String url = "http://127.0.0.1:" + port;
        AdopterScenario scenario = AdopterScenario.of("couchdb",
                AdopterScenario.canonical("adapters", "couchdb", "couchdb.yaml"),
                yaml -> yaml.replace("http://couchdb:5984", url)
                        .replace("allowCidrs: [10.0.0.0/8]", "allowCidrs: [127.0.0.0/8]"),
                Map.of(Persona.READER, List.of("couchdb-users"),
                        Persona.OPERATOR, List.of("couchdb-operators"),
                        Persona.APPROVER, List.of("couchdb-approvers")),
                Map.of("COUCHDB_USER", USER, "COUCHDB_PASSWORD", PASS));

        try (FrontDoor d = FrontDoor.open(door, scenario, dir)) {
            d.note("official couchdb:3.4.2 on " + url + "; seeded databases orders and " + scratch);
            GovernedFlow.read(d, "couchdb_list_dbs", Map.of(), "orders");
            GovernedFlow.read(d, "couchdb_get_db", Map.of("db", "orders"), "\"db_name\":\"orders\"");
            GovernedFlow.destructive(d, "couchdb_delete_db", Map.of("db", scratch),
                    () -> assertTrue(listDbs().contains("\"" + scratch + "\""), scratch + " still listed"),
                    () -> {
                        assertFalse(listDbs().contains("\"" + scratch + "\""), scratch + " deleted");
                        assertTrue(listDbs().contains("\"orders\""), "orders untouched");
                        d.note("CouchDB no longer has " + scratch + " and still has orders");
                    });
        }
    }

    private static HttpResponse<String> putDb(String db) throws Exception {
        return HTTP.send(request("PUT", "/" + db, ""), HttpResponse.BodyHandlers.ofString());
    }

    private static String listDbs() throws Exception {
        return HTTP.send(request("GET", "/_all_dbs", null), HttpResponse.BodyHandlers.ofString()).body();
    }

    private static HttpRequest request(String method, String path, String body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString((USER + ":" + PASS).getBytes(StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(10));
        if (body == null) {
            return b.method(method, HttpRequest.BodyPublishers.noBody()).build();
        }
        return b.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build();
    }
}
