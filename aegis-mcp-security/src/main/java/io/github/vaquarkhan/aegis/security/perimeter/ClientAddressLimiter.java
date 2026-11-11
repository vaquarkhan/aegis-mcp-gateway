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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pre-authentication limits for the gateway's own listener, applied per client address BEFORE the
 * bearer token is verified, so a flood of bad tokens costs one map lookup instead of a signature
 * check:
 *
 * <ul>
 *   <li>a request budget per address per minute ({@code requestsPerMinute});
 *   <li>authentication-failure lockout: more than {@code authFailuresPerMinute} rejected
 *       credentials from one address locks it out for {@code lockoutSeconds} (answered 429 with
 *       Retry-After);
 *   <li>{@code X-Forwarded-For} is believed only when the TCP peer is in {@code trustedProxies};
 *       the client is then the right-most address that is not itself a trusted proxy.
 * </ul>
 *
 * State is local to the replica (deliberately: a shared store would turn a pre-auth flood into load
 * on the shared store). Memory is bounded by {@code maxTracked}; when full, untracked addresses
 * share one overflow bucket, so a spray of addresses cannot grow memory or escape the limit.
 */
public final class ClientAddressLimiter {

  /**
   * @param requestsPerMinute per client address, 0 disables
   * @param authFailuresPerMinute rejected credentials per address before lockout, 0 disables
   *     lockout
   * @param lockoutSeconds lockout length
   * @param trustedProxies IP literals or CIDRs whose X-Forwarded-For is believed
   * @param maxTracked bound on tracked addresses
   */
  public record Settings(
      long requestsPerMinute,
      long authFailuresPerMinute,
      long lockoutSeconds,
      List<String> trustedProxies,
      int maxTracked) {
    /**
     * Off by default. Behind a load balancer or ingress that is not listed in {@code
     * trustedProxies} every client shares the proxy's address, so an on-by-default lockout would
     * let one attacker lock every caller out with a handful of bad tokens. Enable together with
     * trustedProxies.
     */
    public static final Settings DEFAULT = new Settings(0, 0, 300, List.of(), 100_000);

    public Settings {
      trustedProxies = trustedProxies == null ? List.of() : List.copyOf(trustedProxies);
      if (requestsPerMinute < 0
          || authFailuresPerMinute < 0
          || lockoutSeconds < 0
          || maxTracked < 1) {
        throw new IllegalArgumentException(
            "limits must not be negative and maxTracked must be at least 1");
      }
    }
  }

  /** Refusal: HTTP status, Retry-After seconds and a public reason. */
  public record Refusal(int status, long retryAfterSeconds, String reason) {}

  private static final String OVERFLOW = "overflow";
  private static final long WINDOW_MILLIS = 60_000;

  private static final class Window {
    long start;
    long count;
  }

  private final Settings settings;
  private final List<byte[][]> proxies = new ArrayList<>();
  private final Map<String, Window> requests = new ConcurrentHashMap<>();
  private final Map<String, Window> failures = new ConcurrentHashMap<>();
  private final Map<String, Long> lockedUntil = new ConcurrentHashMap<>();
  private final java.util.concurrent.atomic.AtomicLong lastSweep =
      new java.util.concurrent.atomic.AtomicLong();

  public ClientAddressLimiter(Settings settings) {
    this.settings = settings == null ? Settings.DEFAULT : settings;
    for (String p : this.settings.trustedProxies()) {
      proxies.add(cidr(p));
    }
  }

  public Settings settings() {
    return settings;
  }

  /**
   * The client address: the TCP peer, or, when the peer is a trusted proxy, the right-most {@code
   * X-Forwarded-For} entry that is not a trusted proxy. Entries are IP literals only; a malformed
   * chain falls back to the peer (no DNS lookup is ever made here).
   */
  public String clientAddress(InetAddress peer, List<String> forwardedFor) {
    if (peer == null) {
      return "unknown";
    }
    if (proxies.isEmpty() || !trusted(peer) || forwardedFor == null || forwardedFor.isEmpty()) {
      return bucket(peer);
    }
    List<String> hops = new ArrayList<>();
    for (String header : forwardedFor) {
      for (String h : header.split(",")) {
        hops.add(h.trim());
      }
    }
    for (int i = hops.size() - 1; i >= 0; i--) {
      InetAddress a = literal(hops.get(i));
      if (a == null) {
        return bucket(peer);
      }
      if (!trusted(a)) {
        return bucket(a);
      }
    }
    return bucket(peer);
  }

  /**
   * Limit key for an address. IPv4 is the address itself. IPv6 is its /64 prefix: one subscriber is
   * normally given a whole /64, so per-address keys would let one client rotate through 2^64 keys.
   */
  static String bucket(InetAddress a) {
    byte[] b = a.getAddress();
    if (b.length != 16) {
      return a.getHostAddress();
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 8; i += 2) {
      sb.append(Integer.toHexString(((b[i] & 0xff) << 8) | (b[i + 1] & 0xff))).append(':');
    }
    return sb.append(":/64").toString();
  }

