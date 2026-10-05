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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * OAuth scope minimization on the gateway's own listener (MCP authorization, scope challenge
 * handling; RFC 6750 section 3.1). A token that is valid but was granted less than the route needs
 * gets 403 {@code insufficient_scope} with the scope to request, never a silent admit.
 */
class ScopeEnforcementTest {

    @TempDir
    Path dir;

    static final String SECRET = "scope-enforcement-test-secret-0123456789";
    static final Map<String, String> ENV = Map.of("JWT_SECRET", SECRET, "DEV_TOKEN", "development-token-0123456789");
    static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    static final String RESOURCE = "https://aegis.example.com/mcp";

    private OwnServerSecuritySettings settings(String routes, String oauthScopes) {
        return OwnServerSecuritySettings.parse(Json.parseObject("""
                {"identity":{"type":"jwt","jwt":{"issuer":"https://idp.test","audience":"%s",
                   "algorithms":["HS256"],"hmacSecretEnv":"JWT_SECRET"}},
                 "routes":%s,
                 "oauth":{"resource":"%s","authorizationServers":["https://idp.test"],"scopesSupported":%s}}"""
                .formatted(RESOURCE, routes, RESOURCE, oauthScopes)), dir, ENV::get, Clock.systemUTC());
    }

    private OwnServerSecuritySettings standard() {
        return settings("{\"mcpGroups\":[\"mcp-users\"],\"mcpScopes\":[\"mcp.read\"],\"adminGroups\":[\"mcp-admins\"]}",
                "[\"mcp.read\",\"mcp.write\"]");
    }

    private static McpHttpRequest token(Map<String, Object> extraClaims) throws Exception {
        long now = Clock.systemUTC().instant().getEpochSecond();
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("iss", "https://idp.test");
        c.put("aud", RESOURCE);
        c.put("sub", "alice");
        c.put("groups", List.of("mcp-users", "mcp-admins"));
        c.put("iat", now);
        c.put("exp", now + 300);
        c.putAll(extraClaims);
        String input = B64.encodeToString(Json.writeBytes(Map.of("alg", "HS256", "typ", "JWT"))) + "."
                + B64.encodeToString(Json.writeBytes(c));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String jwt = input + "." + B64.encodeToString(mac.doFinal(input.getBytes(StandardCharsets.US_ASCII)));
        return new McpHttpRequest("POST", "", Map.of("Authorization", List.of("Bearer " + jwt)), new byte[0]);
    }

    @Test
    void aTokenCarryingTheRequiredScopeIsAdmitted() throws Exception {
        OwnServerPerimeter p = new OwnServerPerimeter(standard());
        OwnServerPerimeter.Admission ok = p.admit(OwnServerPerimeter.RouteGroup.MCP, token(Map.of("scope", "mcp.read mcp.write")));
        assertTrue(ok.allowed());
        assertEquals("alice", ok.caller().actor());
        assertTrue(p.admit(OwnServerPerimeter.RouteGroup.MCP, token(Map.of("scp", List.of("mcp.read")))).allowed(),
                "the scp array form some identity providers use is read when scope is absent");
    }

    @Test
    void aValidTokenWithoutTheScopeGetsAStepUpChallengeNotAnAdmit() throws Exception {
        OwnServerPerimeter p = new OwnServerPerimeter(standard());
        for (Map<String, Object> claims : List.<Map<String, Object>>of(
                Map.of(),                                   // no scope claim at all
                Map.of("scope", "mcp.write"),               // a different scope
                Map.of("scope", "mcp.read.all"),            // no prefix or pattern match
                Map.of("scope", "MCP.READ"),                // scopes are case sensitive (RFC 6749 3.3)
                Map.of("scope", "other", "scp", List.of("mcp.read")))) { // scope and scp are never merged
            OwnServerPerimeter.Admission a = p.admit(OwnServerPerimeter.RouteGroup.MCP, token(claims));
            assertFalse(a.allowed(), claims.toString());
            assertEquals(403, a.status(), claims.toString());
            assertEquals("Bearer realm=\"aegis\", error=\"insufficient_scope\", scope=\"mcp.read\", resource_metadata=\""
                    + "https://aegis.example.com/.well-known/oauth-protected-resource/mcp\"", a.headers().get("WWW-Authenticate"));
        }
    }

    @Test
    void the401ChallengeNamesTheScopeToRequestAndRoutesWithoutScopesAreUnchanged() throws Exception {
        OwnServerPerimeter p = new OwnServerPerimeter(standard());
        OwnServerPerimeter.Admission none = p.admit(OwnServerPerimeter.RouteGroup.MCP,
                new McpHttpRequest("POST", "", Map.of(), new byte[0]));
        assertEquals(401, none.status());
        assertTrue(none.headers().get("WWW-Authenticate").endsWith(", scope=\"mcp.read\""), none.headers().toString());

        // adminScopes is not configured: the admin route keeps plain group admission.
        OwnServerPerimeter.Admission admin = p.admit(OwnServerPerimeter.RouteGroup.ADMIN, token(Map.of()));
        assertTrue(admin.allowed());
        OwnServerPerimeter.Admission adminNone = p.admit(OwnServerPerimeter.RouteGroup.ADMIN,
                new McpHttpRequest("POST", "", Map.of(), new byte[0]));
        assertFalse(adminNone.headers().get("WWW-Authenticate").contains("scope="));
    }

    @Test
    void scopeAndGroupAreBothRequired() throws Exception {
        OwnServerPerimeter p = new OwnServerPerimeter(settings(
                "{\"mcpGroups\":[\"nobody\"],\"mcpScopes\":[\"mcp.read\"]}", "[\"mcp.read\"]"));
        OwnServerPerimeter.Admission a = p.admit(OwnServerPerimeter.RouteGroup.MCP, token(Map.of("scope", "mcp.read")));
        assertFalse(a.allowed(), "a scope never substitutes for route group membership");
        assertEquals(403, a.status());
    }

    @Test
    void misconfiguredScopesAreRejectedAtStartup() {
        ConfigException notPublished = assertThrows(ConfigException.class, () -> settings(
                "{\"mcpScopes\":[\"mcp.admin\"]}", "[\"mcp.read\"]"));
        assertTrue(notPublished.getMessage().contains("scopesSupported"), notPublished.getMessage());
        assertThrows(ConfigException.class, () -> settings("{\"mcpScopes\":[\"a\\\"b\"]}", "[]"),
                "a quote would break out of the WWW-Authenticate value");
        assertThrows(ConfigException.class, () -> settings("{\"mcpScopes\":[\"a b\"]}", "[]"));
        ConfigException staticTokens = assertThrows(ConfigException.class, () -> OwnServerSecuritySettings.parse(Json.parseObject("""
                {"identity":{"type":"static","static":{"tokens":[{"tokenEnv":"DEV_TOKEN","actor":"dev"}]}},
                 "routes":{"mcpScopes":["mcp.read"]}}"""), dir, ENV::get, Clock.systemUTC()));
        assertTrue(staticTokens.getMessage().contains("static tokens carry no scopes"), staticTokens.getMessage());
    }
}
