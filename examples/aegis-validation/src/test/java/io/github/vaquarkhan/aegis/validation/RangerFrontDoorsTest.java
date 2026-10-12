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
import io.github.vaquarkhan.aegis.validation.frontdoor.JavaServer;
import io.github.vaquarkhan.aegis.validation.frontdoor.GovernedFlow;
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
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * examples/adopters/ranger: the canonical adapter profile against the official Apache Ranger 2.9.0 Admin
 * image and its official database image, through both front doors. The Admin public API is the product;
 * the test logs in as the image's admin user and seeds one HDFS service plus a scratch policy.
 */
class RangerFrontDoorsTest {

    private static final String IMAGE = "apache/ranger:2.9.0";
    private static final String DB_IMAGE = "apache/ranger-db:2.9.0";
    private static final String NET = "aegis-val-ranger-net";
    private static final String DB_NAME = "aegis-val-ranger-db";
    private static final String ADMIN_NAME = "aegis-val-ranger";
    private static final String USER = "admin";
    private static final String PASSWORD = "rangerR0cks!";
    private static final String SERVICE = "aegis_hdfs";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Pattern POLICY_ID = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");

    private static DockerContainer database;
    private static DockerContainer admin;
    private static int port;

    @TempDir
    Path dir;

    @BeforeAll
    static void startRanger() throws Exception {
        execQuiet(List.of("docker", "rm", "-f", ADMIN_NAME, DB_NAME));
        execQuiet(List.of("docker", "network", "rm", NET));
        execQuiet(List.of("docker", "network", "create", NET));
        Path log = Path.of("target", "ranger-server").toAbsolutePath();
        database = DockerContainer.start(DB_NAME, DB_IMAGE, List.of(
                "--network", NET,
                "--hostname", "ranger-db.rangernw",
                "-e", "POSTGRES_PASSWORD=" + PASSWORD,
                "-e", "RANGER_DB_USER=rangeradmin",
                "-e", "RANGER_DB_PASSWORD=" + PASSWORD), log.resolve("db.log"));
        database.awaitReady(RangerFrontDoorsTest::databaseReady, Duration.ofMinutes(3), "Ranger database");
        port = JavaServer.freePort();
        admin = DockerContainer.start(ADMIN_NAME, IMAGE, List.of(
                "--network", NET,
                "--hostname", "ranger-admin.rangernw",
                "-p", "127.0.0.1:" + port + ":6080",
                "-e", "POSTGRES_PASSWORD=" + PASSWORD,
                "-e", "RANGER_DB_USER=rangeradmin",
                "-e", "RANGER_DB_PASSWORD=" + PASSWORD), log.resolve("admin.log"));
        admin.awaitReady(() -> api("GET", "/service/public/v2/api/service", null).statusCode() == 200,
                Duration.ofMinutes(8), "Ranger Admin");
        HttpResponse<String> created = api("POST", "/service/public/v2/api/service", """
                {"name":"%s","type":"hdfs","isEnabled":true,"configs":{"username":"hdfs","password":"hdfs","fs.default.name":"hdfs://localhost:9000","hadoop.security.authentication":"simple","hadoop.security.authorization":"false"}}
                """.formatted(SERVICE));
        assertTrue(created.statusCode() == 200 || created.body().contains(SERVICE), created.body());
    }

    @AfterAll
    static void stopRanger() throws Exception {
        if (admin != null) {
            admin.close();
        }
        if (database != null) {
            database.close();
        }
        execQuiet(List.of("docker", "network", "rm", NET));
    }

    @ParameterizedTest(name = "ranger through {0}")
    @ValueSource(strings = {FrontDoor.OWN_HOST, FrontDoor.KNOX})
    void governsARealRangerAdmin(String door) throws Exception {
        String policyName = "aegis_" + door.replace("-", "");
        String policyId = policyId(api("POST", "/service/public/v2/api/policy", """
                {"service":"%s","name":"%s","policyType":0,"isEnabled":true,"resources":{"path":{"values":["/tmp/%s"],"isRecursive":true}},"policyItems":[{"users":["admin"],"accesses":[{"type":"read","isAllowed":true}]}]}
                """.formatted(SERVICE, policyName, policyName)).body());

        AdopterScenario scenario = AdopterScenario.of("ranger",
                AdopterScenario.canonical("adapters", "ranger", "ranger.yaml"),
                yaml -> yaml.replace("http://ranger-admin:6080", "http://127.0.0.1:" + port)
                        .replace("allowCidrs: [10.0.0.0/8]", "allowCidrs: [127.0.0.0/8]"),
                Map.of(Persona.READER, List.of("ranger-users"),
                        Persona.OPERATOR, List.of("ranger-operators"),
                        Persona.APPROVER, List.of("ranger-approvers")),
                Map.of("RANGER_USER", USER, "RANGER_PASSWORD", PASSWORD));

        try (FrontDoor d = FrontDoor.open(door, scenario, dir)) {
            d.note("official Apache Ranger 2.9.0 Admin on 127.0.0.1:" + port + "; service " + SERVICE + ", policy " + policyId);
            GovernedFlow.read(d, "ranger_list_services", Map.of(), SERVICE);
            GovernedFlow.read(d, "ranger_list_policies", Map.of("service", SERVICE), policyName);
            GovernedFlow.read(d, "ranger_get_policy", Map.of("policyId", policyId), policyName);
            GovernedFlow.destructive(d, "ranger_delete_policy", Map.of("policyId", policyId),
                    () -> assertTrue(api("GET", "/service/public/v2/api/policy/" + policyId, null).statusCode() == 200,
                            "denied and previewed calls did not delete policy " + policyId),
                    () -> {
                        HttpResponse<String> gone = api("GET", "/service/public/v2/api/policy/" + policyId, null);
                        assertTrue(gone.statusCode() == 404 || gone.statusCode() == 400,
                                "policy " + policyId + " still answers " + gone.statusCode() + " " + gone.body());
                        assertTrue(api("GET", "/service/public/v2/api/service", null).body().contains(SERVICE),
                                SERVICE + " still exists");
                        d.note("Ranger Admin no longer has policy " + policyId + " and still has service " + SERVICE);
                    });
        }
    }

    private static boolean databaseReady() {
        try {
            Process process = new ProcessBuilder("docker", "exec", DB_NAME, "pg_isready", "-q", "-U", "postgres")
                    .redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String policyId(String body) {
        Matcher matcher = POLICY_ID.matcher(body);
        assertTrue(matcher.find(), body);
        return matcher.group(1);
    }

    private static HttpResponse<String> api(String method, String path, String json) throws Exception {
        String token = Base64.getEncoder().encodeToString((USER + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Basic " + token)
                .header("Accept", "application/json");
        HttpRequest request = json == null
                ? ("DELETE".equals(method) ? builder.DELETE() : builder.GET()).build()
                : builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(json)).build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void execQuiet(List<String> cmd) {
        try {
            Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            process.waitFor();
        } catch (Exception ignored) {
            // best effort cleanup
        }
    }
}
