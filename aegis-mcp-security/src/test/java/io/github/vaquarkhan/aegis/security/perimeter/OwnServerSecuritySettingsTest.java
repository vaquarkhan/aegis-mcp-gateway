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
package io.github.vaquarkhan.aegis.security.perimeter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.config.ConfigException;
import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.model.Digests;
import io.github.vaquarkhan.aegis.mcp.http.McpHttpRequest;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Strict parsing and identity construction for the extracted own-server security configuration. */
class OwnServerSecuritySettingsTest {

    @TempDir
    Path dir;

    private static final Map<String, String> ENV = Map.of(
            "DEV_TOKEN", "dev-token-0123456789", "OPS_TOKEN", "ops-token-0123456789", "JWT_SECRET", "j".repeat(40));

    private static String staticConfig() {
        return """
                {"listen":{"host":"127.0.0.1","port":0},
                 "identity":{"type":"static","static":{"tokens":[
                   {"tokenEnv":"DEV_TOKEN","actor":"dev","groups":["dev"]},
                   {"sha256":"%s","actor":"ops","groups":["ops"]}]}},
                 "stdio":{"actor":"local","groups":["dev"]},
                 "metrics":{"path":"/prom","allowedGroups":["ops"]}}""".formatted(Digests.sha256Hex(ENV.get("OPS_TOKEN")));
    }

    @Test
    void staticSettingsResolveOnlyConfiguredBearerTokenDigests() {
        OwnServerSecuritySettings settings = OwnServerSecuritySettings.parse(Json.parseObject(staticConfig()), dir, ENV::get, Clock.systemUTC());
        assertEquals("/prom", settings.metricsPath());
        assertEquals("local", settings.stdioIdentity().actor());
        assertEquals("dev", settings.identity().resolve(bearer(ENV.get("DEV_TOKEN"))).actor());
        assertEquals("ops", settings.identity().resolve(bearer(ENV.get("OPS_TOKEN"))).actor());
        assertNull(settings.identity().resolve(bearer("not-a-token")));
        assertNull(settings.identity().resolve(new McpHttpRequest("POST", "", Map.of("Authorization", List.of("Basic ignored")), new byte[0])));
    }

    @Test
    void strictSettingsRejectInsecureOrAmbiguousPerimeterConfiguration() {
        for (String invalid : List.of(
                "{\"identity\":{\"type\":\"anonymous\"}}",
                "{\"identity\":{\"type\":\"static\",\"static\":{\"tokens\":[]}}}",
                "{\"identity\":{\"type\":\"jwt\",\"jwt\":{\"issuer\":\"i\",\"audience\":\"a\",\"algorithms\":[\"HS256\"],\"hmacSecretEnv\":\"UNSET\"}}}",
                "{\"identity\":{\"type\":\"static\",\"static\":{\"tokens\":[{\"tokenEnv\":\"DEV_TOKEN\",\"actor\":\"a\"}]}},\"cors\":{\"allowedOrigins\":[\"*\"]}}",
                "{\"identity\":{\"type\":\"static\",\"static\":{\"tokens\":[{\"tokenEnv\":\"DEV_TOKEN\",\"actor\":\"a\"}]}},\"metrics\":{\"path\":\"/mcp\"}}")) {
            assertThrows(ConfigException.class, () -> OwnServerSecuritySettings.parse(Json.parseObject(invalid), dir, ENV::get, Clock.systemUTC()), invalid);
        }
    }

    @Test
    void oauthMetadataRequiresAudienceBindingUnlessExplicitlyEscaped() {
        String host = """
                {"identity":{"type":"jwt","jwt":{"issuer":"https://idp.example","audience":"aegis",
                   "algorithms":["HS256"],"hmacSecretEnv":"JWT_SECRET"}},
                 "oauth":{"resource":"https://aegis.example/mcp","authorizationServers":["https://idp.example"]}}""";
        assertThrows(ConfigException.class, () -> OwnServerSecuritySettings.parse(Json.parseObject(host), dir, ENV::get, Clock.systemUTC()));
        String allowed = host.replace("\"authorizationServers\":[\"https://idp.example\"]",
                "\"authorizationServers\":[\"https://idp.example\"],\"allowAudienceMismatch\":true");
        assertNotNull(OwnServerSecuritySettings.parse(Json.parseObject(allowed), dir, ENV::get, Clock.systemUTC()).oauth());
        // The explicit escape is still a known key when the audience already matches.
        String matching = allowed.replace("\"audience\":\"aegis\"", "\"audience\":\"https://aegis.example/mcp\"");
        assertNotNull(OwnServerSecuritySettings.parse(Json.parseObject(matching), dir, ENV::get, Clock.systemUTC()).oauth());
        // Without a resource nothing is published, so the other oauth keys are refused instead of ignored.
        String inert = host.replace("\"resource\":\"https://aegis.example/mcp\",", "");
        ConfigException e = assertThrows(ConfigException.class,
                () -> OwnServerSecuritySettings.parse(Json.parseObject(inert), dir, ENV::get, Clock.systemUTC()));
        org.junit.jupiter.api.Assertions.assertTrue(
                e.getMessage().contains("config.host.oauth.authorizationServers has no effect without config.host.oauth.resource"),
                e.getMessage());
    }

    private static McpHttpRequest bearer(String token) {
        return new McpHttpRequest("POST", "", Map.of("Authorization", List.of("Bearer " + token)), new byte[0]);
    }
}
