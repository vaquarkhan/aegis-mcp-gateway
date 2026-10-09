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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.knox.rs.AegisMcpResource;
import io.github.vaquarkhan.aegis.mcp.testing.TestMcpServer;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.security.auth.Subject;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.Response;
import org.apache.knox.gateway.filter.AclsAuthorizationFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Executes the shipped Apache Knox 3.0.0 {@code AclsAuthorizationFilter} (the {@code AclsAuthz}
 * provider) in front of the real Aegis Knox resource, to prove two claims instead of describing them.
 *
 * <ol>
 *   <li><b>Knox route authorization does not inspect the MCP request body.</b> MCP sends every tool call as {@code POST /mcp}
 *       with the tool name and arguments in a JSON-RPC body. The ACL filter decides on user, group and
 *       IP only: it admits a harmless {@code list_jobs} call and a {@code delete_cluster} call
 *       identically, and it never opens the request body or reads a parameter (tripwires count every
 *       attempt).</li>
 *   <li><b>Aegis, not Knox, prevents the confused deputy.</b> A user Knox admitted to the route still
 *       cannot make the gateway run a destructive tool with the gateway's own backend credential: Aegis
 *       reads the body, denies the call before execution, and the backend never sees it. The caller's
 *       Knox credential is never forwarded downstream either.</li>
 * </ol>
 *
 * Only the servlet container is replaced by JDK proxies; the Knox ACL code, the Aegis resource, the
 * Aegis engine and the downstream MCP HTTP exchange are the real implementations.
 */
class KnoxAclRouteScopeTest {

    @TempDir
    Path dir;

    /** What the ACL filter did with one request. */
    record Gate(boolean admitted, int bodyReads, Integer errorStatus, Response aegis) {}

    @Test
    void knoxAclAdmitsByIdentityOnlyAndAegisDeniesTheDestructiveCallInTheBody() throws Exception {
        try (TestMcpServer backend = new TestMcpServer(TestMcpServer.Mode.MODERN)) {
            backend.tool("list_jobs", "List jobs.", Map.of("type", "object"), a -> TestMcpServer.textResult("job-1 RUNNING"));
            backend.tool("delete_cluster", "Delete the cluster.", Map.of("type", "object"), a -> TestMcpServer.textResult("gone"));
            Path cfg = dir.resolve("aegis.json");
            Files.writeString(cfg, """
                    {"access": {"writeGroups": ["operators"]},
                     "policy": {"rules": [{"id": "read", "groups": ["analysts"], "classes": ["READ"]}]},
                     "egress": {"allowCidrs": ["127.0.0.0/8"]},
                     "audit": {"file": "audit.jsonl"},
                     "sources": [{"id": "engine", "type": "mcp", "url": "%s",
                                  "credentials": {"type": "bearer", "tokenEnv": "ENGINE_TOKEN"},
                                  "tools": {"list_jobs": {"class": "READ"}}}]}
                    """.formatted(backend.uri()));
            ServletContext sc = KnoxExtensionTest.servletContext(Map.of(KnoxGatewayRegistry.CONFIG_PARAM, cfg.toString()));

            // The real Knox provider, configured exactly like a topology's AclsAuthz section:
            // users guest and sam, any group, any IP.
            AclsAuthorizationFilter acl = new AclsAuthorizationFilter();
            acl.init(filterConfig(Map.of("resource.role", "AEGIS_MCP", "aegis_mcp.acl", "guest,sam;*;*")));

            Subject guest = KnoxExtensionTest.subject("guest", null, List.of("analysts"), List.of());
            Subject mallory = KnoxExtensionTest.subject("mallory", null, List.of("analysts"), List.of());
            try {
                Gate read = gate(acl, sc, guest, call("list_jobs"));
                Gate destructive = gate(acl, sc, guest, call("delete_cluster"));
                Gate outsider = gate(acl, sc, mallory, call("list_jobs"));

                // Claim 1: the Knox decision is identical for both tools and never touches the body.
                assertTrue(read.admitted() && destructive.admitted(),
                        "Knox ACL admits the route for an allowed user regardless of the MCP tool");
                assertEquals(0, read.bodyReads() + destructive.bodyReads() + outsider.bodyReads(),
                        "Knox ACL never reads the JSON-RPC body or a request parameter");
                assertFalse(outsider.admitted(), "a user outside the ACL is refused at the route");
                assertEquals(403, outsider.errorStatus());

                // Claim 2: Aegis governs what Knox admitted.
                assertEquals(200, read.aegis().getStatus());
                assertTrue(text(read.aegis()).contains("job-1 RUNNING"), text(read.aegis()));
                String denied = text(destructive.aegis());
                assertTrue(denied.contains("READONLY_CALLER"), denied);

                List<String> executed = new ArrayList<>();
                synchronized (backend.received) {
                    for (Map<String, Object> m : backend.received) {
                        if ("tools/call".equals(m.get("method")) && m.get("params") instanceof Map<?, ?> p) {
                            executed.add(String.valueOf(p.get("name")));
                        }
                    }
                }
                assertEquals(List.of("list_jobs"), executed,
                        "the destructive call Knox admitted never reached the backend");
                Set<String> credentials = new java.util.HashSet<>();
                synchronized (backend.receivedHeaders) {
                    for (Map<String, String> h : backend.receivedHeaders) {
                        credentials.add(String.valueOf(h.get("authorization")));
                    }
                }
                assertEquals(Set.of("Bearer engine-service-token"), credentials,
                        "every backend request carried the gateway's service credential and never the Knox user's Basic credential");
            } finally {
                acl.destroy();
                new AegisKnoxLifecycle().contextDestroyed(new javax.servlet.ServletContextEvent(sc));
            }
        }
    }

