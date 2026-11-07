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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code adopters/configurations/adapters/pinot/pinot.yaml} against a real Apache Pinot 1.2.0
 * QuickStart controller (official {@code apachepinot/pinot} image) through both front doors.
 * Destructive deletes use disposable QuickStart sample tables (not baseballStats), because creating
 * extra tables on the single-container QuickStart hits controller timeouts.
 */
class PinotFrontDoorsTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Pattern TABLE = Pattern.compile("\"([^\"]+)\"");

    private static DockerContainer pinot;
    private static int port;
    private static String ownHostVictim;
    private static String knoxVictim;

    @TempDir
    Path dir;

    @BeforeAll
    static void startPinot() throws Exception {
        DockerContainer.requireDocker();
        port = JavaServer.freePort();
        Path log = Path.of("target", "pinot-server", "docker.log").toAbsolutePath();
        pinot = DockerContainer.start("aegis-val-pinot", "apachepinot/pinot:1.2.0",
                List.of("-p", "127.0.0.1:" + port + ":9000"),
                List.of("QuickStart", "-type", "batch"),
                log);
        pinot.awaitReady(() -> {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(base() + "/health"))
                    .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200;
        }, Duration.ofMinutes(6), "Pinot 1.2.0 QuickStart");
        pinot.awaitReady(() -> tables().contains("baseballStats"), Duration.ofMinutes(4),
                "Pinot QuickStart baseballStats table");
        // Wait until QuickStart has at least two other sample tables to delete (one per front door).
        pinot.awaitReady(() -> disposableTables().size() >= 2, Duration.ofMinutes(5),
                "at least two disposable QuickStart tables");
        List<String> victims = disposableTables();
        Assumptions.assumeTrue(victims.size() >= 2, "QuickStart did not publish enough disposable tables");
        ownHostVictim = victims.get(0);
        knoxVictim = victims.get(1);
    }

    @AfterAll
    static void stopPinot() throws Exception {
        if (pinot != null) {
            pinot.close();
        }
    }

    @ParameterizedTest(name = "pinot through {0}")
    @ValueSource(strings = {FrontDoor.OWN_HOST, FrontDoor.KNOX})
    void governsARealPinotController(String door) throws Exception {
        String tableArg = FrontDoor.OWN_HOST.equals(door) ? ownHostVictim : knoxVictim;
        assertTrue(getTable(tableArg).statusCode() == 200, tableArg + " present before run");

        String url = base();
        AdopterScenario scenario = AdopterScenario.of("pinot",
                AdopterScenario.canonical("adapters", "pinot", "pinot.yaml"),
                yaml -> yaml.replace("http://pinot-controller:9000", url)
                        .replace("allowCidrs: [10.0.0.0/8]", "allowCidrs: [127.0.0.0/8]"),
                Map.of(Persona.READER, List.of("pinot-users"),
                        Persona.OPERATOR, List.of("pinot-operators"),
                        Persona.APPROVER, List.of("pinot-approvers")),
                Map.of());

        try (FrontDoor d = FrontDoor.open(door, scenario, dir)) {
            d.note("official apachepinot/pinot:1.2.0 QuickStart on " + url
                    + "; keep baseballStats, delete disposable " + tableArg);
            GovernedFlow.read(d, "pinot_list_tables", Map.of(), "baseballStats");
            GovernedFlow.read(d, "pinot_list_tenants", Map.of(), "DefaultTenant");
            GovernedFlow.destructive(d, "pinot_delete_table", Map.of("table", tableArg),
                    () -> assertTrue(tables().contains("\"" + tableArg + "\""), tableArg + " still listed"),
                    () -> {
                        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
                        while (System.nanoTime() < deadline) {
                            if (!tables().contains("\"" + tableArg + "\"")) {
                                break;
                            }
                            Thread.sleep(500);
                        }
                        assertTrue(!tables().contains("\"" + tableArg + "\""),
                                tableArg + " removed from /tables: " + tables());
                        assertTrue(tables().contains("baseballStats"), "baseballStats untouched");
                        d.note("Pinot deleted " + tableArg + " and still has baseballStats");
                    });
        }
    }

    private static String base() {
        return "http://127.0.0.1:" + port;
    }

    private static String tables() throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(base() + "/tables"))
                .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    private static List<String> disposableTables() throws Exception {
        List<String> out = new ArrayList<>();
        Matcher m = TABLE.matcher(tables());
        while (m.find()) {
            String t = m.group(1);
            if (!"tables".equals(t) && !"baseballStats".equals(t) && !t.endsWith("_OFFLINE")
                    && !t.endsWith("_REALTIME")) {
                out.add(t);
            }
        }
        return out;
    }

    private static HttpResponse<String> getTable(String table) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(base() + "/tables/" + table))
                .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
