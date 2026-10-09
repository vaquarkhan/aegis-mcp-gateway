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
package io.github.vaquarkhan.aegis.engine.control;

import io.github.vaquarkhan.aegis.engine.spi.AddressResolver;
import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Step 5. Every network destination a call would contact must be on the allow list, and EVERY DNS
 * answer for it must be outside the denied address classes unless an operator CIDR explicitly
 * allows it.
 *
 * <p>Fixes three 0.1 defects: a wildcard host pattern no longer allows private or loopback
 * answers; all DNS answers are checked (not just the first); and URL-bearing arguments are found
 * at any nesting depth (see {@link #urlsIn}).
 *
 * <p>Residual risk: between this check and the executor's own connection the
 * name can be re-resolved (DNS rebinding window). Operators with that threat should allow IP
 * literals or CIDRs rather than names, or run the gateway with a pinned resolver.
 */
public final class EgressGuard {

    /** Address classes denied unless an allow CIDR covers the address. */
    public enum AddressClass { LOOPBACK, LINK_LOCAL, PRIVATE, UNIQUE_LOCAL, CGNAT, ANY_LOCAL, MULTICAST, RESERVED, PUBLIC }

    private final List<String> allowHosts;
    private final List<Cidr> allowCidrs;
    private final Set<String> allowSchemes;
    private final AddressResolver resolver;

    public EgressGuard(List<String> allowHosts, List<String> allowCidrs, Set<String> allowSchemes, AddressResolver resolver) {
        List<String> hosts = new ArrayList<>();
        for (String h : allowHosts == null ? List.<String>of() : allowHosts) {
            String n = normaliseHost(h.startsWith("*.") ? h.substring(2) : h);
            hosts.add(h.startsWith("*.") ? "*." + n : n);
        }
        this.allowHosts = List.copyOf(hosts);
        List<Cidr> cidrs = new ArrayList<>();
        for (String c : allowCidrs == null ? List.<String>of() : allowCidrs) {
            cidrs.add(Cidr.parse(c));
        }
        this.allowCidrs = List.copyOf(cidrs);
        this.allowSchemes = allowSchemes == null || allowSchemes.isEmpty() ? Set.of("https", "http") : Set.copyOf(allowSchemes);
        this.resolver = resolver == null ? AddressResolver.SYSTEM : resolver;
    }

    /** @return null when allowed, else a reason (host shown, never full URL with query) */
    public String check(URI target) {
        if (target == null) {
            return "missing egress target";
        }
        String scheme = target.getScheme() == null ? "" : target.getScheme().toLowerCase(Locale.ROOT);
        if (!allowSchemes.contains(scheme)) {
            return "scheme not allowed: " + scheme;
        }
        if (target.getRawUserInfo() != null) {
            return "user-info in egress URL is not allowed";
        }
        String rawHost = target.getHost();
        if (rawHost == null || rawHost.isEmpty()) {
            return "egress URL has no host";
        }
        String host;
        try {
            host = normaliseHost(rawHost);
        } catch (IllegalArgumentException e) {
            return "invalid egress host";
        }
        if (!hostAllowed(host)) {
            return "host not on egress allow list: " + host;
        }
        InetAddress[] answers;
        try {
            answers = resolver.resolveAll(stripBrackets(host));
        } catch (UnknownHostException e) {
            return "egress host does not resolve: " + host;
        }
        if (answers == null || answers.length == 0) {
            return "egress host does not resolve: " + host;
        }
        for (InetAddress a : answers) {
            AddressClass cls = classify(a);
            if (cls != AddressClass.PUBLIC && !cidrAllowed(a)) {
                return "egress host " + host + " resolves to a " + cls + " address that is not allowed";
            }
        }
        return null;
    }

    private boolean hostAllowed(String host) {
        for (String p : allowHosts) {
            if (p.startsWith("*.")) {
                String suffix = p.substring(1); // ".example.com"
                if (host.endsWith(suffix) && host.length() > suffix.length()) {
                    return true;
                }
            } else if (p.equals(host)) {
                return true;
            }
        }
        return false;
    }

    private boolean cidrAllowed(InetAddress a) {
        for (Cidr c : allowCidrs) {
            if (c.contains(a)) {
                return true;
            }
        }
        return false;
    }

    static String normaliseHost(String h) {
        String s = h.trim().toLowerCase(Locale.ROOT);
        if (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.startsWith("[")) {
            return s;
        }
        return IDN.toASCII(s, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
    }

    private static String stripBrackets(String h) {
        return h.startsWith("[") && h.endsWith("]") ? h.substring(1, h.length() - 1) : h;
    }

    /** Classifies an address; IPv4-mapped and IPv4-compatible IPv6 are classified by the embedded IPv4. */
    public static AddressClass classify(InetAddress a) {
        byte[] b = a.getAddress();
        if (a instanceof Inet6Address) {
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                mapped &= b[i] == 0;
            }
            if (mapped && ((b[10] == (byte) 0xff && b[11] == (byte) 0xff) || (b[10] == 0 && b[11] == 0 && !allZero(b, 12)))) {
                try {
                    return classify(InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]}));
                } catch (UnknownHostException e) {
                    return AddressClass.RESERVED;
                }
            }
            // Transition prefixes that embed an IPv4 destination are classified by that IPv4 address:
            // NAT64 64:ff9b::/96 and 64:ff9b:1::/48 (RFC 6052, RFC 8215), 6to4 2002::/16 (RFC 3056),
            // Teredo 2001:0::/32 (RFC 4380, client address stored inverted).
            byte[] embedded = null;
            if (b[0] == 0 && b[1] == 0x64 && b[2] == (byte) 0xff && b[3] == (byte) 0x9b) {
                embedded = new byte[] {b[12], b[13], b[14], b[15]};
            } else if (b[0] == 0x20 && b[1] == 0x02) {
                embedded = new byte[] {b[2], b[3], b[4], b[5]};
            } else if (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0 && b[3] == 0) {
                embedded = new byte[] {(byte) ~b[12], (byte) ~b[13], (byte) ~b[14], (byte) ~b[15]};
            }
            if (embedded != null) {
                try {
                    return classify(InetAddress.getByAddress(embedded));
                } catch (UnknownHostException e) {
                    return AddressClass.RESERVED;
                }
            }
            if (a.isAnyLocalAddress()) {
                return AddressClass.ANY_LOCAL;
            }
            if (a.isLoopbackAddress()) {
                return AddressClass.LOOPBACK;
            }
            if (a.isLinkLocalAddress()) {
                return AddressClass.LINK_LOCAL;
            }
            if (a.isMulticastAddress()) {
                return AddressClass.MULTICAST;
            }
            if ((b[0] & 0xfe) == 0xfc) {
                return AddressClass.UNIQUE_LOCAL;
            }
            if (a.isSiteLocalAddress()) {
                return AddressClass.PRIVATE;
            }
            return AddressClass.PUBLIC;
        }
        int b0 = b[0] & 0xff;
        int b1 = b[1] & 0xff;
        if (b0 == 0) {
            return AddressClass.ANY_LOCAL;
        }
        if (b0 == 127) {
            return AddressClass.LOOPBACK;
        }
        if (b0 == 169 && b1 == 254) {
            return AddressClass.LINK_LOCAL; // includes 169.254.169.254 cloud metadata
        }
        if (b0 == 10 || (b0 == 172 && b1 >= 16 && b1 <= 31) || (b0 == 192 && b1 == 168)) {
            return AddressClass.PRIVATE;
        }
        if (b0 == 100 && b1 >= 64 && b1 <= 127) {
            return AddressClass.CGNAT;
        }
        if (b0 >= 224 && b0 <= 239) {
            return AddressClass.MULTICAST;
        }
        if (b0 >= 240 || (b0 == 192 && b1 == 0 && (b[2] & 0xff) == 0) || (b0 == 198 && (b1 == 18 || b1 == 19))) {
            return AddressClass.RESERVED;
        }
        return AddressClass.PUBLIC;
    }

    private static boolean allZero(byte[] b, int from) {
        for (int i = from; i < b.length; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Finds absolute http(s) URLs at any depth inside the arguments, so a tool that takes a URL
     * argument cannot be pointed at an internal address by nesting it.
     */
    public static List<URI> urlsIn(Object arguments) {
        List<URI> out = new ArrayList<>();
        for (String s : ValueWalker.strings(arguments)) {
            String lower = s.toLowerCase(Locale.ROOT);
            // Every embedded http(s) URL counts, not only values that start with one
            // (e.g. "callback=http://169.254.169.254/" or a URL inside free text).
            int from = 0;
            while (out.size() < 256) {
                int i = lower.indexOf("http://", from);
                int j = lower.indexOf("https://", from);
                int start = i < 0 ? j : (j < 0 ? i : Math.min(i, j));
                if (start < 0) {
                    break;
                }
                int end = start;
                while (end < s.length() && !Character.isWhitespace(s.charAt(end)) && "\"'<>`".indexOf(s.charAt(end)) < 0) {
                    end++;
                }
                String candidate = s.substring(start, end);
                try {
                    out.add(new URI(candidate));
                } catch (Exception e) {
                    // An unparseable URL-looking value is returned as an unroutable marker, so the
                    // guard denies it instead of silently skipping it.
                    out.add(URI.create("invalid://unparseable"));
                }
                from = Math.max(end, start + 7);
            }
            if (out.size() >= 256) {
                out.add(URI.create("invalid://too-many-urls"));
                break;
            }
        }
        return out;
    }

    /** IPv4/IPv6 CIDR block. */
    record Cidr(byte[] network, int prefix) {
        static Cidr parse(String s) {
            String[] parts = s.trim().split("/", 2);
            // Checked before InetAddress.getByName so a host name is never resolved here.
            if (!parts[0].matches("[0-9a-fA-F:.]+")) {
                throw new IllegalArgumentException("CIDR must use an IP literal: " + s);
            }
            try {
                InetAddress addr = InetAddress.getByName(parts[0]);
                byte[] net = addr.getAddress();
                int prefix = parts.length == 2 ? Integer.parseInt(parts[1]) : net.length * 8;
                if (prefix < 0 || prefix > net.length * 8) {
                    throw new IllegalArgumentException("bad CIDR prefix: " + s);
                }
                return new Cidr(net, prefix);
            } catch (UnknownHostException | NumberFormatException e) {
                throw new IllegalArgumentException("bad CIDR: " + s, e);
            }
        }

        boolean contains(InetAddress a) {
            byte[] b = a.getAddress();
            if (b.length != network.length) {
                return false;
            }
            int full = prefix / 8;
            for (int i = 0; i < full; i++) {
                if (b[i] != network[i]) {
                    return false;
                }
            }
            int rem = prefix % 8;
            if (rem == 0) {
                return true;
            }
            int mask = (0xff << (8 - rem)) & 0xff;
            return (b[full] & mask) == (network[full] & mask);
        }
    }
}