  /** Called before authentication; null admits the request. */
  public Refusal beforeAuthentication(String client, long nowMillis) {
    Long until = lockedUntil.get(client);
    if (until != null) {
      if (until > nowMillis) {
        return new Refusal(
            429,
            Math.max(1, (until - nowMillis + 999) / 1000),
            "too many failed authentication attempts from this address");
      }
      lockedUntil.remove(client, until);
    }
    if (settings.requestsPerMinute() > 0
        && !admit(requests, client, settings.requestsPerMinute(), nowMillis)) {
      return new Refusal(
          429,
          Math.max(1, (WINDOW_MILLIS - (nowMillis % WINDOW_MILLIS) + 999) / 1000),
          "request rate from this address exceeded");
    }
    return null;
  }

  /** Called when the credential was missing or rejected (HTTP 401). */
  public void authenticationFailed(String client, long nowMillis) {
    if (settings.authFailuresPerMinute() <= 0) {
      return;
    }
    if (!admit(failures, client, settings.authFailuresPerMinute(), nowMillis)) {
      lockedUntil.put(
          key(lockedUntil, client, nowMillis), nowMillis + settings.lockoutSeconds() * 1000);
    }
  }

  /** Number of addresses currently locked out (diagnostics and tests). */
  public int lockedOut(long nowMillis) {
    int n = 0;
    for (Long v : lockedUntil.values()) {
      if (v > nowMillis) {
        n++;
      }
    }
    return n;
  }

  private boolean admit(Map<String, Window> map, String client, long capacity, long now) {
    Window w = map.computeIfAbsent(key(map, client, now), k -> new Window());
    synchronized (w) {
      long start = now - (now % WINDOW_MILLIS);
      if (w.start != start) {
        w.start = start;
        w.count = 0;
      }
      if (w.count >= capacity) {
        return false;
      }
      w.count++;
      return true;
    }
  }

  /** The key to use: the client itself, or the shared overflow bucket when the map is full. */
  private <V> String key(Map<String, V> map, String client, long now) {
    if (map.containsKey(client) || map.size() < settings.maxTracked()) {
      return client;
    }
    long last = lastSweep.get();
    if (now - last >= 1_000 && lastSweep.compareAndSet(last, now)) {
      sweep(map, now);
    }
    return map.size() < settings.maxTracked() ? client : OVERFLOW;
  }

  private <V> void sweep(Map<String, V> map, long now) {
    long current = now - (now % WINDOW_MILLIS);
    for (Iterator<Map.Entry<String, V>> it = map.entrySet().iterator(); it.hasNext(); ) {
      Map.Entry<String, V> e = it.next();
      Object v = e.getValue();
      if (v instanceof Window w) {
        synchronized (w) {
          if (w.start < current) {
            it.remove();
          }
        }
      } else if (v instanceof Long until && until <= now) {
        it.remove();
      }
    }
  }

  private boolean trusted(InetAddress a) {
    byte[] b = a.getAddress();
    for (byte[][] c : proxies) {
      if (matches(c, b)) {
        return true;
      }
    }
    return false;
  }

  private static boolean matches(byte[][] cidr, byte[] b) {
    byte[] net = cidr[0];
    int prefix = cidr[1][0] & 0xff;
    if (net.length != b.length) {
      return false;
    }
    int full = prefix / 8;
    for (int i = 0; i < full; i++) {
      if (net[i] != b[i]) {
        return false;
      }
    }
    int rem = prefix % 8;
    if (rem == 0) {
      return true;
    }
    int mask = (0xff << (8 - rem)) & 0xff;
    return (net[full] & mask) == (b[full] & mask);
  }

  static InetAddress literal(String s) {
    String v = s.startsWith("[") && s.endsWith("]") ? s.substring(1, s.length() - 1) : s;
    if (v.isEmpty() || v.length() > 45 || !v.matches("[0-9a-fA-F:.]+")) {
      return null;
    }
    try {
      return InetAddress.getByName(v); // literal only (checked above): no DNS
    } catch (UnknownHostException e) {
      return null;
    }
  }

  private static byte[][] cidr(String s) {
    String[] parts = s.trim().split("/", 2);
    InetAddress a = literal(parts[0]);
    if (a == null) {
      throw new IllegalArgumentException(
          "trustedProxies entries must be IP literals or CIDRs: " + s);
    }
    byte[] net = a.getAddress();
    int prefix;
    try {
      prefix = parts.length == 2 ? Integer.parseInt(parts[1]) : net.length * 8;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("bad CIDR prefix: " + s);
    }
    if (prefix < 0 || prefix > net.length * 8) {
      throw new IllegalArgumentException("bad CIDR prefix: " + s);
    }
    return new byte[][] {net, {(byte) prefix}};
  }
}
