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
package io.github.vaquarkhan.aegis.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.vaquarkhan.aegis.engine.audit.AuditChainVerifier;
import io.github.vaquarkhan.aegis.engine.json.Json;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two own-host gateway PROCESSES (separate JVMs started with {@code Main}, as an operator runs two
 * replicas) sharing one {@code state.type: postgres} namespace on a real PostgreSQL server:
 * <ul>
 *   <li>an approval requested on replica A is decided on replica B;</li>
 *   <li>a destructive call approved that way executes once on B, and the same approval token and dry-run
 *       receipt are refused when replayed on A;</li>
 *   <li>a per-caller rate window is one window across both replicas.</li>
 * </ul>
 * The backend is a local HTTP endpoint that counts calls; what is under test is the shared governance
 * state, not a product API. Runs only with {@code -Ppostgres-integration} and {@code AEGIS_POSTGRES_URL},
 * {@code AEGIS_POSTGRES_USER} and {@code AEGIS_POSTGRES_PASSWORD} set; skipped, never passed, otherwise.
 */
class TwoGatewayProcessesPostgresIT {

    static final String KEY = "two-replica-shared-signing-key-0123456789abcdef";
    static final Map<String, String> TOKENS = Map.of(
            "OPERATOR_TOKEN", "operator-token-0123456789",
            "APPROVER_TOKEN", "approver-token-0123456789");

    @TempDir
    Path dir;

    HttpServer backend;
    final AtomicInteger deletes = new AtomicInteger();
    final AtomicInteger reads = new AtomicInteger();
    final List<Process> replicas = new ArrayList<>();
    final HttpClient http = HttpClient.newHttpClient();
    String namespace;
    String url;
    String user;
    String password;

    @BeforeEach
    void requireRealPostgresAndStartBackend() throws Exception {
        url = System.getenv("AEGIS_POSTGRES_URL");
        user = System.getenv("AEGIS_POSTGRES_USER");
        password = System.getenv("AEGIS_POSTGRES_PASSWORD");
        Assumptions.assumeTrue(url != null && user != null && password != null,
                "AEGIS_POSTGRES_URL, AEGIS_POSTGRES_USER and AEGIS_POSTGRES_PASSWORD are required (skipped, never passed)");
        namespace = "it-two-replicas-" + UUID.randomUUID();
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/items", ex -> {
            if ("DELETE".equals(ex.getRequestMethod())) {
                deletes.incrementAndGet();
            } else {
                reads.incrementAndGet();
            }
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        backend.start();
    }

