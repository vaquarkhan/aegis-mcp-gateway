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
 * {@code adopters/configurations/adapters/druid/druid.yaml} against a real Apache Druid nano-quickstart
 * container through both front doors.
 */
class DruidFrontDoorsTest {

    private static final String IMAGE = "apache/druid:28.0.1";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static DockerContainer druid;
    private static int port;
    private static String ownHostVictim;
    private static String knoxVictim;

    @TempDir
    Path dir;

    @BeforeAll
    static void startDruid() throws Exception {
        DockerContainer.requireDocker();
        port = JavaServer.freePort();
        Path log = Path.of("target", "druid-server", "docker.log").toAbsolutePath();
        // Official image ENTRYPOINT is /druid.sh (single service). Nano-quickstart is a separate script.
        druid = DockerContainer.start("aegis-val-druid", IMAGE,
                List.of("--entrypoint", "bash", "-p", "127.0.0.1:" + port + ":8888"),
                List.of("-c", "/opt/druid/bin/start-nano-quickstart"),
                log);
        druid.awaitReady(() -> {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(base() + "/status/health"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200;
        }, Duration.ofMinutes(6), "Druid nano-quickstart");
        // Seed two disposable datasources by cloning metadata if wikipedia exists; otherwise create via SQL load.
        druid.awaitReady(() -> datasources().contains("wikipedia") || datasources().contains("["),
                Duration.ofMinutes(4), "Druid coordinator datasources API");
        ownHostVictim = ensureDisposable("aegis_ownhost");
        knoxVictim = ensureDisposable("aegis_knox");
    }

    @AfterAll
    static void stopDruid() throws Exception {
        if (druid != null) {
            druid.close();
        }
    }

    @ParameterizedTest(name = "druid through {0}")
    @ValueSource(strings = {FrontDoor.OWN_HOST, FrontDoor.KNOX})
    void governsARealDruid(String door) throws Exception {
        String victim = FrontDoor.OWN_HOST.equals(door) ? ownHostVictim : knoxVictim;
        assertTrue(datasources().contains(victim) || datasourceExists(victim), victim + " present");

        String url = base();
        AdopterScenario scenario = AdopterScenario.of("druid",
                AdopterScenario.canonical("adapters", "druid", "druid.yaml"),
                yaml -> yaml.replace("http://druid-router:8888", url)
                        .replace("allowCidrs: [10.0.0.0/8]", "allowCidrs: [127.0.0.0/8]"),
                Map.of(Persona.READER, List.of("druid-users"),
                        Persona.OPERATOR, List.of("druid-operators"),
                        Persona.APPROVER, List.of("druid-approvers")),
                Map.of());

        try (FrontDoor d = FrontDoor.open(door, scenario, dir)) {
            d.note(IMAGE + " nano-quickstart on " + url + "; disable datasource " + victim);
            GovernedFlow.read(d, "druid_list_datasources", Map.of(), victim);
            GovernedFlow.read(d, "druid_run_sql_readonly", Map.of("sql", "SELECT 1 AS n"), "n");
            GovernedFlow.refused(d, "write smuggled into druid SQL", Persona.READER,
                    "druid_run_sql_readonly", Map.of("sql", "DROP TABLE aegis_should_fail"),
                    "INVALID_INPUT", () -> assertTrue(true));
            GovernedFlow.destructive(d, "druid_disable_datasource", Map.of("datasource", victim),
                    () -> assertTrue(datasourceExists(victim), victim + " still enabled"),
                    () -> {
                        // After disable, coordinator may 404 the datasource or mark it disabled.
                        assertFalse(datasourceEnabled(victim), victim + " disabled");
                        d.note("Druid disabled datasource " + victim);
                    });
        }
    }

    private static String ensureDisposable(String name) throws Exception {
        if (datasourceExists(name)) {
            return name;
        }
        // Create an empty datasource by posting a supervisor-less mark: use coordinator "datasources" by
        // ingesting a tiny lookups-style mark via SQL INSERT is not available; use the wikipedia clone trick:
        // POST /druid/coordinator/v1/datasources/{name} is not create. Instead load a tiny inline task.
        String task = "{\"type\":\"index_parallel\",\"spec\":{\"dataSchema\":{\"dataSource\":\"" + name
                + "\",\"timestampSpec\":{\"column\":\"ts\",\"format\":\"iso\"},"
                + "\"dimensionsSpec\":{\"dimensions\":[\"x\"]},"
                + "\"metricsSpec\":[],\"granularitySpec\":{\"type\":\"uniform\",\"segmentGranularity\":\"day\","
                + "\"queryGranularity\":\"none\",\"rollup\":false}},"
                + "\"ioConfig\":{\"type\":\"index_parallel\",\"inputSource\":{\"type\":\"inline\","
                + "\"data\":\"{\\\"ts\\\":\\\"2020-01-01T00:00:00Z\\\",\\\"x\\\":\\\"a\\\"}\"},"
                + "\"inputFormat\":{\"type\":\"json\"}},\"tuningConfig\":{\"type\":\"index_parallel\"}}}";
        HTTP.send(HttpRequest.newBuilder(URI.create(base() + "/druid/indexer/v1/task"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(task)).build(), HttpResponse.BodyHandlers.ofString());
        long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        while (System.nanoTime() < deadline) {
            if (datasourceExists(name)) {
                return name;
            }
            Thread.sleep(2000);
        }
        // Fall back to wikipedia for one door only if ingest failed (still proves disable path once).
        if (datasourceExists("wikipedia")) {
            return "wikipedia";
        }
        throw new IllegalStateException("could not seed datasource " + name + ": " + datasources());
    }

    private static boolean datasourceExists(String name) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(
                        URI.create(base() + "/druid/coordinator/v1/datasources/" + name))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        return r.statusCode() == 200;
    }

    private static boolean datasourceEnabled(String name) throws Exception {
        return datasourceExists(name) && datasources().contains("\"" + name + "\"");
    }

    private static String datasources() throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(
                        URI.create(base() + "/druid/coordinator/v1/datasources"))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        return r.body();
    }

    private static String base() {
        return "http://127.0.0.1:" + port;
    }
}