    private static byte[] call(String tool) {
        return Json.write(Map.of("jsonrpc", "2.0", "id", 1L, "method", "tools/call", "params",
                Map.of("name", tool, "_meta", Map.of("io.modelcontextprotocol/protocolVersion", "2026-07-28"))))
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Runs one request through the Knox ACL filter; an admitted request continues to the Aegis resource. */
    private Gate gate(AclsAuthorizationFilter acl, ServletContext sc, Subject subject, byte[] body) throws Exception {
        String tool = (String) ((Map<?, ?>) Json.parseObject(new String(body, StandardCharsets.UTF_8)).get("params")).get("name");
        Map<String, String> headers = Map.of("MCP-Protocol-Version", "2026-07-28", "Mcp-Method", "tools/call",
                "Mcp-Name", tool, "Authorization", "Basic Z3Vlc3Q6Z3Vlc3QtcGFzc3dvcmQ=");
        AtomicInteger bodyReads = new AtomicInteger();
        HttpServletRequest req = tripwireRequest(headers, bodyReads);
        Integer[] error = new Integer[1];
        HttpServletResponse res = (HttpServletResponse) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {HttpServletResponse.class}, (p, m, a) -> {
                    if (m.getName().equals("sendError")) {
                        error[0] = (Integer) a[0];
                    }
                    return m.getReturnType() == boolean.class ? false : null;
                });
        Response[] aegis = new Response[1];
        boolean[] admitted = new boolean[1];
        FilterChain chain = (rq, rs) -> {
            admitted[0] = true;
            try {
                // Knox's Jersey dispatch hands the entity stream to the resource after the provider chain.
                AegisMcpResource r = KnoxExtensionTest.resource(sc, headers);
                aegis[0] = r.post(new ByteArrayInputStream(body));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        Subject.doAs(subject, (PrivilegedExceptionAction<Void>) () -> {
            acl.doFilter(req, res, chain);
            return null;
        });
        return new Gate(admitted[0], bodyReads.get(), error[0], aegis[0]);
    }

    /** A request whose body and parameters may not be read by the filter: each attempt is counted. */
    private HttpServletRequest tripwireRequest(Map<String, String> headers, AtomicInteger bodyReads) {
        Set<String> bodyMethods = Set.of("getInputStream", "getReader", "getParameter", "getParameterMap",
                "getParameterNames", "getParameterValues", "getPart", "getParts");
        return (HttpServletRequest) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {HttpServletRequest.class},
                (p, m, a) -> {
                    if (bodyMethods.contains(m.getName())) {
                        bodyReads.incrementAndGet();
                        return null;
                    }
                    return switch (m.getName()) {
                        case "getRemoteAddr" -> "127.0.0.1";
                        case "getMethod" -> "POST";
                        case "getRequestURI" -> "/gateway/sandbox/aegis-mcp/v1/mcp";
                        case "getHeader" -> headers.get((String) a[0]);
                        case "getHeaderNames" -> Collections.enumeration(headers.keySet());
                        case "hashCode" -> System.identityHashCode(p);
                        case "equals" -> p == a[0];
                        default -> null;
                    };
                });
    }

    private FilterConfig filterConfig(Map<String, String> params) {
        return (FilterConfig) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {FilterConfig.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getInitParameter" -> params.get((String) a[0]);
                    case "getInitParameterNames" -> Collections.enumeration(params.keySet());
                    case "getFilterName" -> "AclsAuthz";
                    default -> null;
                });
    }

    private static String text(Response r) {
        return r.getEntity() == null ? "" : new String((byte[]) r.getEntity(), StandardCharsets.UTF_8);
    }
}
