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
package io.github.vaquarkhan.aegis.knox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.knox.rs.AegisMcpResource;
import io.github.vaquarkhan.aegis.mcp.testing.TestMcpServer;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivilegedAction;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import javax.security.auth.Subject;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Response;
import org.apache.knox.gateway.security.ActorChainPrincipal;
import org.apache.knox.gateway.security.GroupPrincipal;
import org.apache.knox.gateway.security.ImpersonatedPrincipal;
import org.apache.knox.gateway.security.PrimaryPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit-level proof of the Knox front door against the exact Knox 3.0.0 SPI classes: identity comes
 * from Knox's Subject, and the MCP request is governed by the same engine as the gateway's own front door.
 * (The cold-install test in a real Knox 3.0.0 distribution is documented in VALIDATION.md.)
 */
class KnoxExtensionTest {

    @TempDir
    Path dir;

    static Subject subject(String primary, String impersonated, List<String> groups, List<String> actors) {
        Subject s = new Subject();
        s.getPrincipals().add(new PrimaryPrincipal(primary));
        if (impersonated != null) {
            s.getPrincipals().add(new ImpersonatedPrincipal(impersonated));
        }
        for (String g : groups) {
            s.getPrincipals().add(new GroupPrincipal(g));
        }
        if (!actors.isEmpty()) {
            s.getPrincipals().add(new ActorChainPrincipal() {
                @Override
                public List<Map<String, Object>> getActorChain() {
                    return actors.stream().map(a -> Map.<String, Object>of("sub", a)).toList();
                }

                @Override
                public String getCurrentActor() {
                    return actors.get(0);
                }

                @Override
                public String getOriginalDelegator() {
                    return primary;
                }

                @Override
                public String getName() {
                    return "actor-chain";
                }
            });
        }
        return s;
    }

    @Test
    void identityComesFromTheKnoxSubject() {
        CallerIdentity id = KnoxIdentityBridge.fromSubject(
                subject("guest", "alice", List.of("analysts", "operators"), List.of("agent-7")), "sandbox");
        assertEquals("guest", id.actor());
        assertEquals("alice", id.effectiveSubject());
        assertEquals(java.util.Set.of("analysts", "operators"), id.groups());
        assertEquals("knox:sandbox", id.issuer());
        assertEquals(List.of("agent-7"), id.actorChain());
        assertNull(KnoxIdentityBridge.fromSubject(new Subject(), "sandbox"), "no principal: not authenticated");
        assertNull(KnoxIdentityBridge.fromSubject(null, "sandbox"));
    }

    @Test
    void contributorUsesTheNativeServicePattern() {
        AegisKnoxServiceContributor c = new AegisKnoxServiceContributor();
        assertEquals("AEGIS_MCP", c.getRole());
        assertEquals("aegis-mcp", c.getName());
        assertTrue(new String(readService(), StandardCharsets.UTF_8).contains(AegisKnoxServiceContributor.class.getName()));
    }

