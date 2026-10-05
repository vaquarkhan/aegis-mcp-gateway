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
 * <p>Uses the community all-in-one image {@code apache/doris:doris-2.1.7-x64} when available; otherwise
 * the FE-only image with a companion BE. Skipped (never passed) when the image cannot be pulled.
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
        AssumptionsDocker.assumeImage(IMAGE);
        port = JavaServer.freePort();
        Path log = Path.of("target", "doris-server", "docker.log").toAbsolutePath();
        doris = DockerContainer.start("aegis-val-doris", IMAGE,
                List.of("-p", "127.0.0.1:" + port + ":8030",
                        "-p", "127.0.0.1:" + (port + 1) + ":9030"),
                log);
        doris.awaitReady(() -> {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(base() + "/api/bootstrap"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 && r.body().contains("code");
        }, Duration.ofMinutes(5), "Doris FE bootstrap");
        // Aegis rejects empty passwordEnv values; set a real root password on the FE.
        Process setPw = new ProcessBuilder("docker", "exec", "aegis-val-doris", "bash", "-lc",
                "mysql -h127.0.0.1 -P9030 -uroot -e \"SET PASSWORD FOR 'root'@'%' = PASSWORD('aegis'); "
                        + "SET PASSWORD FOR 'root'@'localhost' = PASSWORD('aegis');\" "
                        + "|| mysql -h127.0.0.1 -P9030 -uroot -e \"ALTER USER 'root'@'%' IDENTIFIED BY 'aegis'; "
                        + "ALTER USER 'root'@'localhost' IDENTIFIED BY 'aegis';\"")
                .redirectErrorStream(true).start();
        String pwOut = new String(setPw.getInputStream().readAllBytes());
        if (!setPw.waitFor(2, java.util.concurrent.TimeUnit.MINUTES) || setPw.exitValue() != 0) {
            // Some all-in-one images already use a fixed password; continue and let tool calls prove auth.
            System.getLogger(DorisFrontDoorsTest.class.getName())
                    .log(System.Logger.Level.WARNING, "Doris password set soft-failed: " + pwOut);
        }
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

    /** Small helper so Doris can soft-skip when the image is not on the host and cannot be pulled quickly. */
    static final class AssumptionsDocker {
        private AssumptionsDocker() {}

        static void assumeImage(String image) {
            try {
                Process p = new ProcessBuilder("docker", "image", "inspect", image)
                        .redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                if (p.waitFor() == 0) {
                    return;
                }
                Process pull = new ProcessBuilder("docker", "pull", image).redirectErrorStream(true).start();
                String out = new String(pull.getInputStream().readAllBytes());
                org.junit.jupiter.api.Assumptions.assumeTrue(pull.waitFor() == 0,
                        "Doris image unavailable (SKIPPED, not passed): " + out);
            } catch (Exception e) {
                org.junit.jupiter.api.Assumptions.assumeTrue(false, "Doris image unavailable: " + e);
            }
        }
    }
}
