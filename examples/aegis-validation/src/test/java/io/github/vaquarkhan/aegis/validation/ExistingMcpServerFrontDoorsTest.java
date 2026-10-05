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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * examples/adopters/existing-mcp-server: the canonical
 * {@code adopters/configurations/existing-mcp-server/existing-mcp-server.yaml} fronting two MCP servers Aegis
 * shares no code with, through both front doors:
 * <ul>
 *   <li>{@code engine-mcp} (Streamable HTTP): a project's own server written with the official MCP Python
 *       SDK 2.2.0 ({@code examples/adopters/existing-mcp-server/engine_mcp_server.py});</li>
 *   <li>{@code local-files} (stdio): the official {@code @modelcontextprotocol/server-filesystem}
 *       2026.8.31, launched by Aegis as a subprocess.</li>
 * </ul>
 * The shipped profile negotiates with {@code era: AUTO}; a further run forces {@code era: LEGACY} to cover
 * the 2025 initialize handshake with a server-assigned session.
 *
 * <p>Needs {@code AEGIS_MCP_PYTHON}, {@code AEGIS_NODE} and {@code AEGIS_MCP_FILESYSTEM_JS}
 * ({@code examples/adopters/existing-mcp-server/setup.ps1} prints them). Without them it is skipped, never passed.
 */
class ExistingMcpServerFrontDoorsTest {

    private static final String ENGINE_TOKEN = "aegis-validation-engine-credential";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static JavaServer engine;
    private static int port;
    private static Path observed;
    private static Path reports;
    private static String node;
    private static String filesystemJs;

    @TempDir
    Path dir;

    @BeforeAll
    static void startEngineMcpServer() throws Exception {
        String python = System.getenv("AEGIS_MCP_PYTHON");
        node = System.getenv("AEGIS_NODE");
        filesystemJs = System.getenv("AEGIS_MCP_FILESYSTEM_JS");
        Assumptions.assumeTrue(python != null && node != null && filesystemJs != null,
                "AEGIS_MCP_PYTHON, AEGIS_NODE and AEGIS_MCP_FILESYSTEM_JS are not set: run "
                        + "examples/adopters/existing-mcp-server/setup.ps1 (SKIPPED, not passed)");
        Path work = Path.of("target", "existing-mcp-server").toAbsolutePath();
        Files.createDirectories(work);
        observed = work.resolve("engine-observed.jsonl");
        Files.deleteIfExists(observed);
        reports = Files.createDirectories(work.resolve("reports"));
        Files.writeString(reports.resolve("q3-summary.txt"), "Q3 summary: orders up 4 percent\n");
        Files.deleteIfExists(reports.resolve("injected.txt"));

        Path script = Path.of("..", "adopters", "existing-mcp-server", "engine_mcp_server.py").toAbsolutePath().normalize();
        port = JavaServer.freePort();
        engine = JavaServer.process(List.of(python, script.toString()),
                Map.of("ENGINE_MCP_PORT", Integer.toString(port), "ENGINE_MCP_TOKEN", ENGINE_TOKEN,
                        "ENGINE_MCP_OBSERVED", observed.toString(), "PYTHONUNBUFFERED", "1"),
                work.resolve("engine-mcp-server.log"));
        engine.awaitReady(() -> state().contains("job-orders-etl"), Duration.ofSeconds(60), "engine MCP server");
    }

    @AfterAll
    static void stopEngineMcpServer() throws Exception {
        if (engine != null) {
            engine.close();
        }
    }

    private AdopterScenario scenario(String adopter, String era) throws Exception {
        String fwd = reports.toString().replace('\\', '/');
        return AdopterScenario.of(adopter,
                AdopterScenario.canonical("existing-mcp-server", "existing-mcp-server.yaml"),
                yaml -> {
                    String y = yaml.replace("http://engine-mcp:9000/mcp", "http://127.0.0.1:" + port + "/mcp")
                            .replace("command: [node, /opt/mcp/filesystem-server/index.js, /data/reports]",
                                    "command: [\"" + node.replace('\\', '/') + "\", \"" + filesystemJs.replace('\\', '/')
                                            + "\", \"" + fwd + "\"]")
                            .replace("allowCidrs: [10.0.0.0/8]", "allowCidrs: [127.0.0.0/8]");
                    return "AUTO".equals(era) ? y : y.replace("era: AUTO", "era: " + era);
                },
                Map.of(Persona.READER, List.of("analysts"),
                        Persona.OPERATOR, List.of("operators"),
                        Persona.APPROVER, List.of("approvers")),
                Map.of("ENGINE_MCP_TOKEN", ENGINE_TOKEN));
    }

