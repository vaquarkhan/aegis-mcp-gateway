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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code --policy-test}: decisions for each case, from the real configuration, with nothing run. */
class PolicyTestCommandTest {

    @TempDir
    Path dir;

    static final Map<String, String> ENV = Map.of("AEGIS_SIGNING_KEY", "k".repeat(48));

    private record Run(int code, String out, String err) {}

    private static Run cli(String... args) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(args, new ByteArrayInputStream(new byte[0]), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), ENV::get, false);
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void refusesRemoteOpaBeforeItCanReceivePolicyInput() throws Exception {
        Path cfg = Files.writeString(dir.resolve("opa.json"), """
                {"policy":{"opa":{"url":"http://127.0.0.1:1/decision"}},
                 "sources":[{"id":"rest","type":"http","baseUrl":"http://127.0.0.1:1","tools":[
                 {"name":"list","description":"List.","class":"READ","request":{"method":"GET","path":"/"}}]}]}""");
        Path cases = Files.writeString(dir.resolve("opa-cases.json"),
                "{\"cases\":[{\"caller\":{\"actor\":\"alice\"},\"tool\":\"list\"}]}");
        Run result = cli("--policy-test", cfg.toString(), cases.toString());
        assertEquals(Main.BROKEN, result.code(), result.out() + result.err());
        assertTrue(result.err().contains("does not support policy.opa"), result.err());
    }


    @Test
    void refusesMcpAndPluginSourcesBeforeTheyCanDiscoverOrResolveCredentials() throws Exception {
        Path cfg = Files.writeString(dir.resolve("remote.json"), """
                {"secrets":{"source":"vault","address":"http://127.0.0.1:1","tokenEnv":"UNUSED"},
                 "sources":[{"id":"remote","type":"mcp","settings":{"transport":"http","url":"http://127.0.0.1:1"}}]}""");
        Path cases = Files.writeString(dir.resolve("remote-cases.json"),
                "{\"cases\":[{\"caller\":{\"actor\":\"alice\"},\"tool\":\"any\"}]}");
        Run result = cli("--policy-test", cfg.toString(), cases.toString());
        assertEquals(Main.BROKEN, result.code(), result.out() + result.err());
        assertTrue(result.err().contains("supports only declarative http sources"), result.err());
    }


    @Test
    void everyCaseGetsTheDecisionARealCallWouldMeetAndNothingRuns() throws Exception {
        AtomicInteger backendHits = new AtomicInteger();
        HttpServer backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", ex -> {
            backendHits.incrementAndGet();
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        backend.start();
        try {
            Path cfg = Files.writeString(dir.resolve("aegis.json"), """
                    {"gateway": {"name": "gw-policy"},
                     "access": {"writeGroups": ["operators"], "destructiveGroups": ["operators"], "approverGroups": ["approvers"]},
                     "approval": {"keyEnv": "AEGIS_SIGNING_KEY"},
                     "policy": {"rules": [
                       {"id": "read", "groups": ["analysts", "operators"], "classes": ["READ"]},
                       {"id": "ops", "groups": ["operators"], "classes": ["MUTATE", "DESTRUCTIVE"]},
                       {"id": "never-finance", "effect": "DENY", "groups": ["*"], "resources": ["iceberg:table:finance.*"]}]},
                     "egress": {"allowCidrs": ["127.0.0.0/8"]},
                     "audit": {"file": "real-audit.jsonl"},
                     "sources": [{"id": "rest", "type": "http", "baseUrl": "http://127.0.0.1:%d", "tools": [
                       {"name": "list_tables", "description": "List.", "class": "READ",
                        "inputSchema": {"type": "object", "properties": {"namespace": {"type": "string"}}, "required": ["namespace"]},
                        "request": {"method": "GET", "path": "/v1/namespaces/{namespace}/tables"}},
                       {"name": "drop_table", "description": "Drop.", "class": "DESTRUCTIVE",
                        "inputSchema": {"type": "object", "properties": {"namespace": {"type": "string"}, "table": {"type": "string"}},
                                        "required": ["namespace", "table"]},
                        "request": {"method": "DELETE", "path": "/v1/namespaces/{namespace}/tables/{table}"},
                        "resources": [{"type": "iceberg:table", "pointers": ["/namespace", "/table"]}]},
                       {"name": "report", "description": "Report.", "class": "READ", "actionScopes": ["reports:read"],
                        "request": {"method": "GET", "path": "/report"}}]}]}
                    """.formatted(backend.getAddress().getPort()));
            Path cases = Files.writeString(dir.resolve("cases.json"), """
                    {"cases": [
                      {"name": "analyst lists", "caller": {"actor": "alice", "groups": ["analysts"]},
                       "tool": "list_tables", "arguments": {"namespace": "sales"}, "expect": "ALLOWED"},
                      {"name": "analyst cannot drop", "caller": {"actor": "alice", "groups": ["analysts"]},
                       "tool": "drop_table", "arguments": {"namespace": "sales", "table": "orders"}, "expect": "READONLY_CALLER"},
                      {"name": "outsider", "caller": {"actor": "eve", "groups": []},
                       "tool": "list_tables", "arguments": {"namespace": "sales"}, "expect": "POLICY_DENIED"},
                      {"name": "operator drop needs approval", "caller": {"actor": "olga", "groups": ["operators"]},
                       "tool": "drop_table", "arguments": {"namespace": "sales", "table": "orders"}, "expect": "APPROVAL_REQUIRED"},
                      {"name": "finance is denied for everyone", "caller": {"actor": "olga", "groups": ["operators"]},
                       "tool": "drop_table", "arguments": {"namespace": "finance", "table": "ledger"}, "expect": "POLICY_DENIED"},
                      {"name": "bad arguments", "caller": {"actor": "alice", "groups": ["analysts"]},
                       "tool": "list_tables", "arguments": {}, "expect": "INVALID_INPUT"},
                      {"name": "scoped tool without the scope", "caller": {"actor": "alice", "groups": ["analysts"]},
                       "tool": "report", "expect": "SCOPE_DENIED"},
                      {"name": "scoped tool with the scope", "caller": {"actor": "alice", "groups": ["analysts"], "scopes": ["reports:read"]},
                       "tool": "report", "expect": "ALLOWED"},
                      {"name": "unknown tool", "caller": {"actor": "alice", "groups": ["analysts"]},
                       "tool": "no_such_tool", "expect": "NOT_EXPOSED"},
                      {"name": "just show me", "caller": {"actor": "olga", "groups": ["operators"]},
                       "tool": "drop_table", "arguments": {"namespace": "sales", "table": "orders"}}]}
                    """);
            Run ok = cli("--policy-test", cfg.toString(), cases.toString());
            assertEquals(Main.OK, ok.code(), ok.out() + ok.err());
            assertTrue(ok.out().contains("OK cases=10 passed=9 failed=0"), ok.out());
            assertTrue(ok.out().contains("INFO just show me: APPROVAL_REQUIRED (allowed by policy; needs a per-call approval and a dry-run receipt)"),
                    ok.out());

            // Nothing ran: no backend request, and the configured journal was never created.
            assertEquals(0, backendHits.get());
            assertFalse(Files.exists(dir.resolve("real-audit.jsonl")), "the operator's journal is untouched");

            // A wrong expectation fails the run with exit 1 and says why.
            Path wrong = Files.writeString(dir.resolve("wrong.json"), """
                    {"cases": [{"name": "outsider reads", "caller": {"actor": "eve", "groups": []},
                                "tool": "list_tables", "arguments": {"namespace": "sales"}, "expect": "ALLOWED"}]}
                    """);
            Run bad = cli("--policy-test", cfg.toString(), wrong.toString());
            assertEquals(Main.BROKEN, bad.code());
            assertTrue(bad.out().contains("FAIL outsider reads: expected ALLOWED, got POLICY_DENIED (step 3:"), bad.out());

            // Usage and malformed cases.
            assertEquals(Main.USAGE, cli("--policy-test", cfg.toString()).code());
            Path empty = Files.writeString(dir.resolve("empty.json"), "{\"cases\": []}");
            Run none = cli("--policy-test", cfg.toString(), empty.toString());
            assertEquals(Main.BROKEN, none.code());
            assertTrue(none.err().contains("non-empty \"cases\""), none.err());
        } finally {
            backend.stop(0);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void preservesCredentialReferencesAndPinConfigurationForSignedCatalogs() throws Exception {
        Map<String, Object> credentials = Map.of("type", "bearer", "tokenEnv", "BACKEND_TOKEN");
        Map<String, Object> source = Map.of("id", "rest", "type", "http", "baseUrl", "http://127.0.0.1:1",
                "credentials", credentials, "tools", List.of(Map.of("name", "list", "description", "List.",
                        "class", "READ", "request", Map.of("method", "GET", "path", "/"))));
        Map<String, Object> raw = new java.util.LinkedHashMap<>();
        raw.put("sources", List.of(source));
        raw.put("serverCatalog", Map.of("file", "servers.json", "publicKeyFile", "servers.pub"));
        raw.put("catalog", Map.of("pinFile", "operator-pins.json"));

        Map<String, Object> isolated = PolicyTestCommand.isolated(raw, dir, dir.resolve("policy-work"));
        Map<String, Object> isolatedSource = (Map<String, Object>) ((List<?>) isolated.get("sources")).get(0);
        Map<String, Object> isolatedCatalog = (Map<String, Object>) isolated.get("catalog");
        assertEquals(credentials, isolatedSource.get("credentials"));
        assertEquals("operator-pins.json", isolatedCatalog.get("pinFile"));
    }
}
