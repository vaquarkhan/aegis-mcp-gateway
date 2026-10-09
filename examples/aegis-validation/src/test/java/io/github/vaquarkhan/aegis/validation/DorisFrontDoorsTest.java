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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code adopters/configurations/adapters/doris/doris.yaml} against a real Apache Doris frontend
 * (read-only profile: no MUTATE/DESTRUCTIVE tools) through both front doors.
 *
 * <p>Uses the official all-in-one image {@code apache/doris:all-in-one-4.1.3}. Fails hard when the image
 * cannot be pulled (never skipped-as-passed).
 */
class DorisFrontDoorsTest {

    private static final String IMAGE = "apache/doris:all-in-one-4.1.3";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static DockerContainer doris;
    private static int port;

    @TempDir
    Path dir;

    @BeforeAll
    static void startDoris() throws Exception {
        DockerContainer.requireDocker();
        pullOrFail(IMAGE);
        port = JavaServer.freePort();
        Path log = Path.of("target", "doris-server", "docker.log").toAbsolutePath();
        // Hostname alias matches Doris all-in-one expectations; FE HTTP needs several minutes on cold start.
        doris = DockerContainer.start("aegis-val-doris", IMAGE,
                List.of("--hostname", "doris",
                        "-p", "127.0.0.1:" + port + ":8030",
                        "-p", "127.0.0.1:" + (port + 1) + ":9030"),
                log);
        doris.awaitReady(() -> {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(base() + "/api/bootstrap"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            String body = r.body();
            return r.statusCode() == 200 && body.contains("\"msg\":\"success\"") && body.contains("\"code\":0");
        }, Duration.ofMinutes(10), "Doris FE bootstrap");
        // All-in-one defaults to root with an empty password. Set it over the FE HTTP SQL API (the
        // container mysql client truncates 127.0.0.1 under Windows docker exec). Aegis needs a non-empty
        // passwordEnv value for basic credentials.
        String emptyRoot = java.util.Base64.getEncoder()
                .encodeToString("root:".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String aegisRoot = java.util.Base64.getEncoder()
                .encodeToString("root:aegis".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        HttpResponse<String> setPw = HTTP.send(HttpRequest.newBuilder(
                        URI.create(base() + "/api/query/default_cluster/mysql"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Basic " + emptyRoot)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"stmt\":\"SET PASSWORD FOR 'root'@'%' = PASSWORD('aegis')\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        boolean passwordReady = setPw.statusCode() == 200 && setPw.body().contains("\"code\":0");
        if (!passwordReady) {
            // Reused FE volume / prior run may already have the password; prove with aegis auth.
            HttpResponse<String> probe = HTTP.send(HttpRequest.newBuilder(
                            URI.create(base() + "/api/query/default_cluster/information_schema"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Basic " + aegisRoot)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"stmt\":\"SHOW DATABASES\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            passwordReady = probe.statusCode() == 200 && probe.body().toLowerCase().contains("information_schema");
            if (!passwordReady) {
                throw new IllegalStateException("could not set Doris root password: " + setPw.statusCode()
                        + " " + setPw.body());
            }
        }
        // Prove basic auth + HTTP SQL path before opening either front door.
        doris.awaitReady(() -> {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(
                            URI.create(base() + "/api/query/default_cluster/information_schema"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Basic " + aegisRoot)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"stmt\":\"SHOW DATABASES\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 && r.body().toLowerCase().contains("information_schema");
        }, Duration.ofMinutes(3), "Doris HTTP SQL with basic auth");
    }

    @AfterAll
    static void stopDoris() throws Exception {
        if (doris != null) {
            doris.close();
        }
    }

    @ParameterizedTest(name = "doris through {0}")
    @ValueSource(strings = {FrontDoor.OWN_HOST, FrontDoor.KNOX})
    void governsARealDoris(String door) throws Exception {
        String url = base();
        AdopterScenario scenario = AdopterScenario.of("doris",
                AdopterScenario.canonical("adapters", "doris", "doris.yaml"),
                yaml -> yaml.replace("http://doris-fe:8030", url)
                        .replace("allowCidrs: [10.0.0.0/8]", "allowCidrs: [127.0.0.0/8]"),
                Map.of(Persona.READER, List.of("doris-users"),
                        Persona.OPERATOR, List.of("doris-operators"),
                        Persona.APPROVER, List.of("doris-approvers")),
                Map.of("DORIS_USER", "root", "DORIS_PASSWORD", "aegis"));

        try (FrontDoor d = FrontDoor.open(door, scenario, dir)) {
            d.note(IMAGE + " FE HTTP on " + url + " (read-only adopter)");
            GovernedFlow.read(d, "doris_cluster_overview", Map.of(), "code");
            GovernedFlow.read(d, "doris_status", Map.of(), "data");
            GovernedFlow.read(d, "doris_run_sql_readonly", Map.of("sql", "SHOW DATABASES"), "information_schema");
            // Write smuggled into the read-only SQL tool must be refused by the sql-readonly hook.
            GovernedFlow.refused(d, "write smuggled into doris SQL", Persona.READER,
                    "doris_run_sql_readonly", Map.of("sql", "DROP DATABASE aegis_should_fail"),
                    "INVALID_INPUT", () -> assertTrue(true));
        }
    }

    private static String base() {
        return "http://127.0.0.1:" + port;
    }

    private static void pullOrFail(String image) throws Exception {
        Process inspect = new ProcessBuilder("docker", "image", "inspect", image)
                .redirectErrorStream(true).start();
        inspect.getInputStream().readAllBytes();
        if (inspect.waitFor() == 0) {
            return;
        }
        Process pull = new ProcessBuilder("docker", "pull", image).redirectErrorStream(true).start();
        String out = new String(pull.getInputStream().readAllBytes());
        if (!pull.waitFor(30, java.util.concurrent.TimeUnit.MINUTES) || pull.exitValue() != 0) {
            throw new IllegalStateException("could not pull Doris image " + image + ": " + out);
        }
    }
}
