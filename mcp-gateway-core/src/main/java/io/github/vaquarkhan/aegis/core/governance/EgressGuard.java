package io.github.vaquarkhan.aegis.core.governance;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Step 5. Server side request forgery defence for outbound targets.
 *
 * <p>Two independent rules apply, and both must pass:
 *
 * <ol>
 *   <li>An unconditional deny list covering cloud instance metadata endpoints and link-local,
 *       multicast, unspecified and broadcast address space. No configuration can re-enable these,
 *       because reaching {@code 169.254.169.254} is how a confused deputy harvests cloud
 *       credentials. Literal hosts are checked after IPv4 alternate encodings (decimal, hex,
 *       octal) are canonicalized; hostnames are resolved and every address is checked. DNS
 *       rebinding after the check still requires connect-time IP pinning (not implemented here).
 *   <li>An allow list. When it is non-empty, only listed hosts are reachable.
 * </ol>
 *
 * @author Viquar Khan
 */
public final class EgressGuard {

    /** Hosts that are never reachable, whatever the allow list says. */
    public static final Set<String> ALWAYS_DENIED_HOSTS = Set.of(
            "169.254.169.254",
            "metadata.google.internal",
            "metadata.goog",
            "metadata",
            "100.100.100.200",
            "fd00:ec2::254",
            "0.0.0.0",
            "255.255.255.255",
            "[::]",
            "::");

    private final Set<String> allowHosts;

