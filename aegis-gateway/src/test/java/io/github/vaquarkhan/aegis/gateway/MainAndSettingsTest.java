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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.config.ConfigException;
import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.model.Digests;
import io.github.vaquarkhan.aegis.mcp.http.McpHttpRequest;
import io.github.vaquarkhan.aegis.security.perimeter.OwnServerSecuritySettings;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The command line (all non-blocking modes), strict parsing of the host section, and static tokens. */
class MainAndSettingsTest {

    @TempDir
    Path dir;

    static final Map<String, String> ENV = Map.of("AEGIS_SIGNING_KEY", "s".repeat(48),
            "DEV_TOKEN", "dev-token-0123456789", "OPS_TOKEN", "ops-token-0123456789");

    record Run(int code, String out, String err) {}

    Run run(String stdin, String... args) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(args, new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8), ENV::get, false);
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    Path config(String host) throws Exception {
        String json = """
                {"policy": {"rules": [{"id": "read", "groups": ["dev"], "classes": ["READ"]}]},
                 "approval": {"keyEnv": "AEGIS_SIGNING_KEY"},
                 "audit": {"file": "audit.jsonl"},
                 "egress": {"allowCidrs": ["127.0.0.0/8"]},
                 "sources": [{"id": "s", "type": "http", "baseUrl": "http://127.0.0.1:1",
                   "tools": [{"name": "ping", "description": "Ping.", "class": "READ"}]}],
                 "host": %s}""".formatted(host);
        Path p = dir.resolve("aegis.json");
        Files.writeString(p, json);
        return p;
    }

    static final String STATIC = """
            {"listen": {"host": "127.0.0.1", "port": 0},
             "identity": {"type": "static", "static": {"tokens": [
                {"tokenEnv": "DEV_TOKEN", "actor": "dev", "groups": ["dev"]},
                {"sha256": "%s", "actor": "ops", "groups": ["ops"]}]}},
             "stdio": {"actor": "local", "groups": ["dev"]},
             "metrics": {"path": "/prom", "allowedGroups": ["ops"]}}""".formatted(Digests.sha256Hex("ops-token-0123456789"));

    @Test
    void commandLineModes() throws Exception {
        assertEquals(Main.USAGE, run("").code(), "no arguments");
        assertEquals(Main.USAGE, run("", "--bogus").code());
        assertEquals(Main.USAGE, run("", "--config").code());
        assertEquals(Main.USAGE, run("", "--verify-audit").code());
        assertEquals(Main.USAGE, run("  ", "--hash-token").code());
        Run h = run("secret-token\n", "--hash-token");
        assertEquals(Main.OK, h.code());
        assertEquals(Digests.sha256Hex("secret-token"), h.out().trim());

        // Start and stop the real server once (serve=false returns after start-up), which writes the journal.
        Path cfg = config(STATIC);
        assertEquals(Main.OK, run("", "--config", cfg.toString()).code());

        // stdio mode: one JSON-RPC line in, one line out, under the configured stdio identity.
        String line = Json.write(Map.of("jsonrpc", "2.0", "id", 1L, "method", "tools/call", "params", Map.of("name", "ping",
                "_meta", Map.of("io.modelcontextprotocol/protocolVersion", "2026-07-28")))) + "\n";
        Run s = run(line, "--config", cfg.toString(), "--stdio");
        assertEquals(Main.OK, s.code(), s.err());
        assertTrue(s.out().contains("\"jsonrpc\":\"2.0\"") && s.out().contains("\"id\":1"), s.out());

        Run ok = run("", "--verify-audit", dir.resolve("audit.jsonl").toString());
        assertEquals(Main.OK, ok.code(), ok.out());
        assertTrue(ok.out().startsWith("OK records="), ok.out());
        Path tampered = dir.resolve("tampered.jsonl");
        Files.writeString(tampered, Files.readString(dir.resolve("audit.jsonl")).replace("\"ping\"", "\"pong\""));
        Run bad = run("", "--verify-audit", tampered.toString());
        assertEquals(Main.BROKEN, bad.code(), bad.out());
        assertTrue(bad.out().startsWith("BROKEN"), bad.out());

        Path noStdio = config("""
                {"identity": {"type": "static", "static": {"tokens": [{"tokenEnv": "DEV_TOKEN", "actor": "dev", "groups": ["dev"]}]}}}""");
        assertEquals(Main.USAGE, run("", "--config", noStdio.toString(), "--stdio").code());
    }

    @Test
    void auditCheckpointsAreWrittenFromConfigAndCheckedFromTheCommandLine() throws Exception {
        Path cfg = config(STATIC);
        Files.writeString(cfg, Files.readString(cfg).replace("\"audit\": {\"file\": \"audit.jsonl\"}",
                "\"audit\": {\"file\": \"audit.jsonl\", \"checkpoint\": {\"file\": \"anchor/checkpoints.jsonl\","
                        + " \"everyRecords\": 1, \"everySeconds\": 0}}"));
        String line = Json.write(Map.of("jsonrpc", "2.0", "id", 1L, "method", "tools/call", "params", Map.of("name", "ping",
                "_meta", Map.of("io.modelcontextprotocol/protocolVersion", "2026-07-28")))) + "\n";
        Run s = run(line, "--config", cfg.toString(), "--stdio");
        assertEquals(Main.OK, s.code(), s.err());

        Path journal = dir.resolve("audit.jsonl");
        Path cps = dir.resolve("anchor").resolve("checkpoints.jsonl");
        assertTrue(Files.size(cps) > 0, "a checkpoint was exported");
        Run ok = run("", "--verify-audit", journal.toString(), "--checkpoints", cps.toString());
        assertEquals(Main.OK, ok.code(), ok.out());
        assertTrue(ok.out().startsWith("OK records="), ok.out());
        assertTrue(ok.out().contains(" checkpoints="), ok.out());

        // Truncate the journal to its first record: the chain alone still verifies, the checkpoints do not,
        // and the gateway refuses to start on it.
        Files.writeString(journal, Files.readAllLines(journal).get(0) + "\n");
        assertEquals(Main.OK, run("", "--verify-audit", journal.toString()).code());
        Run bad = run("", "--verify-audit", journal.toString(), "--checkpoints", cps.toString());
        assertEquals(Main.BROKEN, bad.code(), bad.out());
        assertTrue(bad.out().contains("truncated or replaced"), bad.out());
        Exception refused = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> run("", "--config", cfg.toString()));
        assertTrue(String.valueOf(refused.getCause()).contains("truncated or replaced")
                || String.valueOf(refused.getMessage()).contains("audit journal"), String.valueOf(refused));

        assertEquals(Main.USAGE, run("", "--checkpoints", cps.toString()).code(), "--checkpoints needs --verify-audit");
        assertEquals(Main.USAGE, run("", "--verify-audit", journal.toString(), "--checkpoints").code());
        assertEquals(Main.USAGE, run("", "--verify-audit", journal.toString(), "--config", cfg.toString()).code());
    }

    @Test
    void auditCheckpointSettingsAreValidated() throws Exception {
        for (String cp : List.of("{\"everyRecords\": 1}", "{\"file\": \"c.jsonl\", \"everyRecords\": 0}",
                "{\"file\": \"c.jsonl\", \"everySeconds\": -1}", "{\"file\": \"audit.jsonl\"}",
                "{\"file\": \"c.jsonl\", \"bogus\": 1}")) {
            Path cfg = config(STATIC);
            Files.writeString(cfg, Files.readString(cfg).replace("\"audit\": {\"file\": \"audit.jsonl\"}",
                    "\"audit\": {\"file\": \"audit.jsonl\", \"checkpoint\": " + cp + "}"));
            Exception e = org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> run("", "--config", cfg.toString()));
            assertTrue(String.valueOf(e.getMessage()).contains("audit.checkpoint"), cp + " -> " + e.getMessage());
        }
    }

    @Test
    void hostSectionIsStrict() {
        OwnServerSecuritySettings st = OwnServerSecuritySettings.parse(Json.parseObject(STATIC), dir, ENV::get, Clock.systemUTC());
        assertEquals("/prom", st.metricsPath());
        assertEquals(java.util.Set.of("ops"), st.metricsGroups());
        assertEquals("local", st.stdioIdentity().actor());

        List<String> bad = List.of(
                "{\"identity\": {\"type\": \"anonymous\"}}",
                "{\"identity\": {\"type\": \"static\", \"static\": {\"tokens\": []}}}",
                "{\"identity\": {\"type\": \"static\", \"static\": {\"tokens\": [{\"tokenEnv\": \"UNSET\", \"actor\": \"a\"}]}}}",
                "{\"identity\": {\"type\": \"jwt\", \"jwt\": {\"issuer\": \"i\", \"audience\": \"a\", \"algorithms\": [\"HS256\"], \"hmacSecretEnv\": \"UNSET\"}}}",
                "{\"identity\": {\"type\": \"jwt\", \"jwt\": {\"issuer\": \"i\", \"audience\": \"a\", \"algorithms\": [\"RS256\"]}}}",
                "{\"tls\": {\"keystore\": \"k.p12\", \"keystorePasswordEnv\": \"UNSET\"}, \"identity\": {\"type\": \"static\", \"static\": {\"tokens\": [{\"tokenEnv\": \"DEV_TOKEN\", \"actor\": \"a\"}]}}}",
                "{\"metrics\": {\"path\": \"/mcp\"}, \"identity\": {\"type\": \"static\", \"static\": {\"tokens\": [{\"tokenEnv\": \"DEV_TOKEN\", \"actor\": \"a\"}]}}}",
                "{\"listen\": {\"hots\": \"x\"}, \"identity\": {\"type\": \"static\", \"static\": {\"tokens\": [{\"tokenEnv\": \"DEV_TOKEN\", \"actor\": \"a\"}]}}}");
        for (String b : bad) {
            assertThrows(ConfigException.class, () -> OwnServerSecuritySettings.parse(Json.parseObject(b), dir, ENV::get, Clock.systemUTC()), b);
        }
    }

    @Test
    void staticTokensResolveByDigestOnly() {
        OwnServerSecuritySettings st = OwnServerSecuritySettings.parse(Json.parseObject(STATIC), dir, ENV::get, Clock.systemUTC());
        CallerIdentity dev = st.identity().resolve(bearer("dev-token-0123456789"));
        assertNotNull(dev);
        assertEquals("dev", dev.actor());
        assertEquals("static-token", dev.authMethod());
        assertEquals("ops", st.identity().resolve(bearer("ops-token-0123456789")).actor());
        assertNull(st.identity().resolve(bearer("dev-token-0123456788")));
        assertNull(st.identity().resolve(new McpHttpRequest("POST", "", Map.of(), null)));
        Map<String, List<String>> basic = new LinkedHashMap<>();
        basic.put("Authorization", List.of("Basic ZGV2OnB3"));
        assertNull(st.identity().resolve(new McpHttpRequest("POST", "", basic, null)));
    }

    static McpHttpRequest bearer(String token) {
        return new McpHttpRequest("POST", "", Map.of("Authorization", List.of("Bearer " + token)), null);
    }
}
