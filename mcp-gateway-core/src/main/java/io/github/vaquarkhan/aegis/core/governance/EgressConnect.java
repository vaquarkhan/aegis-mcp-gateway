package io.github.vaquarkhan.aegis.core.governance;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Connect-time SSRF checks for outbound JDK {@code HttpClient} calls.
 *
 * <p>Step 5 of the interceptor chain checks caller-supplied URL arguments before execution. Adapter
 * base URLs are a second path: this helper resolves the host once and rejects unconditionally
 * denied addresses (metadata / link-local / multicast). Clients must also set
 * {@code followRedirects(NEVER)} so a 30x cannot bounce to metadata after this check.
 *
 * <p>The request URI is left unchanged (hostname form) so virtual-hosted backends and the JDK
 * restricted {@code Host} header rules keep working. Operators that need post-resolve IP pinning
 * should place backends on a network path the JVM cannot rebind to metadata.
 *
 * @author Viquar Khan
 */
public final class EgressConnect {

    private EgressConnect() {}

    /**
     * Result of validating a target URL.
     *
     * @param requestUri URI to send (hostname form)
     * @param hostHeader original hostname (informational; not required as an HTTP header)
     */
    public record PinnedTarget(URI requestUri, String hostHeader) {}

    /**
     * Validates {@code uri}. Throws {@link IllegalArgumentException} when the host is on the
     * unconditional deny list or resolves only to denied addresses.
     */
    public static PinnedTarget pin(URI uri) {
        if (uri == null || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("egress target missing host");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (EgressGuard.isDeniedHost(host)) {
            throw new IllegalArgumentException("egress target host denied: " + host);
        }
        assertResolvesSafely(host);
        int port = uri.getPort();
        String hostHeader = port > 0 ? host + ":" + port : host;
        return new PinnedTarget(uri, hostHeader);
    }

    /** Validates a string URL. */
    public static PinnedTarget pin(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("egress target URL blank");
        }
        return pin(URI.create(url.trim()));
    }

    private static void assertResolvesSafely(String host) {
        InetAddress literal = EgressGuard.parseIpLiteral(host);
        if (literal != null) {
            if (EgressGuard.isUnconditionallyDeniedAddress(literal)) {
                throw new IllegalArgumentException("egress target address denied: " + host);
            }
            return;
        }
        try {
            InetAddress[] addrs = InetAddress.getAllByName(host);
            boolean anySafe = false;
            for (InetAddress addr : addrs) {
                if (EgressGuard.isUnconditionallyDeniedAddress(addr)) {
                    continue;
                }
                anySafe = true;
                break;
            }
            if (!anySafe) {
                throw new IllegalArgumentException("egress target resolves only to denied addresses: " + host);
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("egress target host unresolvable: " + host, e);
        }
    }
}