    public EgressGuard(Set<String> allowHosts) {
        Set<String> normalised = new LinkedHashSet<>();
        if (allowHosts != null) {
            for (String h : allowHosts) {
                if (h != null && !h.isBlank()) {
                    normalised.add(h.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        this.allowHosts = Collections.unmodifiableSet(normalised);
    }

    public Set<String> allowHosts() {
        return allowHosts;
    }

    /** Accepts a bare host, a host:port pair or a full URL. */
    public boolean isAllowed(String hostOrUrl) {
        String host = extractHost(hostOrUrl);
        if (host == null || host.isBlank()) {
            return false;
        }
        if (isDeniedHost(host)) {
            return false;
        }
        if (allowHosts.isEmpty()) {
            return false;
        }
        return allowHosts.contains("*") || allowHosts.contains(host);
    }

    /** Denial reason, or {@code null} when the target is allowed. */
    public String denyReason(String hostOrUrl) {
        String host = extractHost(hostOrUrl);
        if (host == null || host.isBlank()) {
            return "unparseable egress target: " + hostOrUrl;
        }
        if (isDeniedHost(host)) {
            return "host is on the unconditional egress deny list: " + host;
        }
        if (allowHosts.isEmpty()) {
            return "no egress allow list configured (MCP_GW_EGRESS_ALLOW_HOSTS)";
        }
        if (!allowHosts.contains("*") && !allowHosts.contains(host)) {
            return "host not in egress allow list: " + host;
        }
        return null;
    }

    /** True for metadata endpoints and non-routable address space that must never be reached. */
    public static boolean isDeniedHost(String rawHost) {
        if (rawHost == null || rawHost.isBlank()) {
            return true;
        }
        String host = rawHost.trim().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (ALWAYS_DENIED_HOSTS.contains(host)) {
            return true;
        }
        if (host.startsWith("169.254.")) {
            return true;
        }
        if (host.startsWith("fe80:") || host.startsWith("fc00:") || host.startsWith("fd00:")) {
            return true;
        }

        InetAddress literal = parseIpLiteral(host);
        if (literal != null) {
            return isUnconditionallyDeniedAddress(literal);
        }

        int firstOctet = leadingOctet(host);
        if (firstOctet >= 224) {
            return true;
        }

        try {
            for (InetAddress addr : InetAddress.getAllByName(host)) {
                if (isUnconditionallyDeniedAddress(addr)) {
                    return true;
                }
            }
        } catch (UnknownHostException e) {
            // Cannot prove the name is dangerous without resolution; string checks already ran.
        }
        return false;
    }

    /**
     * Addresses that must never be egress targets, even under a {@code *} allow list. Site-local
     * and loopback are not included here: operators put those hosts on the allow list deliberately.
     */
    static boolean isUnconditionallyDeniedAddress(InetAddress addr) {
        if (addr == null) {
            return true;
        }
        if (addr.isLinkLocalAddress() || addr.isAnyLocalAddress() || addr.isMulticastAddress()) {
            return true;
        }
        byte[] raw = addr.getAddress();
        if (raw.length == 4) {
            int b0 = raw[0] & 0xff;
            int b1 = raw[1] & 0xff;
            if (b0 == 169 && b1 == 254) {
                return true;
            }
            if (b0 == 0 || b0 >= 224) {
                return true;
            }
            if (b0 == 100 && b1 == 100) {
                // Alibaba metadata 100.100.100.200 and neighbours in that /16 used for metadata.
                return raw[2] == 100;
            }
        }
        String host = addr.getHostAddress();
        if (host != null) {
            String lower = host.toLowerCase(Locale.ROOT);
            if (ALWAYS_DENIED_HOSTS.contains(lower) || lower.startsWith("fd00:ec2:")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Parses IPv4/IPv6 literals including alternate IPv4 encodings (decimal, hex, octal, dotted).
     * Returns null when {@code host} is not a literal (callers may then DNS-resolve).
     */
    static InetAddress parseIpLiteral(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        if (host.indexOf(':') >= 0) {
            try {
                // Bracket-free IPv6 or already normalized.
                return InetAddress.getByAddress(InetAddress.getByName(host).getAddress());
            } catch (UnknownHostException e) {
                return null;
            }
        }
        byte[] v4 = parseIpv4Bytes(host);
        if (v4 == null) {
            return null;
        }
        try {
            return InetAddress.getByAddress(v4);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /**
     * IPv4 text forms: dotted-decimal, dotted-octal/hex mix, single 32-bit decimal, single hex.
     * Returns null when the string is not an IPv4 literal.
     */
    static byte[] parseIpv4Bytes(String host) {
        if (host == null || host.isEmpty()) {
            return null;
        }
        if (host.indexOf('.') < 0) {
            Long value = parseUnsignedIpv4Number(host);
            if (value == null || value < 0 || value > 0xffff_ffffL) {
                return null;
            }
            return intToBytes(value.intValue());
        }
        String[] parts = host.split("\\.", -1);
        if (parts.length == 0 || parts.length > 4) {
            return null;
        }
        long[] nums = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            Long n = parseUnsignedIpv4Number(parts[i]);
            if (n == null) {
                return null;
            }
            nums[i] = n;
        }
        long addr;
        if (parts.length == 4) {
            for (long n : nums) {
                if (n > 255) {
                    return null;
                }
            }
            addr = (nums[0] << 24) | (nums[1] << 16) | (nums[2] << 8) | nums[3];
        } else if (parts.length == 3) {
            if (nums[0] > 255 || nums[1] > 255 || nums[2] > 0xffff) {
                return null;
            }
            addr = (nums[0] << 24) | (nums[1] << 16) | nums[2];
        } else if (parts.length == 2) {
            if (nums[0] > 255 || nums[1] > 0xff_ffff) {
                return null;
            }
            addr = (nums[0] << 24) | nums[1];
        } else {
            if (nums[0] > 0xffff_ffffL) {
                return null;
            }
            addr = nums[0];
        }
        return intToBytes((int) addr);
    }

    private static Long parseUnsignedIpv4Number(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String s = raw.toLowerCase(Locale.ROOT);
        try {
            if (s.startsWith("0x")) {
                if (s.length() == 2) {
                    return null;
                }
                return Long.parseLong(s.substring(2), 16);
            }
            // Leading 0 => octal when remaining digits are 0-7 (browser/URL SSRF style).
            if (s.length() > 1 && s.startsWith("0") && s.chars().allMatch(c -> c >= '0' && c <= '7')) {
                return Long.parseLong(s, 8);
            }
            if (!s.chars().allMatch(Character::isDigit)) {
                return null;
            }
            return Long.parseLong(s, 10);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static byte[] intToBytes(int value) {
        return new byte[] {
            (byte) ((value >>> 24) & 0xff),
            (byte) ((value >>> 16) & 0xff),
            (byte) ((value >>> 8) & 0xff),
            (byte) (value & 0xff)
        };
    }

    /** Extracts the host component from a bare host, {@code host:port} or a full URL. */
    public static String extractHost(String hostOrUrl) {
        if (hostOrUrl == null) {
            return null;
        }
        String value = hostOrUrl.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.contains("://")) {
            try {
                URI uri = URI.create(value);
                String h = uri.getHost();
                if (h != null) {
                    return h.toLowerCase(Locale.ROOT);
                }
            } catch (IllegalArgumentException e) {
                return null;
            }
            int schemeEnd = value.indexOf("://") + 3;
            value = value.substring(schemeEnd);
            int slash = value.indexOf('/');
            if (slash >= 0) {
                value = value.substring(0, slash);
            }
        }
        int at = value.lastIndexOf('@');
        if (at >= 0) {
            value = value.substring(at + 1);
        }
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close > 0) {
                return value.substring(1, close).toLowerCase(Locale.ROOT);
            }
            return null;
        }
        int colon = value.indexOf(':');
        if (colon >= 0) {
            value = value.substring(0, colon);
        }
        return value.isEmpty() ? null : value.toLowerCase(Locale.ROOT);
    }

    /** Leading dotted-quad octet, or -1 when the host is not an IPv4 dotted literal. */
    private static int leadingOctet(String host) {
        byte[] v4 = parseIpv4Bytes(host);
        if (v4 == null) {
            return -1;
        }
        return v4[0] & 0xff;
    }
}
