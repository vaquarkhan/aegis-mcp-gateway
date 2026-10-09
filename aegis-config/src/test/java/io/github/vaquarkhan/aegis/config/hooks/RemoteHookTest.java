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
package io.github.vaquarkhan.aegis.config.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.vaquarkhan.aegis.config.BuildContext;
import io.github.vaquarkhan.aegis.config.ConfigException;
import io.github.vaquarkhan.aegis.config.Gateway;
import io.github.vaquarkhan.aegis.config.GatewayFactory;
import io.github.vaquarkhan.aegis.engine.CallOutcome;
import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.model.Action;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.model.DenyCodes;
import io.github.vaquarkhan.aegis.engine.model.ToolCall;
import io.github.vaquarkhan.aegis.engine.model.ToolClass;
import io.github.vaquarkhan.aegis.engine.model.ToolResult;
import io.github.vaquarkhan.aegis.engine.spi.GovernanceHook;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The built-in {@code remote} hook against a real local HTTP endpoint: the answer contract, the
 * fail-closed paths, what does and does not leave the gateway, and the startup egress check.
 */
class RemoteHookTest {

    @TempDir
    java.nio.file.Path dir;

    static final CallerIdentity ANA = CallerIdentity.of("ana", Set.of("users"), "idp", "jwt");
    static final Map<String, String> ENV = Map.of("HOOK_TOKEN", "hook-token-abc", "AEGIS_SIGNING_KEY", "k".repeat(40));

