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
/*
 * Threat-model reproduction suite (transport layer). Replays the attack behind public MCP transport CVEs
 * against the gateway's own MCP server handler. Verdict lines: THREATMODEL|id|refs|class|verdict|detail
 */
package io.github.vaquarkhan.aegis.mcp.server;

import static io.github.vaquarkhan.aegis.mcp.testing.TestEngines.ALICE;
import static io.github.vaquarkhan.aegis.mcp.testing.TestEngines.OLGA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.json.JsonLimits;
import io.github.vaquarkhan.aegis.engine.json.JsonValues;
import io.github.vaquarkhan.aegis.engine.model.ToolClass;
import io.github.vaquarkhan.aegis.engine.model.ToolDescriptor;
import io.github.vaquarkhan.aegis.engine.model.ToolResult;
import io.github.vaquarkhan.aegis.engine.spi.ExecutionContext;
import io.github.vaquarkhan.aegis.engine.spi.ToolExecutor;
import io.github.vaquarkhan.aegis.engine.spi.ToolSource;
import io.github.vaquarkhan.aegis.mcp.http.McpHttpRequest;
import io.github.vaquarkhan.aegis.mcp.http.McpHttpResponse;
import io.github.vaquarkhan.aegis.mcp.testing.TestEngines;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ThreatModelCveTransportTest {

  static void verdict(String id, String refs, String cls, String verdict, String detail) {
    System.out.println("THREATMODEL|" + id + "|" + refs + "|" + cls + "|" + verdict + "|" + detail);
  }

  private McpServerHandler handler;
  private McpServerHandler smallLimits;
  private int backendCalls;

  @BeforeEach
  void setUp() throws Exception {
    ToolExecutor echo =
        new ToolExecutor() {
          @Override
          public List<java.net.URI> egressTargets(Map<String, Object> a) {
            return List.of();
          }

          @Override
          public ToolResult execute(ExecutionContext c) {
            backendCalls++;
            return ToolResult.text("echo:" + c.arguments().get("text"), false);
          }
        };
    ToolSource src =
        new ToolSource() {
          @Override
          public String id() {
            return "local";
          }

          @Override
          public List<SourcedTool> discover() {
            return List.of(
                new SourcedTool(
                    new ToolDescriptor(
                        "echo",
                        null,
                        "Echo.",
                        Json.parseObject(
                            "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}"),
                        null,
                        null),
                    echo));
          }
        };
    TestEngines.Built b = TestEngines.build(List.of(src), Map.of("echo", ToolClass.READ));
    handler =
        new McpServerHandler(
            b.engine,
            ServerOptions.defaults(),
            new SessionRegistry(Clock.systemUTC(), 60_000, 100));
    smallLimits =
        new McpServerHandler(
            b.engine,
            new ServerOptions(
                null,
                null,
                null,
                true,
                Set.of("https://app.example"),
                0,
                null,
                new JsonLimits(2048, 8, 1024, 64, 32, 50, 200)),
            new SessionRegistry(Clock.systemUTC(), 60_000, 100));
  }

  private static Map<String, Object> modern(Map<String, Object> extra) {
    Map<String, Object> p = new LinkedHashMap<>(extra);
    p.put("_meta", Map.of("io.modelcontextprotocol/protocolVersion", "2026-07-28"));
    return p;
  }

  private static McpHttpRequest post(byte[] body, Map<String, String> headers) {
    Map<String, List<String>> h = new LinkedHashMap<>();
    headers.forEach((k, v) -> h.put(k, List.of(v)));
    return new McpHttpRequest("POST", "", h, body);
  }

  private static McpHttpRequest post(Map<String, Object> body, Map<String, String> headers) {
    return post(Json.writeBytes(body), headers);
  }

  private static Map<String, Object> rpc(String method, Map<String, Object> params) {
    return Map.of("jsonrpc", "2.0", "id", 7L, "method", method, "params", params);
  }

  private static final Map<String, String> MODERN_HEADERS =
      Map.of("MCP-Protocol-Version", "2026-07-28", "Mcp-Method", "tools/call", "Mcp-Name", "echo");

  private static Map<String, Object> echoCall() {
    return rpc("tools/call", modern(Map.of("name", "echo", "arguments", Map.of("text", "hi"))));
  }

  // ------------------------------------------------------------------ TM-06 no authentication

  @Test
  void tm06_unauthenticatedRequest_is401() {
    McpHttpResponse r = handler.handleHttp(post(echoCall(), MODERN_HEADERS), null);
    assertEquals(401, r.status(), r.bodyText());
    assertEquals(0, backendCalls);
    verdict(
        "TM-06",
        "CVE-2025-49596 (MCP Inspector unauth RCE); arXiv 2608.00150 91.8% servers without OAuth; Jan-2026 scan 1,862 open servers",
        "unauthenticated access to an MCP server",
        "BLOCKED",
        "handler answers 401 for a request with no host-established identity; config refuses anonymous identity types");
  }

  // ------------------------------------------------------------------ TM-07 DNS rebinding /
  // browser origin

  @Test
  void tm07_dnsRebindingBrowserOrigin_is403EvenWhenAuthenticated() {
    Map<String, String> h = new LinkedHashMap<>(MODERN_HEADERS);
    h.put("Origin", "http://attacker-rebind.example:8080");
    McpHttpResponse r = handler.handleHttp(post(echoCall(), h), ALICE);
    assertEquals(403, r.status(), r.bodyText());
    // Even a configured allow list admits only exact origins.
    h.put("Origin", "https://app.example.evil");
    assertEquals(403, smallLimits.handleHttp(post(echoCall(), h), ALICE).status());
    h.put("Origin", "https://app.example");
    assertEquals(200, smallLimits.handleHttp(post(echoCall(), h), ALICE).status());
    verdict(
        "TM-07",
        "CVE-2025-35028 (SDK DNS rebinding off by default); CVE-2026-81092 (mcp-go); CVE-2026-32632; CVE-2026-55637; CVE-2026-81315",
        "DNS rebinding via browser against a local MCP listener",
        "BLOCKED",
        "any Origin not in the exact allow list -> 403 before dispatch; default allow list is empty");
  }

  // ------------------------------------------------------------------ TM-08 session binding

  @Test
  void tm08_sessionIdOfAnotherCaller_isRejected() {
    Map<String, Object> init =
        rpc(
            "initialize",
            Map.of(
                "protocolVersion",
                "2025-06-18",
                "capabilities",
                Map.of(),
                "clientInfo",
                Map.of("name", "t", "version", "1")));
    McpHttpResponse created = handler.handleHttp(post(init, Map.of()), ALICE);
    assertEquals(200, created.status(), created.bodyText());
    String sid = created.headers().get("Mcp-Session-Id");
    assertNotNull(sid);
    Map<String, Object> list = rpc("tools/list", Map.of());
    McpHttpResponse owner =
        handler.handleHttp(
            post(list, Map.of("Mcp-Session-Id", sid, "MCP-Protocol-Version", "2025-06-18")), ALICE);
    assertEquals(200, owner.status(), owner.bodyText());
    McpHttpResponse thief =
        handler.handleHttp(
            post(list, Map.of("Mcp-Session-Id", sid, "MCP-Protocol-Version", "2025-06-18")), OLGA);
    assertEquals(404, thief.status(), thief.bodyText());
    verdict(
        "TM-08",
        "CVE-2026-67431 (MCP Ruby SDK session poisoning, no owner binding)",
        "session id reuse by a different principal",
        "BLOCKED",
        "session bound to principalKey (issuer, actor, subject); other caller gets 404; ids are 256-bit SecureRandom");
  }

  // ------------------------------------------------------------------ TM-09 malformed / oversized
  // bodies

  @Test
  void tm09_oversizedAndMalformedBodies_areRefusedWithoutCrash() {
    byte[] big =
        ("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\",\"arguments\":{\"text\":\""
                + "A".repeat(4000)
                + "\"}}}")
            .getBytes(StandardCharsets.UTF_8);
    McpHttpResponse r = smallLimits.handleHttp(post(big, MODERN_HEADERS), ALICE);
    assertEquals(413, r.status(), r.bodyText());

    McpHttpResponse garbage =
        handler.handleHttp(
            post("{\"jsonrpc\":\"2.0\",\"id\":".getBytes(StandardCharsets.UTF_8), MODERN_HEADERS),
            ALICE);
    assertEquals(400, garbage.status(), garbage.bodyText());

    StringBuilder deep =
        new StringBuilder(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\",\"arguments\":");
    for (int i = 0; i < 500; i++) {
      deep.append("{\"a\":");
    }
    deep.append("1");
    for (int i = 0; i < 500; i++) {
      deep.append('}');
    }
    deep.append("}}");
    McpHttpResponse nested =
        handler.handleHttp(
            post(deep.toString().getBytes(StandardCharsets.UTF_8), MODERN_HEADERS), ALICE);
    assertTrue(
        nested.status() == 400 || nested.status() == 413,
        nested.status() + " " + nested.bodyText());

    McpHttpResponse batch =
        handler.handleHttp(
            post(
                "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}]"
                    .getBytes(StandardCharsets.UTF_8),
                Map.of()),
            ALICE);
    assertEquals(400, batch.status());

    McpHttpResponse dup =
        handler.handleHttp(
            post(
                ("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"method\":\"tools/list\","
                        + "\"params\":{}}")
                    .getBytes(StandardCharsets.UTF_8),
                Map.of()),
            ALICE);
    assertEquals(400, dup.status(), dup.bodyText());
    assertEquals(0, backendCalls);
    verdict(
        "TM-09",
        "CVE-2026-67432 (Ruby SDK unbounded JSON-RPC body); CVE-2026-53965 (PHP SDK unbounded SSE buffer); CVE-2025-53366 (FastMCP validation DoS)",
        "oversized, deeply nested, batched, duplicate-key or malformed JSON-RPC",
        "BLOCKED",
        "413 over maxBytes; 400 on malformed, depth, batch, duplicate keys; handler never throws");
  }

  // ------------------------------------------------------------------ TM-16 header/body desync

  @Test
  void tm16_headerBodyDisagreement_isRefused() {
    Map<String, Object> call =
        rpc("tools/call", modern(Map.of("name", "echo", "arguments", Map.of("text", "hi"))));
    Map<String, String> h = new LinkedHashMap<>(MODERN_HEADERS);
    h.put("Mcp-Name", "harmless_tool");
    McpHttpResponse r = handler.handleHttp(post(call, h), ALICE);
    assertEquals(400, r.status(), r.bodyText());
    h.put("Mcp-Name", "echo");
    h.put("Mcp-Method", "tools/list");
    assertEquals(400, handler.handleHttp(post(call, h), ALICE).status());
    assertEquals(0, backendCalls);
    verdict(
        "TM-16",
        "MCP 2026-07-28 'Server Validation'; proxy-routing desync class",
        "intermediary routes on header while server executes body",
        "BLOCKED",
        "Mcp-Name / Mcp-Method must be single-valued and equal the body -> 400 HEADER_MISMATCH");
  }

  // ------------------------------------------------------------------ second-pass items

  @Test
  void open03_adminRoutesAreGatedPerRouteEvenWithoutAdminGroups() throws Exception {
    // Re-examination of the "admin API shares the MCP listener" finding: with adminGroups unset the
    // perimeter admits any authenticated caller to the admin PATH, so every ROUTE must carry its
    // own gate.
    TestEngines.Built b = TestEngines.build(List.of(), Map.of());
    io.github.vaquarkhan.aegis.mcp.admin.AdminApi api =
        new io.github.vaquarkhan.aegis.mcp.admin.AdminApi(b.engine, Set.of("approvers"), Set.of());
    java.util.function.BiFunction<String, String, McpHttpResponse> as =
        (method, path) ->
            api.handle(
                new McpHttpRequest(method, path, Map.of(), new byte[0]),
                ALICE); // analyst, not an approver
    assertEquals(
        403,
        as.apply("GET", "/approvals").status(),
        "listing pending approvals needs an approver group");
    assertEquals(
        404,
        as.apply("GET", "/approvals/apr_00000000000000000000000000000000").status(),
        "a stranger cannot read a request");
    assertEquals(
        403, as.apply("POST", "/approvals/apr_00000000000000000000000000000000/approve").status());
    assertEquals(403, as.apply("GET", "/catalog").status(), "catalog needs an approver group");
    assertEquals(
        428,
        api.handle(
                new McpHttpRequest("POST", "/catalog/x/accept", Map.of(), new byte[0]),
                TestEngines.BOB)
            .status(),
        "even an approver must present the reviewed digest header");
    assertEquals(
        404, as.apply("GET", "/audit/verify").status(), "no verifier configured: route absent");
    assertEquals(404, as.apply("GET", "/prompts").status());
    assertEquals(
        401,
        api.handle(new McpHttpRequest("GET", "/approvals", Map.of(), new byte[0]), null).status());
    verdict(
        "OPEN-03",
        "review re-examination (admin API on the MCP listener)",
        "ordinary MCP caller probing admin routes",
        "NO-ISSUE",
        "every admin route carries its own authorisation (approver / requester / verifier groups, digest header); empty adminGroups only admits to the path, which requesters need to collect their own token");
  }

  @Test
  void open07_fullSessionTable_evictsLeastRecentInsteadOfLockingEveryoneOut() {
    io.github.vaquarkhan.aegis.engine.model.CallerIdentity[] principals =
        new io.github.vaquarkhan.aegis.engine.model.CallerIdentity[5];
    for (int i = 0; i < principals.length; i++) {
      principals[i] =
          io.github.vaquarkhan.aegis.engine.model.CallerIdentity.of(
              "p" + i, Set.of("analysts"), "test", "test");
    }
    java.util.concurrent.atomic.AtomicLong now =
        new java.util.concurrent.atomic.AtomicLong(1_000_000L);
    Clock ticking =
        new Clock() {
          @Override
          public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(java.time.ZoneId zone) {
            return this;
          }

          @Override
          public java.time.Instant instant() {
            return java.time.Instant.ofEpochMilli(now.getAndAdd(1_000));
          }
        };
    SessionRegistry registry = new SessionRegistry(ticking, 3_600_000, 4);
    String first = registry.create(principals[0], "2025-06-18").orElseThrow().id();
    for (int i = 1; i < 4; i++) {
      registry.create(principals[i], "2025-06-18").orElseThrow();
    }
    assertEquals(4, registry.size());
    // BEFORE the fix: table full, nothing idle, fifth principal refused (503) for the whole idle
    // TTL.
    java.util.Optional<SessionRegistry.Session> fifth =
        registry.create(principals[4], "2025-06-18");
    assertTrue(fifth.isPresent(), "a new principal must be admitted when the table is full");
    assertEquals(4, registry.size());
    assertTrue(
        registry.find(first, principals[0]).isEmpty(),
        "the least recently used session was evicted");
    assertTrue(registry.find(fifth.get().id(), principals[4]).isPresent());
    verdict(
        "OPEN-07",
        "review finding (SessionRegistry capacity)",
        "session table lock-out by ~313 principals holding 32 sessions each",
        "FIXED",
        "global least-recently-used eviction when full; evicted client re-initialises instead of everyone getting 503");
  }

  @Test
  void sanity_governedCallSucceeds() {
    McpHttpResponse ok = handler.handleHttp(post(echoCall(), MODERN_HEADERS), ALICE);
    assertEquals(200, ok.status(), ok.bodyText());
    assertTrue(
        Json.write(JsonValues.object(Json.parseObject(ok.bodyText()), "result"))
            .contains("echo:hi"));
    assertEquals(1, backendCalls);
  }
}
