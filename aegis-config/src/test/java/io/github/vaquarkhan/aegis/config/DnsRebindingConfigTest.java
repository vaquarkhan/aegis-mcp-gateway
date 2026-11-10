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
 * Gap L1 through the whole gateway: a source host whose DNS answer flips between the policy step
 * and the connection (DNS rebinding) must not reach the rebound address. The host name is not
 * resolvable by real DNS, so a successful call also proves the gateway-built transport dials the
 * address the egress control checked instead of resolving again.
 */
package io.github.vaquarkhan.aegis.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.engine.CallOutcome;
import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.model.ToolCall;
import io.github.vaquarkhan.aegis.engine.spi.AddressResolver;
import io.github.vaquarkhan.aegis.mcp.testing.TestMcpServer;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DnsRebindingConfigTest {

  @TempDir Path dir;

  static final CallerIdentity ALICE = CallerIdentity.of("alice", Set.of("analysts"), "idp", "jwt");
  private static final Map<String, String> ENV = Map.of("AEGIS_SIGNING_KEY", "k".repeat(40));

  @Test
  void aRebindingSourceHostNeverReachesTheReboundAddress() throws Exception {
    AtomicBoolean rebind = new AtomicBoolean(false);
    AtomicInteger lookups = new AtomicInteger();
    InetAddress loopback = InetAddress.getByAddress("rebind.aegis.test", new byte[] {127, 0, 0, 1});
    InetAddress metadata =
        InetAddress.getByAddress(
            "rebind.aegis.test", new byte[] {(byte) 169, (byte) 254, (byte) 169, (byte) 254});
    AddressResolver resolver =
        host -> {
          int n = lookups.incrementAndGet();
          // Attacker DNS: once armed, alternate safe / metadata, so the policy step sees the safe
          // answer and a second resolution at connect time would see the metadata address.
          return new InetAddress[] {rebind.get() && n % 2 == 0 ? metadata : loopback};
        };
    AtomicInteger backendCalls = new AtomicInteger();
    try (TestMcpServer mcp = new TestMcpServer(TestMcpServer.Mode.MODERN)) {
      mcp.tool(
          "lookup",
          "Lookup.",
          Map.of("type", "object"),
          a -> {
            backendCalls.incrementAndGet();
            return TestMcpServer.textResult("ok");
          });
      String url = mcp.uri().toString().replace("127.0.0.1", "rebind.aegis.test");
      Map<String, Object> cfg =
          Json.parseObject(
              """
                    {"policy":{"rules":[{"id":"r","groups":["*"],"classes":["READ"]}]},"audit":{"file":"a.jsonl"},
                     "egress":{"allowCidrs":["127.0.0.1/32"],"schemes":["http"]},
                     "sources":[{"id":"m","type":"mcp","url":"%s","defaultClass":"READ"}]}"""
                  .formatted(url));
      BuildContext ctx = new BuildContext(ENV::get, dir, null, resolver, null, null, null, null);
      try (Gateway g = GatewayFactory.build(cfg, ctx)) {
        CallOutcome ok =
            g.engine().call(ALICE, new ToolCall("1", "lookup", Map.of(), Map.of(), ""));
        assertTrue(ok.allowed(), "pinned transport reached the checked address: " + ok.decision());
        assertEquals(1, backendCalls.get());

        lookups.set(0);
        rebind.set(true);
        CallOutcome rebound =
            g.engine().call(ALICE, new ToolCall("2", "lookup", Map.of(), Map.of(), ""));
        assertFalse(
            rebound.allowed(),
            "the call whose connect-time answer is metadata must fail: " + rebound.decision());
        assertEquals(1, backendCalls.get(), "no request reached a backend after rebinding");
        assertTrue(
            lookups.get() >= 2,
            "policy step and connect step each resolved once: " + lookups.get());
      }
    }
    System.out.println(
        "THREATMODEL|GAP-L1e|DNS rebinding through GatewayFactory-built MCP source|"
            + "BuildContext.outbound + EgressBinding|FIXED|policy step saw 127.0.0.1, connect step saw 169.254.169.254, refused, backend calls unchanged");
  }
}
