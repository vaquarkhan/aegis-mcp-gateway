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
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code adopters/configurations/adapters/nifi/nifi.yaml} against a real Apache NiFi 1.28.1 container
 * (HTTP single-user mode) through both front doors. Start/stop carry NiFi optimistic-lock revision
 * fields supplied by the caller after reading the process group.
 */
class NifiFrontDoorsTest {

    private static final String USER = "admin";
    private static final String PASS = "ctsBtRBKHR5yVJ6K7KyF";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Pattern ROOT_ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern REV_VER = Pattern.compile("\"version\"\\s*:\\s*(\\d+)");

    private static DockerContainer nifi;
    private static int port;
    private static String rootGroupId;

    @TempDir
    Path dir;

    @BeforeAll
    static void startNifi() throws Exception {
        DockerContainer.requireDocker();
        port = JavaServer.freePort();
        Path log = Path.of("target", "nifi-server", "docker.log").toAbsolutePath();
        // Force HTTP (not the image default HTTPS) and single-user credentials.
        nifi = DockerContainer.start("aegis-val-nifi", "apache/nifi:1.28.1",
                List.of("-p", "127.0.0.1:" + port + ":8080",
                        "-e", "NIFI_WEB_HTTP_PORT=8080",
                        "-e", "NIFI_WEB_HTTPS_PORT=",
                        "-e", "SINGLE_USER_CREDENTIALS_USERNAME=" + USER,
                        "-e", "SINGLE_USER_CREDENTIALS_PASSWORD=" + PASS,
                        "-e", "NIFI_SENSITIVE_PROPS_KEY=aegis-nifi-sensitive-props-key-32b"),
                log);
        nifi.awaitReady(() -> about().contains("\"version\""), Duration.ofMinutes(5), "NiFi 1.28.1 API");
        String root = get("/flow/process-groups/root");
        Matcher m = ROOT_ID.matcher(root);
        assertTrue(m.find(), "root process group id in " + root);
        rootGroupId = m.group(1);
    }

    @AfterAll
    static void stopNifi() throws Exception {
        if (nifi != null) {
            nifi.close();
        }
    }

    @ParameterizedTest(name = "nifi through {0}")
    @ValueSource(strings = {FrontDoor.OWN_HOST, FrontDoor.KNOX})
    void governsARealNifi(String door) throws Exception {
        String url = "http://127.0.0.1:" + port + "/nifi-api";
        AdopterScenario scenario = AdopterScenario.of("nifi",
                AdopterScenario.canonical("adapters", "nifi", "nifi.yaml"),
                yaml -> yaml.replace("http://nifi:8080/nifi-api", url)
                        .replace("allowCidrs: [10.0.0.0/8]", "allowCidrs: [127.0.0.0/8]"),
                Map.of(Persona.READER, List.of("nifi-users"),
                        Persona.OPERATOR, List.of("nifi-operators"),
                        Persona.APPROVER, List.of("nifi-approvers")),
                Map.of("NIFI_API_USER", USER, "NIFI_API_PASSWORD", PASS));

        try (FrontDoor d = FrontDoor.open(door, scenario, dir)) {
            d.note("apache/nifi:1.28.1 HTTP on " + url + "; root group " + rootGroupId);
            GovernedFlow.read(d, "nifi_get_about", Map.of(), "version");
            GovernedFlow.read(d, "nifi_list_process_groups", Map.of(), rootGroupId);
            GovernedFlow.read(d, "nifi_get_process_group", Map.of("groupId", rootGroupId), rootGroupId);

            // Ensure RUNNING so STOP is a real state change; then STOP with a fresh revision.
            ensureRunning();
            String before = get("/process-groups/" + rootGroupId);
            Map<String, Object> stopArgs = revisionArgs(rootGroupId, before);
            GovernedFlow.destructive(d, "nifi_stop_process_group", stopArgs,
                    () -> assertTrue(!get("/process-groups/" + rootGroupId).isBlank(), "pre-stop engine reachable"),
                    () -> {
                        String after = get("/process-groups/" + rootGroupId);
                        assertTrue(after.contains("STOPPED") || after.contains("\"runningCount\":0")
                                        || after.contains("\"stoppedCount\""),
                                "stop applied: " + after);
                        d.note("NiFi stop issued for root group " + rootGroupId);
                    });
        }
    }

    private static void ensureRunning() throws Exception {
        String body = get("/process-groups/" + rootGroupId);
        Map<String, Object> args = revisionArgs(rootGroupId, body);
        String payload = "{\"id\":\"" + rootGroupId + "\",\"state\":\"RUNNING\","
                + "\"disconnectedNodeAcknowledged\":true,\"revision\":{\"version\":"
                + args.get("revisionVersion") + ",\"clientId\":\"" + args.get("revisionClientId") + "\"}}";
        HTTP.send(authed(HttpRequest.newBuilder(URI.create(api() + "/flow/process-groups/" + rootGroupId))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(payload))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, Object> revisionArgs(String groupId, String json) {
        Matcher v = REV_VER.matcher(json);
        int version = v.find() ? Integer.parseInt(v.group(1)) : 0;
        return Map.of("groupId", groupId, "revisionVersion", version,
                "revisionClientId", UUID.randomUUID().toString());
    }

    private static String about() throws Exception {
        return get("/flow/about");
    }

    private static String get(String path) throws Exception {
        HttpResponse<String> r = HTTP.send(authed(HttpRequest.newBuilder(URI.create(api() + path))
                .timeout(Duration.ofSeconds(20)).GET()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        return r.body();
    }

    private static String api() {
        return "http://127.0.0.1:" + port + "/nifi-api";
    }

    private static HttpRequest.Builder authed(HttpRequest.Builder b) {
        return b.header("Authorization", "Basic " + Base64.getEncoder()
                .encodeToString((USER + ":" + PASS).getBytes(StandardCharsets.UTF_8)));
    }
}
