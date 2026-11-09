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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code adopters/configurations/adapters/skywalking/skywalking.yaml} against a real Apache
 * SkyWalking OAP 9.7.0 container ({@code apache/skywalking-oap-server} with H2 storage) through both
 * front doors. The profile is read-only: OAP GraphQL is a query API.
 */
class SkyWalkingFrontDoorsTest {

    private static final String IMAGE = "apache/skywalking-oap-server:9.7.0";
    private static final DateTimeFormatter WINDOW = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static DockerContainer oap;
    private static int port;

    @TempDir
    Path dir;

    @BeforeAll
    static void startOap() throws Exception {
        DockerContainer.requireDocker();
        port = JavaServer.freePort();
        Path log = Path.of("target", "skywalking-oap", "docker.log").toAbsolutePath();
        oap = DockerContainer.start("aegis-val-skywalking", IMAGE,
                List.of("-p", "127.0.0.1:" + port + ":12800", "-e", "SW_STORAGE=h2"),
                log);
        oap.awaitReady(() -> {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(base() + "/graphql"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"{ version }\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 && r.body().contains("version");
        }, Duration.ofMinutes(4), "SkyWalking OAP GraphQL");
    }

    @AfterAll
    static void stopOap() throws Exception {
        if (oap != null) {
            oap.close();
        }
    }

    @ParameterizedTest(name = "skywalking through {0}")
    @ValueSource(strings = {FrontDoor.OWN_HOST, FrontDoor.KNOX})
    void governsARealSkyWalkingOap(String door) throws Exception {
        LocalDateTime end = LocalDateTime.now();
        LocalDateTime start = end.minusMinutes(5);
        String startWindow = start.format(WINDOW);
        String endWindow = end.format(WINDOW);
        // Fresh OAP has no services; a dummy id still returns empty GraphQL data payloads.
        String service = "aegis-validation";

        AdopterScenario scenario = AdopterScenario.of("skywalking",
                AdopterScenario.canonical("adapters", "skywalking", "skywalking.yaml"),
                yaml -> yaml.replace("http://skywalking-oap:12800", base())
                        .replace("allowCidrs: [10.0.0.0/8]", "allowCidrs: [127.0.0.0/8]"),
                Map.of(Persona.READER, List.of("skywalking-users"),
                        Persona.OPERATOR, List.of("skywalking-operators"),
                        Persona.APPROVER, List.of("skywalking-approvers")),
                Map.of());

        try (FrontDoor d = FrontDoor.open(door, scenario, dir)) {
            d.note(IMAGE + " (SW_STORAGE=h2) GraphQL on " + base()
                    + "; read-only window " + startWindow + ".." + endWindow);
            GovernedFlow.read(d, "skywalking_list_services",
                    Map.of("start", startWindow, "end", endWindow), "getAllServices");
            GovernedFlow.read(d, "skywalking_service_instances",
                    Map.of("service", service, "start", startWindow, "end", endWindow),
                    "getServiceInstances");
            GovernedFlow.read(d, "skywalking_query_traces",
                    Map.of("service", service, "start", startWindow, "end", endWindow), "traces");
        }
    }

    private static String base() {
        return "http://127.0.0.1:" + port;
    }
}