    HttpServer server;
    int port;
    final AtomicReference<String> reply = new AtomicReference<>("{\"allow\":true}");
    final AtomicReference<Integer> status = new AtomicReference<>(200);
    final AtomicReference<String> lastBody = new AtomicReference<>("");
    final AtomicReference<String> lastAuth = new AtomicReference<>("");
    final AtomicReference<Long> delayMillis = new AtomicReference<>(0L);

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", ex -> {
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastAuth.set(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            if (delayMillis.get() > 0) {
                try {
                    Thread.sleep(delayMillis.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] b = reply.get().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status.get(), b.length == 0 ? -1 : b.length);
            if (b.length > 0) {
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(b);
                }
            }
            ex.close();
        });
        server.createContext("/items", ex -> {
            byte[] b = "{\"items\":[{\"id\":1}]}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
            ex.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private URI hookUrl() {
        return URI.create("http://127.0.0.1:" + port + "/hook");
    }

    private RemoteHook hook(boolean includeArguments, boolean includeResult, Set<RemoteHook.Phase> phases) {
        return new RemoteHook("dlp", hookUrl(), Duration.ofSeconds(2),
                Map.of("Authorization", "Bearer " + ENV.get("HOOK_TOKEN")), includeArguments, includeResult, 8192, phases);
    }

    private static GovernanceHook.Context context() {
        Action a = new Action("drop_table", ToolClass.DESTRUCTIVE, "iceberg", List.of("iceberg:table:sales.orders"), "sha256:ab");
        return new GovernanceHook.Context("req_1", "4bf92f3577b34da6a3ce929d0e0e4736", ANA,
                GovernanceHook.Operation.TOOL_CALL, "drop_table", null, "iceberg", a,
                Map.of("table", "sales.orders", "ssn", "123-45-6789"), false);
    }

    @Test
    void onlyALiteralTrueAllowsAndEverythingElseDenies() throws Exception {
        RemoteHook h = hook(false, false, Set.of(RemoteHook.Phase.BEFORE));
        GovernanceHook.Context c = context();

        reply.set("{\"allow\":true}");
        assertTrue(h.before(c).allowed());

        reply.set("{\"allow\":false,\"code\":\"PROMPT_INJECTION\",\"reason\":\"argument looks like an injection\"}");
        GovernanceHook.HookDenied denied = assertThrows(GovernanceHook.HookDenied.class, () -> h.before(c));
        assertEquals(DenyCodes.PROMPT_INJECTION, denied.code());
        assertEquals("argument looks like an injection", denied.getMessage());

        reply.set("{\"allow\":false}");
        assertEquals("denied by dlp", assertThrows(GovernanceHook.HookDenied.class, () -> h.before(c)).getMessage());

        for (String bad : new String[] {"{\"allow\":\"true\"}", "{}", "{\"allowed\":true}", "{\"allow\":1}", "not json",
                "{\"allow\":null}"}) {
            reply.set(bad);
            assertThrows(Exception.class, () -> h.before(c), bad);
        }
        status.set(500);
        reply.set("{\"allow\":true}");
        assertTrue(assertThrows(GovernanceHook.HookDenied.class, () -> h.before(c)).getMessage().contains("HTTP 500"));
        status.set(200);
    }

    @Test
    void theEndpointIsUnreachableOrSlowAndTheHookStillFailsClosed() throws Exception {
        RemoteHook unreachable = new RemoteHook("dlp", URI.create("http://127.0.0.1:1/hook"), Duration.ofMillis(500),
                Map.of(), false, false, 8192, Set.of(RemoteHook.Phase.BEFORE));
        assertThrows(Exception.class, () -> unreachable.before(context()));

        RemoteHook impatient = new RemoteHook("dlp", hookUrl(), Duration.ofMillis(200), Map.of(), false, false, 8192,
                Set.of(RemoteHook.Phase.BEFORE));
        delayMillis.set(1_500L);
        try {
            assertThrows(Exception.class, () -> impatient.before(context()), "a slow endpoint must not become an allow");
        } finally {
            delayMillis.set(0L);
        }
    }

    @Test
    void argumentsAndResultsStayInsideTheGatewayUnlessTheOperatorOptsIn() throws Exception {
        GovernanceHook.Context c = context();
        RemoteHook quiet = hook(false, false, Set.of(RemoteHook.Phase.BEFORE, RemoteHook.Phase.AFTER));
        quiet.before(c);
        String body = lastBody.get();
        assertFalse(body.contains("123-45-6789"), "arguments are not sent by default: " + body);
        assertFalse(body.contains("\"arguments\""), body);
        // What the endpoint always gets is enough for identity and resource based controls.
        assertTrue(body.contains("\"operation\":\"TOOL_CALL\""), body);
        assertTrue(body.contains("\"method\":\"drop_table\""), body);
        assertTrue(body.contains("\"toolClass\":\"DESTRUCTIVE\""), body);
        assertTrue(body.contains("iceberg:table:sales.orders"), body);
        assertTrue(body.contains("\"argsDigest\":\"sha256:ab\""), body);
        assertTrue(body.contains("\"traceId\":\"4bf92f3577b34da6a3ce929d0e0e4736\""), body);
        assertTrue(body.contains("\"actor\":\"ana\"") && body.contains("\"issuer\":\"idp\""), body);
        assertTrue(body.contains("\"phase\":\"before\"") && body.contains("\"aegis\":\"2.0\""), body);
        assertEquals("Bearer hook-token-abc", lastAuth.get(), "the configured service token is presented");

        ToolResult r = ToolResult.text("row one", false);
        quiet.afterTool(c, r);
        String after = lastBody.get();
        assertTrue(after.contains("\"phase\":\"afterTool\""), after);
        assertFalse(after.contains("row one"), "the result is not sent by default: " + after);
        assertTrue(after.contains("\"resultDigest\""), after);

        RemoteHook verbose = hook(true, true, Set.of(RemoteHook.Phase.BEFORE, RemoteHook.Phase.AFTER));
        verbose.before(c);
        assertTrue(lastBody.get().contains("123-45-6789"), "includeArguments sends them");
        verbose.afterTool(c, r);
        assertTrue(lastBody.get().contains("row one"), "includeResult sends the result");
    }

    @Test
    void phasesLimitWhenTheEndpointIsConsulted() throws Exception {
        GovernanceHook.Context c = context();
        reply.set("{\"allow\":false,\"reason\":\"no\"}");

        RemoteHook beforeOnly = hook(false, false, Set.of(RemoteHook.Phase.BEFORE));
        assertThrows(GovernanceHook.HookDenied.class, () -> beforeOnly.before(c));
        ToolResult r = ToolResult.text("x", false);
        assertEquals(r, beforeOnly.afterTool(c, r), "the after phase was not configured");
        assertEquals(Map.of("k", "v"), beforeOnly.afterContent(c, Map.of("k", "v")));

        RemoteHook afterOnly = hook(false, false, Set.of(RemoteHook.Phase.AFTER));
        assertTrue(afterOnly.before(c).allowed(), "the before phase was not configured");
        assertThrows(GovernanceHook.HookDenied.class, () -> afterOnly.afterTool(c, r));
        assertThrows(GovernanceHook.HookDenied.class, () -> afterOnly.afterContent(c, Map.of("k", "v")));

        // The default is the before phase only, and a result is returned unchanged when it is allowed.
        RemoteHook defaults = new RemoteHook("dlp", hookUrl(), null, null, false, false, 0, null);
        assertEquals(Set.of(RemoteHook.Phase.BEFORE), defaults.phases());
        assertEquals(hookUrl(), defaults.endpoint());
        assertEquals("dlp", defaults.name());
    }

    @Test
    void constructorRejectsAHookWithNoNameOrUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> new RemoteHook(" ", hookUrl(), null, null, false, false, 0, null));
        assertThrows(IllegalArgumentException.class,
                () -> new RemoteHook("dlp", null, null, null, false, false, 0, null));
    }

    // ---------------------------------------------------------------- wired through configuration

    private String config(String hooks) {
        return """
                {"policy": {"rules": [{"id": "all", "groups": ["users"]}]},
                 "egress": {"allowCidrs": ["127.0.0.0/8"]},
                 "audit": {"file": "a.jsonl"},
                 "hooks": [%s],
                 "sources": [{"id": "api", "type": "http", "baseUrl": "http://127.0.0.1:%d",
                   "tools": [{"name": "items", "description": "Items.", "class": "READ", "request": {"path": "/items"}}]}]}
                """.formatted(hooks, port);
    }

    private Gateway build(String json) throws Exception {
        return GatewayFactory.build(Json.parseObject(json), BuildContext.defaults(dir).withEnv(ENV::get));
    }

    @Test
    void configuredRemoteHookGovernsCallsAndHonoursItsMode() throws Exception {
        String remote = "{\"type\": \"remote\", \"settings\": {\"name\": \"dlp\", \"url\": \"http://127.0.0.1:" + port
                + "/hook\", \"tokenEnv\": \"HOOK_TOKEN\", \"timeoutMs\": 2000}}";
        reply.set("{\"allow\":false,\"reason\":\"table is restricted\"}");
        try (Gateway g = build(config(remote))) {
            CallOutcome o = g.engine().call(ANA, new ToolCall("1", "items", Map.of(), Map.of(), ""));
            assertFalse(o.allowed());
            assertEquals(DenyCodes.POLICY_DENIED, o.decision().code());
            assertEquals(DenyCodes.STEP_INJECTION, o.decision().step());
            assertTrue(o.decision().reason().contains("table is restricted"), o.decision().reason());
            assertEquals("Bearer hook-token-abc", lastAuth.get());
        }
        // The same endpoint in AUDIT mode records the would-be denial and lets the call through.
        try (Gateway g = build(config(remote.replace("{\"type\": \"remote\"", "{\"mode\": \"AUDIT\", \"type\": \"remote\"")))) {
            assertTrue(g.engine().call(ANA, new ToolCall("1", "items", Map.of(), Map.of(), "")).allowed());
        }
        // Bound to content operations only, so a tool call never reaches it.
        String contentOnly = remote.replace("{\"type\": \"remote\"",
                "{\"operations\": [\"RESOURCE_READ\", \"PROMPT_GET\"], \"type\": \"remote\"");
        lastBody.set("");
        try (Gateway g = build(config(contentOnly))) {
            assertTrue(g.engine().call(ANA, new ToolCall("1", "items", Map.of(), Map.of(), "")).allowed());
            assertEquals("", lastBody.get(), "the endpoint was never asked about a tool call");
        }
    }

    @Test
    void aHookEndpointOutsideTheEgressAllowListRefusesToStart() {
        String offList = "{\"type\": \"remote\", \"settings\": {\"url\": \"http://169.254.169.254/hook\"}}";
        ConfigException e = assertThrows(ConfigException.class, () -> build(config(offList)));
        assertTrue(e.getMessage().contains("egress"), e.getMessage());

        for (String bad : new String[] {
                "{\"type\": \"remote\", \"settings\": {\"url\": \"not a uri\"}}",
                "{\"type\": \"remote\", \"settings\": {}}",
                "{\"type\": \"remote\", \"mode\": \"SOMETIMES\", \"settings\": {\"url\": \"http://127.0.0.1:" + port + "/hook\"}}",
                "{\"type\": \"remote\", \"operations\": [\"EVERYTHING\"], \"settings\": {\"url\": \"http://127.0.0.1:" + port + "/hook\"}}",
                "{\"type\": \"remote\", \"settings\": {\"url\": \"http://127.0.0.1:" + port + "/hook\", \"phases\": [\"DURING\"]}}",
                "{\"type\": \"remote\", \"settings\": {\"url\": \"http://127.0.0.1:" + port + "/hook\", \"tokenEnv\": \"UNSET\"}}"}) {
            assertThrows(Exception.class, () -> build(config(bad)), bad);
        }
    }
}
