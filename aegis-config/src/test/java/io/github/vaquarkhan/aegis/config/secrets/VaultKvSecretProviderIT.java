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

import io.github.vaquarkhan.aegis.config.BuildContext;
import io.github.vaquarkhan.aegis.config.ConfigException;
import io.github.vaquarkhan.aegis.config.Gateway;
import io.github.vaquarkhan.aegis.config.GatewayFactory;
import io.github.vaquarkhan.aegis.engine.json.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code secrets.source: vault} against a real Vault-API server (validated with OpenBao). The test
 * writes a KV v2 secret and a read-only ACL policy with the admin token, mints a token limited to that
 * one path, and gives the gateway only the limited token. Runs with {@code -Pvault-integration} and
 * {@code AEGIS_VAULT_ADDR} plus {@code AEGIS_VAULT_TOKEN}; skipped, never passed, otherwise.
 */
class VaultKvSecretProviderIT {

    @TempDir
    Path dir;

    final HttpClient http = HttpClient.newHttpClient();
    String addr;
    String admin;
    String path;
    String policy;
    String limitedToken;

    @BeforeEach
    void requireRealServer() throws Exception {
        addr = System.getenv("AEGIS_VAULT_ADDR");
        admin = System.getenv("AEGIS_VAULT_TOKEN");
        Assumptions.assumeTrue(addr != null && admin != null,
                "AEGIS_VAULT_ADDR and AEGIS_VAULT_TOKEN are required (skipped, never passed)");
        path = "aegis-it-" + UUID.randomUUID();
        policy = path + "-read";
        HttpResponse<String> written = call("POST", "/v1/secret/data/" + path, Map.of("data", Map.of(
                "AEGIS_SIGNING_KEY", "v".repeat(48), "REST_TOKEN", "token-from-vault")));
        assertEquals(200, written.statusCode(), written.body());
        assertEquals(204, call("PUT", "/v1/sys/policies/acl/" + policy, Map.of("policy",
                "path \"secret/data/" + path + "\" { capabilities = [\"read\"] }")).statusCode());
        HttpResponse<String> t = call("POST", "/v1/auth/token/create", Map.of("policies", List.of(policy), "ttl", "10m"));
        assertEquals(200, t.statusCode(), t.body());
        @SuppressWarnings("unchecked")
        Map<String, Object> auth = (Map<String, Object>) Json.parseObject(t.body()).get("auth");
        limitedToken = (String) auth.get("client_token");
    }

    @AfterEach
    void cleanUp() throws Exception {
        if (addr == null || admin == null) {
            return;
        }
        if (limitedToken != null) {
            call("POST", "/v1/auth/token/revoke", Map.of("token", limitedToken));
        }
        call("DELETE", "/v1/secret/metadata/" + path, null);
        call("DELETE", "/v1/sys/policies/acl/" + policy, null);
    }

    private HttpResponse<String> call(String method, String p, Object body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(addr + p)).header("X-Vault-Token", admin)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(Json.write(body)))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, Object> config(Map<String, Object> vaultSettings) {
        Map<String, Object> c = SecretProvidersTest.config("http://127.0.0.1:1", Map.of("source", "vault", "settings", vaultSettings));
        URI a = URI.create(addr);
        c.put("egress", Map.of("allowCidrs", List.of("127.0.0.0/8"), "allowHosts", List.of(a.getHost()),
                "schemes", List.of(a.getScheme())));
        return c;
    }

    @Test
    void aTokenLimitedToOnePathSuppliesEveryNamedSecret() throws Exception {
        Files.writeString(dir.resolve("vault-token"), limitedToken + "\n");
        Map<String, Object> settings = new LinkedHashMap<>(Map.of("address", addr, "mount", "secret", "path", path,
                "tokenFile", "vault-token"));
        try (Gateway g = GatewayFactory.build(config(settings), BuildContext.defaults(dir).withEnv(SecretProvidersTest.ENV::get))) {
            assertEquals("token-from-vault", g.secrets().apply("REST_TOKEN"));
            assertEquals("v".repeat(48), g.secrets().apply("AEGIS_SIGNING_KEY"));
            assertNull(g.secrets().apply("NOT_IN_VAULT"));
        }

        // The same through tokenEnv, read from the process environment the provider itself does not serve.
        Map<String, String> env = new LinkedHashMap<>(SecretProvidersTest.ENV);
        env.put("VAULT_TOKEN", limitedToken);
        Map<String, Object> viaEnv = new LinkedHashMap<>(Map.of("address", addr, "path", path, "tokenEnv", "VAULT_TOKEN"));
        try (Gateway g = GatewayFactory.build(config(viaEnv), BuildContext.defaults(dir).withEnv(env::get))) {
            assertEquals("token-from-vault", g.secrets().apply("REST_TOKEN"));
        }
    }

    @Test
    void theServersOwnAnswersAreFailClosedConfigurationErrors() throws Exception {
        Files.writeString(dir.resolve("vault-token"), limitedToken);
        // Another path: the limited token is refused by the server's ACL.
        ConfigException denied = assertThrows(ConfigException.class, () -> GatewayFactory.build(config(new LinkedHashMap<>(Map.of(
                "address", addr, "path", path + "-other", "tokenFile", "vault-token"))), BuildContext.defaults(dir)));
        assertTrue(denied.getMessage().contains("HTTP 403"), denied.getMessage());

        // A path that does not exist, read with a token that could read it.
        Files.writeString(dir.resolve("admin-token"), admin);
        ConfigException missing = assertThrows(ConfigException.class, () -> GatewayFactory.build(config(new LinkedHashMap<>(Map.of(
                "address", addr, "path", path + "-absent", "tokenFile", "admin-token"))), BuildContext.defaults(dir)));
        assertTrue(missing.getMessage().contains("HTTP 404"), missing.getMessage());

        // A wrong token.
        Files.writeString(dir.resolve("bad-token"), "s.not-a-real-token");
        ConfigException bad = assertThrows(ConfigException.class, () -> GatewayFactory.build(config(new LinkedHashMap<>(Map.of(
                "address", addr, "path", path, "tokenFile", "bad-token"))), BuildContext.defaults(dir)));
        assertTrue(bad.getMessage().contains("HTTP 403"), bad.getMessage());
        assertTrue(!bad.getMessage().contains("not-a-real-token"), "the token is never echoed");
    }
}