    private static byte[] readService() {
        try (var in = KnoxExtensionTest.class.getResourceAsStream(
                "/META-INF/services/org.apache.knox.gateway.deploy.ServiceDeploymentContributor")) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void resourceGovernsAnExistingMcpServerWithKnoxIdentity() throws Exception {
        try (TestMcpServer mcp = new TestMcpServer(TestMcpServer.Mode.MODERN)) {
            mcp.tool("list_jobs", "List jobs.", Map.of("type", "object"), a -> TestMcpServer.textResult("job-1 RUNNING"));
            mcp.tool("delete_cluster", "Delete the cluster.", Map.of("type", "object"), a -> TestMcpServer.textResult("gone"));
            Path cfg = dir.resolve("aegis.json");
            Files.writeString(cfg, """
                    {"access": {"writeGroups": ["operators"], "auditVerifierGroups": ["audit-verifiers"]},
                     "policy": {"rules": [{"id": "read", "groups": ["analysts"], "classes": ["READ"]}]},
                     "egress": {"allowCidrs": ["127.0.0.0/8"]},
                     "audit": {"file": "audit.jsonl"},
                     "sources": [{"id": "engine", "type": "mcp", "url": "%s", "tools": {"list_jobs": {"class": "READ"}}}]}
                    """.formatted(mcp.uri()));
            ServletContext sc = servletContext(Map.of(KnoxGatewayRegistry.CONFIG_PARAM, cfg.toString()));
            try {
                String list = Json.write(Map.of("jsonrpc", "2.0", "id", 1L, "method", "tools/call", "params",
                        Map.of("name", "list_jobs", "_meta", Map.of("io.modelcontextprotocol/protocolVersion", "2026-07-28"))));
                Map<String, String> headers = Map.of("MCP-Protocol-Version", "2026-07-28", "Mcp-Method", "tools/call", "Mcp-Name", "list_jobs");
                AegisMcpResource r = resource(sc, headers);
                Response ok = Subject.doAs(subject("guest", null, List.of("analysts"), List.of()),
                        (PrivilegedAction<Response>) () -> r.post(new ByteArrayInputStream(list.getBytes(StandardCharsets.UTF_8))));
                assertEquals(200, ok.getStatus());
                assertTrue(new String((byte[]) ok.getEntity(), StandardCharsets.UTF_8).contains("job-1 RUNNING"));

                // Same payload shape, destructive tool: the Knox ACL layer decides at the route and does not inspect the body; Aegis does.
                String del = list.replace("list_jobs", "delete_cluster");
                AegisMcpResource r2 = resource(sc, Map.of("MCP-Protocol-Version", "2026-07-28", "Mcp-Method", "tools/call", "Mcp-Name", "delete_cluster"));
                Response denied = Subject.doAs(subject("guest", null, List.of("analysts"), List.of()),
                        (PrivilegedAction<Response>) () -> r2.post(new ByteArrayInputStream(del.getBytes(StandardCharsets.UTF_8))));
                String body = new String((byte[]) denied.getEntity(), StandardCharsets.UTF_8);
                assertTrue(body.contains("READONLY_CALLER"), body);

                // No Knox subject (misconfigured topology without authentication): 401, never anonymous.
                Response anon = resource(sc, headers).post(new ByteArrayInputStream(list.getBytes(StandardCharsets.UTF_8)));
                assertEquals(401, anon.getStatus());

                // Metrics behind the same provider chain: counted decisions, Prometheus content type; 401 without a subject.
                AegisMcpResource rm = resource(sc, Map.of());
                Response metrics = Subject.doAs(subject("guest", null, List.of("analysts"), List.of()),
                        (PrivilegedAction<Response>) rm::metrics);
                assertEquals(200, metrics.getStatus());
                String text = new String((byte[]) metrics.getEntity(), StandardCharsets.UTF_8);
                assertTrue(text.contains("method=\"list_jobs\",source=\"engine\",outcome=\"ALLOWED\"} 1"), text);
                assertTrue(String.valueOf(metrics.getMetadata().getFirst("Content-Type")).startsWith("text/plain; version=0.0.4"));
                assertEquals(401, resource(sc, Map.of()).metrics().getStatus());

                // Admin routes and health through the Knox resource.
                AegisMcpResource ra = resource(sc, Map.of());
                Response cat = Subject.doAs(subject("guest", null, List.of("analysts"), List.of()),
                        (PrivilegedAction<Response>) () -> ra.adminGet("catalog"));
                assertEquals(403, cat.getStatus(), "catalog is for approver groups only");
                Response auditDenied = Subject.doAs(subject("guest", null, List.of("analysts"), List.of()),
                        (PrivilegedAction<Response>) () -> ra.adminGet("audit/verify"));
                assertEquals(403, auditDenied.getStatus(), "analyst is not an audit verifier");
                Response auditOk = Subject.doAs(subject("vera", null, List.of("audit-verifiers"), List.of()),
                        (PrivilegedAction<Response>) () -> ra.adminGet("audit/verify"));
                assertEquals(200, auditOk.getStatus());
                assertEquals(Map.of("verified", true), Json.parseObject(new String((byte[]) auditOk.getEntity(), StandardCharsets.UTF_8)));
                AegisMcpResource rp = resource(sc, Map.of());
                Response post = Subject.doAs(subject("guest", null, List.of("analysts"), List.of()),
                        (PrivilegedAction<Response>) () -> rp.adminPost("approvals/none/approve",
                                new ByteArrayInputStream(new byte[0])));
                assertTrue(post.getStatus() >= 400, "unknown approval");
                assertEquals(200, resource(sc, Map.of()).health().getStatus());
            } finally {
                new AegisKnoxLifecycle().contextDestroyed(new javax.servlet.ServletContextEvent(sc));
            }
        }
    }

    @Test
    void badConfigMakesOnlyTheAegisRouteUnavailable() throws Exception {
        ServletContext sc = servletContext(Map.of(KnoxGatewayRegistry.CONFIG_PARAM, dir.resolve("missing.json").toString()));
        try {
            AegisMcpResource r = resource(sc, Map.of());
            Response health = r.health();
            assertEquals(503, health.getStatus());
            String healthBody = new String((byte[]) health.getEntity(), StandardCharsets.UTF_8);
            assertEquals(Map.of("status", "unavailable", "reason", "Aegis configuration is unavailable"),
                    Json.parseObject(healthBody));
            assertFalse(healthBody.contains("missing.json") || healthBody.contains(dir.toString()), healthBody);
            assertEquals(503, r.post(new ByteArrayInputStream(new byte[0])).getStatus());
        } finally {
            KnoxGatewayRegistry.close(sc);
        }
        ServletContext unset = servletContext(Map.of());
        try {
            Response health = resource(unset, Map.of()).health();
            assertEquals(503, health.getStatus());
            assertEquals(Map.of("status", "unavailable", "reason", "Aegis configuration is unavailable"),
                    Json.parseObject(new String((byte[]) health.getEntity(), StandardCharsets.UTF_8)));
        } finally {
            KnoxGatewayRegistry.close(unset);
        }
    }

    // ----------------------------------------------------------------- fakes (JDK proxies, no container)

    static ServletContext servletContext(Map<String, String> initParams) {
        return (ServletContext) Proxy.newProxyInstance(KnoxExtensionTest.class.getClassLoader(), new Class<?>[] {ServletContext.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getInitParameter" -> initParams.get((String) a[0]);
                    case "getContextPath" -> "/gateway/sandbox";
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == a[0];
                    case "toString" -> "FakeServletContext";
                    default -> null;
                });
    }

    static HttpServletRequest request(Map<String, String> headers) {
        return (HttpServletRequest) Proxy.newProxyInstance(KnoxExtensionTest.class.getClassLoader(), new Class<?>[] {HttpServletRequest.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getHeaderNames" -> Collections.enumeration(headers.keySet());
                    case "getHeaders" -> headers.containsKey((String) a[0])
                            ? new Vector<>(List.of(headers.get((String) a[0]))).elements() : Collections.emptyEnumeration();
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == a[0];
                    default -> null;
                });
    }

    static AegisMcpResource resource(ServletContext sc, Map<String, String> headers) throws Exception {
        AegisMcpResource r = new AegisMcpResource();
        inject(r, "servletContext", sc);
        inject(r, "request", request(headers));
        return r;
    }

    static void inject(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
