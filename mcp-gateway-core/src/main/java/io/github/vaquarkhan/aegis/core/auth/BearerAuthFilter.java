package io.github.vaquarkhan.aegis.core.auth;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.Set;

/**
 * Fail-closed bearer authentication for the HTTP transport. Supports a single shared token or a
 * hashed multi-caller {@link TokenRegistry}.
 *
 * <p>Comparison is constant time so a wrong token cannot be discovered byte by byte through timing.
 *
 * @author Viquar Khan
 */
public final class BearerAuthFilter implements Filter {

    public static final String ATTR_CALLER = "aegis.caller";

    private final byte[] expectedBearerHeader;
    private final CallerIdentity singleCaller;
    private final TokenRegistry registry;

    /** Single shared bearer token with full scope and the given read-only flag. */
    public BearerAuthFilter(String token) {
        this(token, "http", Set.of("*"), false);
    }

    public BearerAuthFilter(String token, String callerId, Set<String> resourceScopes, boolean readonly) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("bearer token required");
        }
        this.expectedBearerHeader = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        this.singleCaller = new CallerIdentity(callerId, resourceScopes, readonly);
        this.registry = null;
    }

    /** Multi-caller mode backed by a hashed token registry. */
    public BearerAuthFilter(TokenRegistry registry) {
        if (registry == null || registry.size() == 0) {
            throw new IllegalArgumentException("token registry required");
        }
        this.expectedBearerHeader = null;
        this.singleCaller = null;
        this.registry = registry;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;
        Optional<CallerIdentity> identity = resolve(req.getHeader("Authorization"));
        if (identity.isEmpty()) {
            resp.setStatus(401);
            resp.setHeader("WWW-Authenticate", "Bearer");
            resp.setContentType("application/json");
            resp.getOutputStream().write("{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        CallerIdentity caller = identity.get();
        req.setAttribute(ATTR_CALLER, caller);
        CallerContext.set(caller);
        try {
            chain.doFilter(request, response);
        } finally {
            CallerContext.clear();
        }
    }

    /** Exposed for tests: resolves an {@code Authorization} header to an identity. */
    public Optional<CallerIdentity> resolve(String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            return Optional.empty();
        }
        if (registry != null) {
            if (!authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
                return Optional.empty();
            }
            return registry.authenticateBearerToken(authHeader.substring(7).trim());
        }
        if (!constantTimeEquals(expectedBearerHeader, authHeader.getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }
        return Optional.of(singleCaller);
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        int len = Math.max(a.length, b.length);
        byte[] aa = new byte[len];
        byte[] bb = new byte[len];
        System.arraycopy(a, 0, aa, 0, a.length);
        System.arraycopy(b, 0, bb, 0, b.length);
        int diff = a.length ^ b.length;
        if (!MessageDigest.isEqual(aa, bb)) {
            diff |= 1;
        }
        return diff == 0;
    }
}