    @ParameterizedTest(name = "existing MCP server through {0}")
    @ValueSource(strings = {FrontDoor.OWN_HOST, FrontDoor.KNOX})
    void frontsRealMcpServersUnchanged(String door) throws Exception {
        String job = "job-scratch-" + door.replace("-", "");
        long before = Files.exists(observed) ? Files.readAllLines(observed).size() : 0;
        String file = reports.resolve("q3-summary.txt").toString().replace('\\', '/');
        String injected = reports.resolve("injected.txt").toString().replace('\\', '/');

        try (FrontDoor d = FrontDoor.open(door, scenario("existing-mcp-server", "AUTO"), dir)) {
            d.note("engine-mcp: official MCP Python SDK 2.2.0 on 127.0.0.1:" + port
                    + "; local-files: @modelcontextprotocol/server-filesystem 2026.8.31 over stdio, allowed dir " + reports.getFileName());
            GovernedFlow.read(d, "engine_list_jobs", Map.of(), "job-orders-etl");
            GovernedFlow.read(d, "read_text_file", Map.of("path", file), "orders up 4 percent");
            // unlistedTools: HIDE on local-files: the server's write tools do not exist for any caller.
            GovernedFlow.refused(d, "a hidden stdio tool (unlistedTools: HIDE)", Persona.OPERATOR, "write_file",
                    Map.of("path", injected, "content", "x"), "NOT_EXPOSED",
                    () -> assertFalse(Files.exists(reports.resolve("injected.txt")), "nothing was written"));
            // engine-mcp lists no class for restart_all_jobs, so it is DESTRUCTIVE until an operator classifies it.
            GovernedFlow.refused(d, "an unclassified tool defaults to DESTRUCTIVE", Persona.OPERATOR, "engine_restart_all_jobs",
                    Map.of(), "APPROVAL_REQUIRED",
                    () -> assertFalse(observedSince(before).contains("\"tool\": \"restart_all_jobs\""), "never reached the server"));
            GovernedFlow.destructive(d, "engine_delete_job", Map.of("jobId", job),
                    () -> assertTrue(state().contains(job), "denied and previewed calls kept " + job),
                    () -> {
                        String now = state();
                        assertFalse(now.contains(job), job + " is gone: " + now);
                        assertTrue(now.contains("job-orders-etl"), "other jobs untouched: " + now);
                        d.note("the engine MCP server no longer has " + job);
                    });
            String seen = observedSince(before);
            assertFalse(seen.contains("\"authorized\": false"), "every request carried the gateway's own credential");
            assertTrue(seen.contains("\"protocolVersion\": \"2026-07-28\""), "era AUTO negotiated 2026-07-28: " + seen);
            assertEquals(1, count(seen, "\"tool\": \"delete_job\""), "delete_job reached the server exactly once");
            d.note("engine-mcp saw: every request authorized with the gateway credential, protocol 2026-07-28, delete_job once");
        }
    }

    /** The 2025 handshake (initialize, then a server-assigned Mcp-Session-Id) through the own host. */
    @Test
    void legacy2025HandshakeThroughOwnHost() throws Exception {
        long before = Files.exists(observed) ? Files.readAllLines(observed).size() : 0;
        String job = "job-scratch-legacy";
        try (FrontDoor d = FrontDoor.open(FrontDoor.OWN_HOST, scenario("existing-mcp-server-legacy-2025", "LEGACY"), dir)) {
            d.note("same profile with era: LEGACY against the same engine MCP server");
            GovernedFlow.read(d, "engine_list_jobs", Map.of(), "job-orders-etl");
            GovernedFlow.destructive(d, "engine_delete_job", Map.of("jobId", job),
                    () -> assertTrue(state().contains(job), "denied and previewed calls kept " + job),
                    () -> assertFalse(state().contains(job), job + " is gone"));
            String seen = observedSince(before);
            assertTrue(seen.contains("\"protocolVersion\": \"2025-"), "a 2025 protocol version after initialize: " + seen);
            assertTrue(seen.contains("\"session\": true"), "requests carried the server-assigned Mcp-Session-Id: " + seen);
            assertFalse(seen.contains("\"authorized\": false"), "every request carried the gateway's own credential");
            d.note("engine-mcp saw: a 2025 protocol version and an Mcp-Session-Id on the session's requests");
        }
    }

    private static String state() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/state"))
                .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + ENGINE_TOKEN).GET().build();
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new IllegalStateException("state HTTP " + r.statusCode());
        }
        return r.body();
    }

    private static String observedSince(long line) throws Exception {
        List<String> all = Files.readAllLines(observed, StandardCharsets.UTF_8);
        return String.join("\n", all.subList((int) Math.min(line, all.size()), all.size()));
    }

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
