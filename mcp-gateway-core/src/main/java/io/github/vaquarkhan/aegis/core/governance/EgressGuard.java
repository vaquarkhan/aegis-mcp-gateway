/*
 * Licensed to the Aegis MCP Gateway project under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vaquarkhan.aegis.core.governance;

import java.net.URI;
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
 *       credentials.</li>
 *   <li>An allow list. When it is non-empty, only listed hosts are reachable.</li>
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
        // IPv4 link-local, which is where every major cloud publishes instance metadata.
        if (host.startsWith("169.254.")) {
            return true;
        }
        // IPv6 link-local and unique local address space.
        if (host.startsWith("fe80:") || host.startsWith("fc00:") || host.startsWith("fd00:")) {
            return true;
        }
        // IPv4 multicast 224.0.0.0/4 and reserved 240.0.0.0/4.
        int firstOctet = leadingOctet(host);
        return firstOctet >= 224;
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
                // URI.getHost returns null for some malformed authorities; fall through.
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

    /** Leading dotted-quad octet, or -1 when the host is not an IPv4 literal. */
    private static int leadingOctet(String host) {
        int dot = host.indexOf('.');
        if (dot <= 0) {
            return -1;
        }
        String head = host.substring(0, dot);
        if (head.isEmpty() || head.length() > 3) {
            return -1;
        }
        for (int i = 0; i < head.length(); i++) {
            if (!Character.isDigit(head.charAt(i))) {
                return -1;
            }
        }
        // Only treat it as an IPv4 literal when the last label is numeric too.
        int lastDot = host.lastIndexOf('.');
        String tail = host.substring(lastDot + 1);
        if (tail.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < tail.length(); i++) {
            if (!Character.isDigit(tail.charAt(i))) {
                return -1;
            }
        }
        return Integer.parseInt(head);
    }
}
