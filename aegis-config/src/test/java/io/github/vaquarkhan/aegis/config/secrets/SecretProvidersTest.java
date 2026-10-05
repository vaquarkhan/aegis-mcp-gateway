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
package io.github.vaquarkhan.aegis.config.secrets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.vaquarkhan.aegis.config.BuildContext;
import io.github.vaquarkhan.aegis.config.ConfigException;
import io.github.vaquarkhan.aegis.config.Gateway;
import io.github.vaquarkhan.aegis.config.GatewayFactory;
import io.github.vaquarkhan.aegis.engine.CallOutcome;
import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.model.ToolCall;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretProvidersTest {

    @TempDir
    Path dir;

    /** The process environment in these tests: it holds decoy values that must not be used. */
    static final Map<String, String> ENV = Map.of("AEGIS_SIGNING_KEY", "e".repeat(48), "REST_TOKEN", "from-the-environment");

    static Map<String, Object> config(String baseUrl, Map<String, Object> secrets) {
        Map<String, Object> c = new LinkedHashMap<>();
        if (secrets != null) {
            c.put("secrets", secrets);
        }
        c.put("approval", Map.of("keyEnv", "AEGIS_SIGNING_KEY"));
        c.put("policy", Map.of("rules", List.of(Map.of("id", "r", "groups", List.of("dev"), "classes", List.of("READ")))));
        c.put("audit", Map.of("file", "audit.jsonl"));
        c.put("egress", Map.of("allowCidrs", List.of("127.0.0.0/8")));
        c.put("sources", List.of(Map.of("id", "s", "type", "http", "baseUrl", baseUrl,
                "credentials", Map.of("type", "bearer", "tokenEnv", "REST_TOKEN"),
                "tools", List.of(Map.of("name", "ping", "description", "Ping.", "class", "READ",
                        "request", Map.of("path", "/ping"))))));
        return c;
    }

    @Test
    void fileSecretsReplaceTheEnvironmentForEveryNamedSecret() throws Exception {
        Path secrets = Files.createDirectories(dir.resolve("run-secrets"));
        Files.writeString(secrets.resolve("AEGIS_SIGNING_KEY"), "f".repeat(48) + "\n");
        Files.writeString(secrets.resolve("REST_TOKEN"), "from-the-mounted-file\r\n");
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        HttpServer rest = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        rest.createContext("/", ex -> {
            seen.add(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] b = "{}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        rest.start();
        try (Gateway g = GatewayFactory.build(config("http://127.0.0.1:" + rest.getAddress().getPort(),
                Map.of("source", "file", "settings", Map.of("directory", "run-secrets"))), BuildContext.defaults(dir).withEnv(ENV::get))) {
            assertEquals("from-the-mounted-file", g.secrets().apply("REST_TOKEN"), "one trailing newline is removed");
            CallOutcome o = g.engine().call(CallerIdentity.of("u", Set.of("dev"), "i", "t"),
                    new ToolCall("1", "ping", Map.of(), Map.of(), ""));
            assertTrue(o.allowed(), o.decision().toString());
            assertEquals(List.of("Bearer from-the-mounted-file"), seen, "the backend got the file value, not the environment's");
        } finally {
            rest.stop(0);
        }

        // Strict: a name that only the environment knows is not set under secrets.source: file.
        Files.delete(secrets.resolve("REST_TOKEN"));
        ConfigException e = assertThrows(ConfigException.class, () -> GatewayFactory.build(config("http://127.0.0.1:1",
                Map.of("source", "file", "settings", Map.of("directory", "run-secrets"))), BuildContext.defaults(dir).withEnv(ENV::get)));
        assertTrue(e.getMessage().contains("REST_TOKEN, which is not set by secrets.source"), e.getMessage());
    }

    @Test
    void theFileProviderNeverLeavesItsDirectory() throws Exception {
        Path secrets = Files.createDirectories(dir.resolve("s"));
        Files.writeString(dir.resolve("outside"), "nope");
        Files.writeString(secrets.resolve("ok"), "value");
        Files.writeString(secrets.resolve("empty"), "\n");
        FileSecretProvider p = new FileSecretProvider(secrets);
        assertEquals("value", p.apply("ok"));
        assertNull(p.apply("../outside"));
        assertNull(p.apply("..\\outside"));
        assertNull(p.apply(".hidden"));
        assertNull(p.apply("a/b"));
        assertNull(p.apply("missing"));
        assertNull(p.apply("empty"), "an empty secret is not set");
        assertNull(p.apply(null));
        Files.write(secrets.resolve("big"), new byte[FileSecretProvider.MAX_BYTES + 1]);
        assertThrows(ConfigException.class, () -> p.apply("big"));
        assertThrows(ConfigException.class, () -> new FileSecretProvider(dir.resolve("absent")));
        assertThrows(ConfigException.class, () -> new FileSecretProvider(secrets.resolve("ok")));
    }

    @Test
    void sourceSelectionAndVaultSettingsAreStrictAndChecked() throws Exception {
        BuildContext ctx = BuildContext.defaults(dir).withEnv(ENV::get);
        assertTrue(assertThrows(ConfigException.class, () -> GatewayFactory.build(config("http://127.0.0.1:1",
                Map.of("source", "keychain")), ctx)).getMessage().contains("unknown secret provider 'keychain'"));
        Path secrets = Files.createDirectories(dir.resolve("strict"));
        Files.writeString(secrets.resolve("AEGIS_SIGNING_KEY"), "f".repeat(48));
        Files.writeString(secrets.resolve("REST_TOKEN"), "t");
        String extra = assertThrows(ConfigException.class, () -> GatewayFactory.build(config("http://127.0.0.1:1",
                Map.of("source", "file", "settings", Map.of("directory", secrets.toString(), "extra", 1L))), ctx)).getMessage();
        assertTrue(extra.contains("config.secrets.settings: unknown key(s) [extra]"), extra);
        // The Vault address must be allowed by the operator's own egress list (no request is made).
        Map<String, Object> vault = new LinkedHashMap<>(Map.of("address", "https://vault.example.com:8200",
                "path", "aegis/prod", "tokenEnv", "VAULT_TOKEN"));
        String egress = assertThrows(ConfigException.class, () -> GatewayFactory.build(config("http://127.0.0.1:1",
                Map.of("source", "vault", "settings", vault)), ctx)).getMessage();
        assertTrue(egress.contains("not allowed by config.egress"), egress);
        Map<String, Object> both = new LinkedHashMap<>(Map.of("address", "http://127.0.0.1:1", "path", "p",
                "tokenEnv", "VAULT_TOKEN", "tokenFile", "t"));
        Map<String, Object> cfg = config("http://127.0.0.1:1", Map.of("source", "vault", "settings", both));
        cfg.put("egress", Map.of("allowHosts", List.of("127.0.0.1"), "allowCidrs", List.of("127.0.0.0/8"),
                "schemes", List.of("http")));
        String twoTokens = assertThrows(ConfigException.class, () -> GatewayFactory.build(cfg, ctx)).getMessage();
        assertTrue(twoTokens.contains("exactly one of tokenEnv or tokenFile"), twoTokens);
    }

    @Test
    void aProviderJarOnTheClasspathIsSelectedBySourceName() throws Exception {
        Files.writeString(dir.resolve("secrets.properties"), "AEGIS_SIGNING_KEY=" + "p".repeat(48) + "\nREST_TOKEN=from-properties\n");
        try (Gateway g = GatewayFactory.build(config("http://127.0.0.1:1", Map.of("source", "properties-file",
                "settings", Map.of("file", "secrets.properties"))), BuildContext.defaults(dir).withEnv(ENV::get))) {
            assertEquals("from-properties", g.secrets().apply("REST_TOKEN"));
        }
    }

    /** Vault settings for an address on loopback that the egress list allows. */
    private Map<String, Object> vaultConfig(String address, Map<String, Object> extra) {
        Map<String, Object> settings = new LinkedHashMap<>(Map.of("address", address, "path", "aegis/prod", "tokenFile", "token"));
        settings.putAll(extra);
        Map<String, Object> cfg = config("http://127.0.0.1:1", Map.of("source", "vault", "settings", settings));
        cfg.put("egress", Map.of("allowHosts", List.of("127.0.0.1"), "allowCidrs", List.of("127.0.0.0/8"),
                "schemes", List.of("http", "https")));
        return cfg;
    }

    @Test
    void anUnreachableOrMisconfiguredVaultStopsTheGatewayFromStarting() throws Exception {
        Files.writeString(dir.resolve("token"), "s.local-test-token\n");
        int closed;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            closed = s.getLocalPort();
        }
        BuildContext ctx = BuildContext.defaults(dir).withEnv(ENV::get);
        String refused = assertThrows(ConfigException.class, () -> GatewayFactory.build(
                vaultConfig("http://127.0.0.1:" + closed, Map.of("timeoutMs", 2000L)), ctx)).getMessage();
        assertTrue(refused.contains("could not read secrets from http://127.0.0.1:" + closed), refused);
        assertTrue(!refused.contains("local-test-token"), "the token is never echoed");

        Files.writeString(dir.resolve("not-a-cert.pem"), "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n");
        String badCa = assertThrows(ConfigException.class, () -> GatewayFactory.build(
                vaultConfig("https://127.0.0.1:" + closed, Map.of("caFile", "not-a-cert.pem")), ctx)).getMessage();
        assertTrue(badCa.contains("caFile") || badCa.contains("could not read secrets"), badCa);

        Files.writeString(dir.resolve("empty.pem"), "");
        assertThrows(ConfigException.class, () -> GatewayFactory.build(
                vaultConfig("https://127.0.0.1:" + closed, Map.of("caFile", "empty.pem")), ctx));

        for (Map<String, Object> bad : List.<Map<String, Object>>of(Map.of("mount", "../sys"), Map.of("path", "a/../b"),
                Map.of("path", "a//b"))) {
            assertThrows(ConfigException.class, () -> GatewayFactory.build(vaultConfig("http://127.0.0.1:" + closed, bad), ctx),
                    String.valueOf(bad));
        }
        Files.writeString(dir.resolve("token"), "   ");
        String empty = assertThrows(ConfigException.class, () -> GatewayFactory.build(
                vaultConfig("http://127.0.0.1:" + closed, Map.of()), ctx)).getMessage();
        assertTrue(empty.contains("token is empty"), empty);
    }

    @Test
    void kvVersion2ResponsesAreParsedStrictly() {
        assertEquals(Map.of("A", "1"), VaultKvSecretProvider.parse(
                Json.write(Map.of("data", Map.of("data", Map.of("A", "1"), "metadata", Map.of()))).getBytes(StandardCharsets.UTF_8)));
        assertTrue(assertThrows(ConfigException.class, () -> VaultKvSecretProvider.parse(
                Json.write(Map.of("data", Map.of("A", "1"))).getBytes(StandardCharsets.UTF_8))).getMessage().contains("KV version 2"));
        assertTrue(assertThrows(ConfigException.class, () -> VaultKvSecretProvider.parse(
                Json.write(Map.of("data", Map.of("data", Map.of("A", 1L)))).getBytes(StandardCharsets.UTF_8))).getMessage().contains("not a string"));
        assertThrows(ConfigException.class, () -> VaultKvSecretProvider.parse("<html>".getBytes(StandardCharsets.UTF_8)));
    }
}
