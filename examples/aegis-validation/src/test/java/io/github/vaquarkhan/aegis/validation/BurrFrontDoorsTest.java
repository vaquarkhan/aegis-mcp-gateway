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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.validation.frontdoor.AdopterScenario;
import io.github.vaquarkhan.aegis.validation.frontdoor.FrontDoor;
import io.github.vaquarkhan.aegis.validation.frontdoor.GovernedFlow;
import io.github.vaquarkhan.aegis.validation.frontdoor.JavaServer;
import io.github.vaquarkhan.aegis.validation.frontdoor.McpReply;
import io.github.vaquarkhan.aegis.validation.frontdoor.Persona;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * examples/adopters/burr: the canonical {@code adopters/configurations/adapters/burr/burr.yaml} against a real
 * Apache Burr 0.42.0 tracking server ({@code burr.tracking.server.run:app} under uvicorn, as the {@code burr}
 * command runs it) serving traces Burr's own {@code LocalTrackingClient} recorded, through both front doors.
 * The governed write is a {@code MUTATE} (an annotation), and the profile's distinctive control is a READ
 * behind {@code allowedGroups}: an agent trace is a disclosure, so readers do not see it at all.
 *
 * <p>Needs {@code AEGIS_BURR_PYTHON} (examples/adopters/burr/setup.ps1); skipped, never passed, without it.
 */
class BurrFrontDoorsTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static JavaServer tracking;
    private static int port;

    @TempDir
    Path dir;

    @BeforeAll
    static void startTrackingServer() throws Exception {
        String python = System.getenv("AEGIS_BURR_PYTHON");
        Assumptions.assumeTrue(python != null,
                "AEGIS_BURR_PYTHON is not set: run examples/adopters/burr/setup.ps1 (SKIPPED, not passed)");
        Path work = Path.of("target", "burr-server").toAbsolutePath();
        Path store = work.resolve("store");
        if (Files.exists(store)) {
            try (var s = Files.walk(store)) {
                for (Path p : (Iterable<Path>) s.sorted(java.util.Comparator.reverseOrder())::iterator) {
                    Files.delete(p);
                }
            }
        }
        Files.createDirectories(work);
        Path script = Path.of("..", "adopters", "burr", "record_runs.py").toAbsolutePath().normalize();
        JavaServer record = JavaServer.process(List.of(python, script.toString(), store.toString(), "run-ownhost", "run-knox"),
                Map.of("PYTHONUNBUFFERED", "1"), work.resolve("record-runs.log"));
        assertEquals(0, record.awaitExit(Duration.ofSeconds(120)), "recording the Burr runs: " + Files.readString(record.log()));

        port = JavaServer.freePort();
        tracking = JavaServer.process(List.of(python, "-m", "uvicorn", "burr.tracking.server.run:app",
                        "--host", "127.0.0.1", "--port", Integer.toString(port)),
                Map.of("BURR_PATH", store.toString(), "BURR_SERVE_STATIC", "false", "PYTHONUNBUFFERED", "1"),
                work.resolve("burr-tracking-server.log"));
        tracking.awaitReady(() -> get("/api/v0/ready").equals("true"), Duration.ofSeconds(90), "Burr tracking server");
    }

    @AfterAll
    static void stopTrackingServer() throws Exception {
        if (tracking != null) {
            tracking.close();
        }
    }

    @ParameterizedTest(name = "burr through {0}")
    @ValueSource(strings = {FrontDoor.OWN_HOST, FrontDoor.KNOX})
    void governsARealBurrTrackingServer(String door) throws Exception {
        String run = "run-" + door.replace("-", "");
        AdopterScenario scenario = AdopterScenario.of("burr", AdopterScenario.canonical("adapters", "burr", "burr.yaml"),
                yaml -> yaml.replace("http://burr-tracking:7241", "http://127.0.0.1:" + port)
                        .replace("allowCidrs: [10.0.0.0/8]", "allowCidrs: [127.0.0.0/8]"),
                Map.of(Persona.READER, List.of("burr-users"),
                        Persona.OPERATOR, List.of("burr-operators"),
                        Persona.APPROVER, List.of("burr-approvers")),
                Map.of());
        Map<String, Object> trace = Map.of("projectId", "support-agent", "appId", run, "partitionKey", "__none__");

        try (FrontDoor d = FrontDoor.open(door, scenario, dir)) {
            d.note("Burr 0.42.0 tracking server on 127.0.0.1:" + port + ", local backend; project support-agent, run " + run);
            GovernedFlow.read(d, "burr_get_version", Map.of(), "0.42.0");
            GovernedFlow.read(d, "burr_list_projects", Map.of(), "support-agent");
            GovernedFlow.read(d, "burr_list_applications", Map.of("projectId", "support-agent", "partitionKey", "__none__"), run);

            // The agent trace: hidden from readers (allowedGroups), visible to operators.
            GovernedFlow.refused(d, "a reader asks for another run's full trace", Persona.READER, "burr_get_application_trace",
                    trace, "NOT_EXPOSED", () -> { });
            McpReply full = d.call("an operator reads the trace", Persona.OPERATOR, "burr_get_application_trace", trace);
            assertTrue(full.allowed() && full.text().contains("customer 4711"), "the real trace, with its state: " + full.body());

            Map<String, Object> note = Map.of("projectId", "support-agent", "appId", run, "partitionKey", "__none__",
                    "sequenceId", 1, "stepName", "answer_question", "tags", List.of("reviewed"), "thumbsUp", true,
                    "note", "answer matches the refund policy");
            GovernedFlow.mutate(d, "burr_annotate_step", note,
                    () -> assertFalse(annotations().contains("\"app_id\":\"" + run + "\""), "no annotation on " + run + " yet"),
                    () -> {
                        String now = annotations();
                        assertTrue(now.contains("\"app_id\":\"" + run + "\"") && now.contains("answer matches the refund policy"),
                                "the tracking server stored the annotation: " + now);
                        assertEquals(1, now.split("\"app_id\":\"" + run + "\"", -1).length - 1, "exactly one annotation on " + run);
                        d.note("the tracking server stores one annotation on " + run + " step 1");
                    });
        }
    }

    private static String annotations() throws Exception {
        return get("/api/v0/support-agent/annotations");
    }

    private static String get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new IllegalStateException(path + " HTTP " + r.statusCode() + ": " + r.body());
        }
        return r.body();
    }
}
