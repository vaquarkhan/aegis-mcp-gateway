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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.config.ConfigException;
import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.mcp.http.McpHttpRequest;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Direct tests for host-neutral own-listener admission and browser policy. */
class OwnServerPerimeterTest {

    @TempDir
    Path dir;

    private static final Map<String, String> ENV = Map.of("DEV_TOKEN", "development-token-0123456789");

    private OwnServerSecuritySettings settings() {
        return OwnServerSecuritySettings.parse(Json.parseObject("""
                {"listen":{"host":"127.0.0.1","port":0},
                 "identity":{"type":"static","static":{"tokens":[
                   {"tokenEnv":"DEV_TOKEN","actor":"dev","groups":["mcp-users"]}]}},
                 "routes":{"mcpGroups":["mcp-users"],"adminGroups":["mcp-admins"]},
                 "cors":{"allowedOrigins":["https://console.example"],"allowedMethods":["POST"],
                   "allowedHeaders":["Authorization","Content-Type"],"exposedHeaders":["Mcp-Session-Id"],
                   "maxAgeSeconds":300,"allowCredentials":true}}"""), dir, ENV::get, Clock.systemUTC());
    }

    private static McpHttpRequest bearer(String token) {
        return new McpHttpRequest("POST", "", Map.of("Authorization", List.of("Bearer " + token)), new byte[0]);
    }

    @Test
    void routeAdmissionUsesIdentityAndRouteGroupBeforeAnyBodyIsNeeded() {
        OwnServerPerimeter p = new OwnServerPerimeter(settings());
        OwnServerPerimeter.Admission mcp = p.admit(OwnServerPerimeter.RouteGroup.MCP, bearer(ENV.get("DEV_TOKEN")));
        assertTrue(mcp.allowed());
        assertEquals("dev", mcp.caller().actor());
        assertFalse(p.admit(OwnServerPerimeter.RouteGroup.ADMIN, bearer(ENV.get("DEV_TOKEN"))).allowed());
        OwnServerPerimeter.Admission none = p.admit(OwnServerPerimeter.RouteGroup.MCP,
                new McpHttpRequest("POST", "", Map.of(), new byte[0]));
        assertFalse(none.allowed());
        assertEquals(401, none.status());
        assertTrue(none.headers().get("WWW-Authenticate").startsWith("Bearer"));
    }

    @Test
    void corsPolicyUsesExactOriginsAndHeaderNormalization() {
        OwnServerPerimeter p = new OwnServerPerimeter(settings());
        OwnServerPerimeter.Preflight ok = p.preflight("https://console.example", "post", "authorization, CONTENT-TYPE");
        assertTrue(ok.allowed());
        assertEquals("https://console.example", ok.headers().get("Access-Control-Allow-Origin"));
        assertEquals("true", ok.headers().get("Access-Control-Allow-Credentials"));
        assertTrue(p.responseCorsHeaders("https://console.example").containsKey("Access-Control-Expose-Headers"));
        assertFalse(p.preflight("https://attacker.example", "POST", "Authorization").allowed());
        assertFalse(p.preflight("https://console.example", "DELETE", "Authorization").allowed());
        assertFalse(p.preflight("https://console.example", "POST", "X-Not-Allowed").allowed());
        assertTrue(p.responseCorsHeaders("https://attacker.example").isEmpty());
    }

    @Test
    void strictSettingsRejectWildcardAndUnknownSecurityKeys() {
        assertThrows(ConfigException.class, () -> OwnServerSecuritySettings.parse(Json.parseObject("""
                {"identity":{"type":"static","static":{"tokens":[{"tokenEnv":"DEV_TOKEN","actor":"dev"}]}},
                 "cors":{"allowedOrigins":["*"]}}"""), dir, ENV::get, Clock.systemUTC()));
        assertThrows(ConfigException.class, () -> OwnServerSecuritySettings.parse(Json.parseObject("""
                {"identity":{"type":"static","static":{"tokens":[{"tokenEnv":"DEV_TOKEN","actor":"dev"}]}},
                 "routes":{"mcpGrups":["mcp-users"]}}"""), dir, ENV::get, Clock.systemUTC()));
    }
}
