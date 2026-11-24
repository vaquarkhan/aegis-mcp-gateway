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
 * Gap L3 unit level: client address derivation (trusted proxies only), per-address budget,
 * failed-authentication lockout and bounded memory. The HTTP-level proof is ThreatModelGapServerTest.
 */
package io.github.vaquarkhan.aegis.security.perimeter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.config.ConfigException;
import io.github.vaquarkhan.aegis.engine.json.Json;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientAddressLimiterTest {

  @TempDir Path dir;

  private static InetAddress ip(String s) throws Exception {
    return InetAddress.getByName(s);
  }

  @Test
  void forwardedForIsBelievedOnlyFromTrustedProxies() throws Exception {
    ClientAddressLimiter l =
        new ClientAddressLimiter(
            new ClientAddressLimiter.Settings(
                0, 0, 0, List.of("10.0.0.0/8", "fd00::/8", "192.0.2.1"), 10));
    // Untrusted peer: header ignored (a client cannot choose its own bucket).
    assertEquals("198.51.100.1", l.clientAddress(ip("198.51.100.1"), List.of("203.0.113.5")));
    // Trusted proxy: right-most untrusted hop wins; trusted hops are skipped.
    assertEquals(
        "203.0.113.5",
        l.clientAddress(ip("10.1.2.3"), List.of("1.1.1.1, 203.0.113.5", "10.9.9.9")));
    assertEquals("2001:db8:0:0::/64", l.clientAddress(ip("10.1.2.3"), List.of("[2001:db8::1]")));
    // IPv6 is limited per /64: rotating the interface id inside one subscriber prefix gains
    // nothing.
    assertEquals(
        l.clientAddress(ip("2001:db8:aa:bb::1"), null),
        l.clientAddress(ip("2001:db8:aa:bb:ffff::9"), null));
    assertTrue(
        !l.clientAddress(ip("2001:db8:aa:bb::1"), null)
            .equals(l.clientAddress(ip("2001:db8:aa:bc::1"), null)));
    assertEquals("203.0.113.7", l.clientAddress(ip("192.0.2.1"), List.of("203.0.113.7")));
    assertEquals("203.0.113.8", l.clientAddress(ip("fd00::5"), List.of("203.0.113.8")));
    // Malformed or name entries fall back to the peer (never a DNS lookup).
    assertEquals("10.1.2.3", l.clientAddress(ip("10.1.2.3"), List.of("evil.example")));
    assertEquals("10.1.2.3", l.clientAddress(ip("10.1.2.3"), List.of("")));
    // Only trusted hops: the peer.
    assertEquals("10.1.2.3", l.clientAddress(ip("10.1.2.3"), List.of("10.0.0.9")));
    assertEquals("10.1.2.3", l.clientAddress(ip("10.1.2.3"), null));
    assertEquals("unknown", l.clientAddress(null, List.of()));
    ClientAddressLimiter noProxies = new ClientAddressLimiter(null);
    assertEquals(ClientAddressLimiter.Settings.DEFAULT, noProxies.settings());
    assertEquals(
        0,
        ClientAddressLimiter.Settings.DEFAULT.requestsPerMinute(),
        "opt-in: a shared proxy address must not be limited by default");
    assertEquals(
        0, ClientAddressLimiter.Settings.DEFAULT.authFailuresPerMinute(), "opt-in lockout");
    assertEquals("10.1.2.3", noProxies.clientAddress(ip("10.1.2.3"), List.of("203.0.113.5")));
    assertNull(ClientAddressLimiter.literal("x".repeat(50)));
  }

  @Test
  void budgetLockoutAndOverflowAreBounded() {
    ClientAddressLimiter l =
        new ClientAddressLimiter(new ClientAddressLimiter.Settings(3, 2, 10, List.of(), 2));
    long t = 1_000_000L * 60_000L; // start of a window
    for (int i = 0; i < 3; i++) {
      assertNull(l.beforeAuthentication("a", t + i));
    }
    ClientAddressLimiter.Refusal r = l.beforeAuthentication("a", t + 10);
    assertNotNull(r);
    assertEquals(429, r.status());
    assertTrue(r.retryAfterSeconds() >= 1);
    assertNull(l.beforeAuthentication("a", t + 60_000), "a new window resets the budget");

    l.authenticationFailed("b", t);
    l.authenticationFailed("b", t);
    assertEquals(0, l.lockedOut(t));
    l.authenticationFailed("b", t);
    assertEquals(1, l.lockedOut(t));
    ClientAddressLimiter.Refusal locked = l.beforeAuthentication("b", t + 1_000);
    assertNotNull(locked);
    assertTrue(locked.reason().contains("failed authentication"));
    assertEquals(9, locked.retryAfterSeconds());
    assertNull(l.beforeAuthentication("b", t + 11_000), "lockout expires");

    // maxTracked = 2: a spray of new addresses shares one overflow bucket instead of growing
    // memory.
    ClientAddressLimiter small =
        new ClientAddressLimiter(new ClientAddressLimiter.Settings(1, 0, 0, List.of(), 2));
    assertNull(small.beforeAuthentication("x1", t));
    assertNull(small.beforeAuthentication("x2", t));
    assertNull(
        small.beforeAuthentication("x3", t), "first overflow request uses the shared bucket");
    assertNotNull(small.beforeAuthentication("x4", t), "the shared overflow bucket is limited too");
    // A minute later the old windows are swept, so new addresses are tracked individually again.
    assertNull(small.beforeAuthentication("x5", t + 60_000));
    assertNull(small.beforeAuthentication("x6", t + 60_000));
    // Lockout entries are bounded and swept the same way.
    ClientAddressLimiter locks =
        new ClientAddressLimiter(new ClientAddressLimiter.Settings(0, 1, 1, List.of(), 1));
    locks.authenticationFailed("l1", t);
    locks.authenticationFailed("l1", t);
    assertEquals(1, locks.lockedOut(t));
    locks.authenticationFailed("l2", t + 5_000);
    locks.authenticationFailed("l2", t + 5_000);
    assertNull(locks.beforeAuthentication("l1", t + 5_000), "the first lock expired after 1 s");
    // Disabled lockout records nothing.
    ClientAddressLimiter off =
        new ClientAddressLimiter(new ClientAddressLimiter.Settings(0, 0, 0, List.of(), 5));
    off.authenticationFailed("z", t);
    assertEquals(0, off.lockedOut(t));
    assertNull(off.beforeAuthentication("z", t));
  }

  @Test
  void settingsAndHostKeysAreValidated() {
    assertThrows(
        IllegalArgumentException.class, () -> new ClientAddressLimiter.Settings(-1, 0, 0, null, 1));
    assertThrows(
        IllegalArgumentException.class, () -> new ClientAddressLimiter.Settings(0, 0, 0, null, 0));
    for (String bad : List.of("proxy.example", "10.0.0.0/33", "10.0.0.0/x", "::/129")) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new ClientAddressLimiter(new ClientAddressLimiter.Settings(0, 0, 0, List.of(bad), 1)),
          bad);
    }
    Map<String, String> env = Map.of("S", "secret-0123456789abcdef-0123456789abcdef");
    String id =
        "\"identity\":{\"type\":\"jwt\",\"jwt\":{\"issuer\":\"i\",\"audience\":\"a\",\"algorithms\":[\"HS256\"],"
            + "\"hmacSecretEnv\":\"S\",\"requireJti\":true,\"requireRevocation\":true}}";
    OwnServerSecuritySettings ok =
        OwnServerSecuritySettings.parse(
            Json.parseObject(
                "{"
                    + id
                    + ",\"limits\":{\"requestsPerMinute\":5,\"trustedProxies\":[\"10.0.0.0/8\"]},\"adminListen\":{\"port\":9443}}"),
            dir,
            env::get,
            Clock.systemUTC());
    assertEquals(5, ok.limits().requestsPerMinute());
    assertTrue(ok.separateAdminListener());
    assertEquals("127.0.0.1", ok.adminHost());
    assertEquals(9443, ok.adminPort());
    OwnServerSecuritySettings shared =
        OwnServerSecuritySettings.parse(
            Json.parseObject("{" + id + "}"), dir, env::get, Clock.systemUTC());
    assertEquals(-1, shared.adminPort());
    for (String bad :
        List.of(
            ",\"limits\":{\"trustedProxies\":[\"proxy.example\"]}",
            ",\"limits\":{\"maxTracked\":0}",
            ",\"adminListen\":{\"host\":\"127.0.0.1\"}",
            ",\"adminListen\":{\"port\":70000}",
            ",\"listen\":{\"port\":9000},\"adminListen\":{\"port\":9000}",
            ",\"adminListen\":{\"host\":\"0.0.0.0\",\"port\":9001}")) {
      assertThrows(
          ConfigException.class,
          () ->
              OwnServerSecuritySettings.parse(
                  Json.parseObject("{" + id + bad + "}"), dir, env::get, Clock.systemUTC()),
          bad);
    }
  }
}