    @AfterEach
    void stopEverything() throws Exception {
        for (Process p : replicas) {
            p.destroy();
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        }
        if (backend != null) {
            backend.stop(0);
        }
        if (namespace != null && url != null) {
            try (Connection c = DriverManager.getConnection(url, user, password)) {
                for (String table : List.of("aegis_state", "aegis_window")) {
                    try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE namespace = ?")) {
                        ps.setString(1, namespace);
                        ps.executeUpdate();
                    }
                }
            }
        }
    }

    @Test
    void approvalsReceiptsAndRateWindowsAreSharedAcrossTwoGatewayProcesses() throws Exception {
        int a = startReplica("a");
        int b = startReplica("b");

        // 1. The operator dry-runs the destructive call on replica A: a receipt and an approval request.
        Map<String, Object> dry = call(a, "OPERATOR_TOKEN", "delete_item", Map.of("id", "42"),
                Map.of("io.github.vaquarkhan.aegis/dryRun", true));
        Map<String, Object> dryDecision = decision(dry);
        String receipt = (String) dryDecision.get("dryRunReceipt");
        String requestId = (String) dryDecision.get("approvalRequestId");
        assertNotNull(receipt, "dry run on A issued a receipt: " + dry);
        assertNotNull(requestId, "dry run on A opened an approval request: " + dry);
        assertEquals(0, deletes.get(), "a dry run never reaches the backend");

        // 2. The approver decides it on replica B: B can only see it through the shared store.
        HttpResponse<String> approved = admin(b, "APPROVER_TOKEN", "POST", "/aegis/v1/approvals/" + requestId + "/approve");
        assertEquals(200, approved.statusCode(), approved.body());

        // 3. The operator collects the token from replica A.
        HttpResponse<String> collected = admin(a, "OPERATOR_TOKEN", "GET", "/aegis/v1/approvals/" + requestId);
        assertEquals(200, collected.statusCode(), collected.body());
        String token = (String) Json.parseObject(collected.body()).get("token");
        assertNotNull(token, collected.body());

        // 4. Executed on replica B: allowed, backend called once.
        Map<String, Object> control = Map.of("io.github.vaquarkhan.aegis/approvalToken", token,
                "io.github.vaquarkhan.aegis/dryRunReceipt", receipt);
        Map<String, Object> run = call(b, "OPERATOR_TOKEN", "delete_item", Map.of("id", "42"), control);
        assertEquals("ALLOWED", decision(run).get("outcome"), String.valueOf(run));
        assertEquals(1, deletes.get(), "the approved delete ran exactly once");

        // 5. Replayed on replica A: the single-use nonce was consumed in the shared store.
        Map<String, Object> replay = call(a, "OPERATOR_TOKEN", "delete_item", Map.of("id", "42"), control);
        Map<String, Object> replayDecision = decision(replay);
        assertEquals("APPROVAL_REQUIRED", replayDecision.get("code"), String.valueOf(replay));
        assertTrue(String.valueOf(replayDecision.get("reason")).contains("already been used"), String.valueOf(replay));
        assertEquals(1, deletes.get(), "the replay on the other replica never reached the backend");

        // 6. One rate window (3 per caller per tool per minute) across both replicas.
        List<String> outcomes = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            outcomes.add(String.valueOf(decision(call(i % 2 == 0 ? a : b, "OPERATOR_TOKEN", "list_items", Map.of(), Map.of()))
                    .getOrDefault("code", "ALLOWED")));
        }
        assertEquals(List.of("ALLOWED", "ALLOWED", "ALLOWED", "RATE_LIMITED"), outcomes,
                "three calls spread over A and B use the whole shared window");
        assertEquals(3, reads.get(), "the rate-limited call never reached the backend");

        // Each replica kept its own intact journal.
        for (String r : List.of("a", "b")) {
            AuditChainVerifier.Report report = AuditChainVerifier.verify(dir.resolve("audit-" + r + ".jsonl"));
            assertTrue(report.ok(), r + ": " + report.error());
        }
    }

    private int startReplica(String name) throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        Map<String, Object> config = new LinkedHashMap<>();
        // The same gateway name and signing key on both replicas: approvals and receipts are bound to them.
        config.put("gateway", Map.of("name", "aegis-shared"));
        config.put("access", Map.of("writeGroups", List.of("ops"), "approverGroups", List.of("approvers")));
        config.put("approval", Map.of("keyEnv", "AEGIS_SIGNING_KEY"));
        config.put("policy", Map.of("rules", List.of(Map.of("id", "ops", "groups", List.of("ops"),
                "classes", List.of("READ", "DESTRUCTIVE")))));
        config.put("egress", Map.of("allowCidrs", List.of("127.0.0.1/32")));
        config.put("rateLimits", Map.of("perCallerPerToolPerMinute", 3L));
        config.put("state", Map.of("type", "postgres", "settings", Map.of("jdbcUrl", url, "usernameEnv", "PG_USER",
                "passwordEnv", "PG_PASSWORD", "namespace", namespace)));
        config.put("audit", Map.of("file", "audit-" + name + ".jsonl"));
        config.put("sources", List.of(Map.of("id", "inventory", "type", "http",
                "baseUrl", "http://127.0.0.1:" + backend.getAddress().getPort(),
                "tools", List.of(
                        Map.of("name", "list_items", "description", "List items.", "class", "READ",
                                "request", Map.of("path", "/items")),
                        Map.of("name", "delete_item", "description", "Delete one item.", "class", "DESTRUCTIVE",
                                "inputSchema", Map.of("type", "object",
                                        "properties", Map.of("id", Map.of("type", "string", "maxLength", 64L)),
                                        "required", List.of("id"), "additionalProperties", false),
                                "request", Map.of("method", "DELETE", "path", "/items/{id}"),
                                "resources", List.of(Map.of("type", "inventory:item", "pointer", "/id")))))));
        config.put("host", Map.of(
                "listen", Map.of("host", "127.0.0.1", "port", (long) port),
                "identity", Map.of("type", "static", "static", Map.of("tokens", List.of(
                        Map.of("tokenEnv", "OPERATOR_TOKEN", "actor", "olga", "groups", List.of("ops")),
                        Map.of("tokenEnv", "APPROVER_TOKEN", "actor", "bob", "groups", List.of("approvers")))))));
        Path file = dir.resolve("replica-" + name + ".json");
        Files.writeString(file, Json.write(config));

        List<String> cmd = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), Main.class.getName(), "--config", file.toString());
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true)
                .redirectOutput(dir.resolve("replica-" + name + ".log").toFile());
        pb.environment().put("AEGIS_SIGNING_KEY", KEY);
        pb.environment().put("PG_USER", user);
        pb.environment().put("PG_PASSWORD", password);
        pb.environment().putAll(TOKENS);
        Process p = pb.start();
        replicas.add(p);
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            if (!p.isAlive()) {
                throw new IllegalStateException("replica " + name + " exited: " + Files.readString(dir.resolve("replica-" + name + ".log")));
            }
            try {
                if (http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/healthz")).GET().build(),
                        HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
                    return port;
                }
            } catch (java.io.IOException notYet) {
                // still starting
            }
            Thread.sleep(300);
        }
        throw new IllegalStateException("replica " + name + " did not start: " + Files.readString(dir.resolve("replica-" + name + ".log")));
    }

    private Map<String, Object> call(int port, String tokenEnv, String tool, Map<String, Object> args,
                                     Map<String, Object> control) throws Exception {
        Map<String, Object> meta = new LinkedHashMap<>(control);
        meta.put("io.modelcontextprotocol/protocolVersion", "2026-07-28");
        meta.put("io.modelcontextprotocol/clientCapabilities", Map.of());
        Map<String, Object> body = Map.of("jsonrpc", "2.0", "id", 1L, "method", "tools/call",
                "params", Map.of("name", tool, "arguments", args, "_meta", meta));
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + TOKENS.get(tokenEnv))
                .header("MCP-Protocol-Version", "2026-07-28").header("Mcp-Method", "tools/call").header("Mcp-Name", tool)
                .POST(HttpRequest.BodyPublishers.ofByteArray(Json.writeBytes(body))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        return Json.parseObject(r.body());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> decision(Map<String, Object> reply) {
        Map<String, Object> result = (Map<String, Object>) reply.get("result");
        Map<String, Object> meta = (Map<String, Object>) result.get("_meta");
        return (Map<String, Object>) meta.get("io.github.vaquarkhan.aegis/decision");
    }

    private HttpResponse<String> admin(int port, String tokenEnv, String method, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + TOKENS.get(tokenEnv))
                .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }
}
