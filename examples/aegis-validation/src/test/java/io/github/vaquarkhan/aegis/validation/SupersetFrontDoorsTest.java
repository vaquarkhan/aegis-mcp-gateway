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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code adopters/configurations/adapters/superset/superset.yaml} against a real Apache Superset 4.0.2
 * container through both front doors. The gateway holds a bearer JWT obtained outside Aegis via
 * {@code POST /api/v1/security/login}.
 */
class SupersetFrontDoorsTest {

    private static final String ADMIN = "admin";
    private static final String PASS = "admin";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Pattern ACCESS = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern CHART_ID = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");

    private static DockerContainer superset;
    private static int port;
    private static String token;
    private static String ownHostVictim;
    private static String knoxVictim;

    @TempDir
    Path dir;

    @BeforeAll
    static void startSuperset() throws Exception {
        DockerContainer.requireDocker();
        port = JavaServer.freePort();
        Path log = Path.of("target", "superset-server", "docker.log").toAbsolutePath();
        Path pythonpath = Path.of("target", "superset-server", "pythonpath").toAbsolutePath();
        Files.createDirectories(pythonpath);
        Files.writeString(pythonpath.resolve("superset_config.py"),
                "WTF_CSRF_ENABLED = False\nTALISMAN_ENABLED = False\n");
        // Official image entrypoint waits for SUPERSET_SECRET_KEY; we bootstrap admin + examples via docker exec.
        superset = DockerContainer.start("aegis-val-superset", "apache/superset:4.0.2",
                List.of("-p", "127.0.0.1:" + port + ":8088",
                        "-v", pythonpath + ":/app/pythonpath",
                        "-e", "PYTHONPATH=/app/pythonpath",
                        "-e", "SUPERSET_SECRET_KEY=aegis-superset-validation-secret-key-32b",
                        "-e", "SUPERSET_LOAD_EXAMPLES=yes"),
                log);
        superset.awaitReady(() -> {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(base() + "/health"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200;
        }, Duration.ofMinutes(4), "Superset health");
        bootstrapAdmin();
        token = login();
        seedCharts();
        assertTrue(ownHostVictim != null && knoxVictim != null, "need two disposable charts");
    }

    @AfterAll
    static void stopSuperset() throws Exception {
        if (superset != null) {
            superset.close();
        }
    }

    @ParameterizedTest(name = "superset through {0}")
    @ValueSource(strings = {FrontDoor.OWN_HOST, FrontDoor.KNOX})
    void governsARealSuperset(String door) throws Exception {
        String victim = FrontDoor.OWN_HOST.equals(door) ? ownHostVictim : knoxVictim;
        assertTrue(listCharts().contains("\"id\": " + victim) || listCharts().contains("\"id\":" + victim),
                "chart " + victim + " present");

        String url = base();
        AdopterScenario scenario = AdopterScenario.of("superset",
                AdopterScenario.canonical("adapters", "superset", "superset.yaml"),
                yaml -> yaml.replace("http://superset:8088", url)
                        .replace("allowCidrs: [10.0.0.0/8]", "allowCidrs: [127.0.0.0/8]"),
                Map.of(Persona.READER, List.of("superset-users"),
                        Persona.OPERATOR, List.of("superset-operators"),
                        Persona.APPROVER, List.of("superset-approvers")),
                Map.of("SUPERSET_ACCESS_TOKEN", token));

        try (FrontDoor d = FrontDoor.open(door, scenario, dir)) {
            d.note("apache/superset:4.0.2 on " + url + "; delete chart " + victim);
            GovernedFlow.read(d, "superset_list_databases", Map.of(), "result");
            GovernedFlow.read(d, "superset_list_datasets", Map.of(), "result");
            GovernedFlow.read(d, "superset_list_charts", Map.of(), victim);
            GovernedFlow.destructive(d, "superset_delete_chart", Map.of("chartId", victim),
                    () -> assertTrue(chartExists(victim), "chart " + victim + " still present"),
                    () -> {
                        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                        boolean gone = false;
                        while (System.nanoTime() < deadline) {
                            if (!chartExists(victim)) {
                                gone = true;
                                break;
                            }
                            Thread.sleep(500);
                        }
                        assertTrue(gone, "chart " + victim + " deleted");
                        d.note("Superset deleted chart " + victim);
                    });
        }
    }

    private static void bootstrapAdmin() throws Exception {
        // Idempotent bootstrap: db upgrade, create admin, init. Examples may already be loading.
        execIn("superset db upgrade");
        execIn("superset fab create-admin --username " + ADMIN + " --firstname Aegis --lastname Admin "
                + "--email aegis@example.com --password " + PASS);
        execIn("superset init");
        try {
            execIn("superset load-examples --force");
        } catch (Exception ignored) {
            // examples optional if image already seeded; charts can be created via API
        }
    }

    private static void seedCharts() throws Exception {
        // Prefer existing example charts; otherwise create two empty chart stubs via API if possible.
        List<String> ids = chartIds(listCharts());
        if (ids.size() >= 2) {
            ownHostVictim = ids.get(0);
            knoxVictim = ids.get(1);
            return;
        }
        // Minimal chart create against the examples database if present.
        for (int i = 0; i < 2; i++) {
            String body = "{\"slice_name\":\"aegis_scratch_" + i + "\",\"viz_type\":\"table\","
                    + "\"datasource_id\":1,\"datasource_type\":\"table\",\"params\":\"{}\"}";
            HttpResponse<String> r = HTTP.send(authed(HttpRequest.newBuilder(URI.create(base() + "/api/v1/chart/"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))).build(), HttpResponse.BodyHandlers.ofString());
            Matcher m = CHART_ID.matcher(r.body());
            if (m.find()) {
                if (ownHostVictim == null) {
                    ownHostVictim = m.group(1);
                } else {
                    knoxVictim = m.group(1);
                }
            }
        }
        if (ownHostVictim == null || knoxVictim == null) {
            ids = chartIds(listCharts());
            if (ids.size() >= 2) {
                ownHostVictim = ids.get(0);
                knoxVictim = ids.get(1);
            }
        }
    }

    private static String login() throws Exception {
        String body = "{\"username\":\"" + ADMIN + "\",\"password\":\"" + PASS
                + "\",\"provider\":\"db\",\"refresh\":true}";
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(base() + "/api/v1/security/login"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(r.statusCode() == 200, "login: " + r.statusCode() + " " + r.body());
        Matcher m = ACCESS.matcher(r.body());
        assertTrue(m.find(), "access_token in " + r.body());
        return m.group(1);
    }

    private static boolean chartExists(String id) throws Exception {
        HttpResponse<String> r = HTTP.send(authed(HttpRequest.newBuilder(URI.create(base() + "/api/v1/chart/" + id))
                .timeout(Duration.ofSeconds(15)).GET()).build(), HttpResponse.BodyHandlers.ofString());
        return r.statusCode() == 200;
    }

    private static String listCharts() throws Exception {
        HttpResponse<String> r = HTTP.send(authed(HttpRequest.newBuilder(URI.create(base() + "/api/v1/chart/?q=(page_size:100)"))
                .timeout(Duration.ofSeconds(20)).GET()).build(), HttpResponse.BodyHandlers.ofString());
        return r.body();
    }

    private static List<String> chartIds(String body) {
        List<String> ids = new java.util.ArrayList<>();
        Matcher m = CHART_ID.matcher(body);
        while (m.find()) {
            ids.add(m.group(1));
        }
        return ids;
    }

    private static String base() {
        return "http://127.0.0.1:" + port;
    }

    private static HttpRequest.Builder authed(HttpRequest.Builder b) {
        return b.header("Authorization", "Bearer " + token);
    }

    private static void execIn(String shellCmd) throws Exception {
        Process p = new ProcessBuilder("docker", "exec", "aegis-val-superset", "bash", "-lc", shellCmd)
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        Files.writeString(Path.of("target", "superset-server", "bootstrap-"
                + shellCmd.hashCode() + ".log").toAbsolutePath(), out);
        if (!p.waitFor(5, java.util.concurrent.TimeUnit.MINUTES) || p.exitValue() != 0) {
            // create-admin fails if the user already exists; treat as soft for that command only.
            if (shellCmd.contains("create-admin") && out.toLowerCase().contains("exist")) {
                return;
            }
            throw new IllegalStateException("docker exec failed: " + shellCmd + "\n" + out);
        }
    }
}
