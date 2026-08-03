package io.github.vaquarkhan.aegis.core.auth;

import com.nimbusds.jose.util.JSONObjectUtils;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.ParseException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client Identity Metadata Document verifier.
 *
 * <p>Fetches a client identity metadata document over HTTPS and checks the property that makes the
 * URL-as-client-id pattern safe: the document's {@code client_id} must equal the URL it was
 * retrieved from. Without that check any client could point the gateway at someone else's document
 * and inherit its identity. Documents are cached for {@link #CACHE_TTL_MILLIS}, failures included,
 * so neither a busy client nor a hostile one can turn the gateway into a fetch amplifier.
 *
 * <p>The verifier does not yet authenticate a request. A metadata document names a client, it does
 * not prove that the caller is that client, so {@link #verify(String)} still denies and {@code
 * MCP_GW_AUTH_MODE=cimd} still refuses to start an HTTP listener. What this class provides is the
 * document half of the flow, ready for the authorization server half. See design section 10.
 *
 * @author Viquar Khan
 */
public final class CimdVerifier {

    /** Document cache lifetime. */
    public static final long CACHE_TTL_MILLIS = 300_000L;

    /** Refuse a document larger than this, so a hostile endpoint cannot stream us out of memory. */
    public static final int MAX_DOCUMENT_BYTES = 65_536;

    /** Cap distinct document URLs retained in the cache. */
    public static final int MAX_CACHE_ENTRIES = 1024;

    private static final Logger LOG = LoggerFactory.getLogger(CimdVerifier.class);

    private final String metadataUrl;
    private final HttpClient http;
    private final Map<String, CachedDocument> cache = new LinkedHashMap<>(64, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CachedDocument> eldest) {
            return size() > MAX_CACHE_ENTRIES;
        }
    };

    public CimdVerifier(String metadataUrl) {
        this.metadataUrl = blankToNull(metadataUrl);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public boolean configured() {
        return metadataUrl != null;
    }

    public String metadataUrl() {
        return metadataUrl;
    }

    /**
     * Fetches and validates the document at the configured URL.
     *
     * @return the verified document, or empty when nothing is configured or verification fails
     */
    public Optional<ClientDocument> verifyConfiguredDocument() {
        return metadataUrl == null ? Optional.empty() : verifyDocument(metadataUrl);
    }

    /**
     * Fetches the document at {@code url} over HTTPS and validates it.
     *
     * <p>Redirects are not followed, because a redirect would let the document be served from a URL
     * other than the one that acts as the client identifier.
     *
     * @param url the client identity metadata document URL, which is also the client id
     * @return the verified document, or empty when the fetch or any check fails
     */
    public Optional<ClientDocument> verifyDocument(String url) {
        String normalized = blankToNull(url);
        if (normalized == null) {
            return Optional.empty();
        }
        synchronized (cache) {
            CachedDocument cached = cache.get(normalized);
            if (cached != null) {
                if (System.currentTimeMillis() < cached.expiresAtMillis) {
                    return Optional.ofNullable(cached.document);
                }
                cache.remove(normalized);
            }
        }
        Optional<ClientDocument> fetched = fetchAndParse(normalized);
        synchronized (cache) {
            // Failures are cached too, so a client repeatedly presenting a broken URL cannot use
            // the gateway as an amplifier against the host that serves it.
            cache.put(normalized, new CachedDocument(
                    fetched.orElse(null), System.currentTimeMillis() + CACHE_TTL_MILLIS));
        }
        return fetched;
    }

    /** Parses and validates a document body already in hand, for tests and offline checks. */
    public Optional<ClientDocument> parseDocument(String url, String body) {
        String normalized = blankToNull(url);
        if (normalized == null || body == null || body.isBlank()) {
            return Optional.empty();
        }
        Map<String, Object> json;
        try {
            json = JSONObjectUtils.parse(body);
        } catch (ParseException e) {
            LOG.debug("CIMD rejected: document at {} is not JSON", normalized);
            return Optional.empty();
        }
        String clientId = str(json.get("client_id"));
        if (clientId == null || !sameUrl(clientId, normalized)) {
            LOG.debug("CIMD rejected: client_id does not match the document URL {}", normalized);
            return Optional.empty();
        }
        return Optional.of(new ClientDocument(
                clientId,
                str(json.get("client_name")),
                strings(json.get("redirect_uris")),
                strings(json.get("grant_types")),
                str(json.get("software_id")),
                str(json.get("software_statement"))));
    }

    /** Clears the document cache. */
    public void invalidate() {
        synchronized (cache) {
            cache.clear();
        }
    }

    /**
     * Always empty: a metadata document identifies a client but does not authenticate the request
     * that presented it, so admitting a caller on it alone would authenticate everybody.
     */
    public Optional<CallerIdentity> verify(String clientIdentityMetadataDocument) {
        LOG.debug("CIMD verification denied: request authentication is not implemented");
        return Optional.empty();
    }

    /**
     * Strict verification entry point.
     *
     * @throws UnsupportedOperationException always
     */
    public CallerIdentity requireVerified(String clientIdentityMetadataDocument) {
        throw new UnsupportedOperationException(
                "CIMD request authentication is not implemented in 0.1.0; see DESIGN section 10");
    }

    private Optional<ClientDocument> fetchAndParse(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            LOG.debug("CIMD rejected: malformed URL {}", url);
            return Optional.empty();
        }
        if (!fetchable(uri)) {
            LOG.debug("CIMD rejected: {} is not an absolute https URL", url);
            return Optional.empty();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(5))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOG.warn("CIMD fetch returned status {} from {}", response.statusCode(), url);
                return Optional.empty();
            }
            String body = response.body();
            if (body != null && body.length() > MAX_DOCUMENT_BYTES) {
                LOG.warn("CIMD document at {} exceeds {} bytes", url, MAX_DOCUMENT_BYTES);
                return Optional.empty();
            }
            return parseDocument(url, body);
        } catch (IOException e) {
            LOG.warn("CIMD fetch failed from {}: {}", url, e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("CIMD fetch interrupted");
            return Optional.empty();
        }
    }

    /**
     * HTTPS is required, because a document fetched over plaintext identifies whoever is on the
     * path rather than whoever published it. Plain HTTP is tolerated for a loopback host only, so
     * a developer can run a client and the gateway on one machine without a certificate.
     */
    private static boolean fetchable(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return false;
        }
        if ("https".equals(scheme)) {
            return true;
        }
        return "http".equals(scheme) && isLoopback(host);
    }

    private static boolean isLoopback(String host) {
        return "127.0.0.1".equals(host)
                || "localhost".equalsIgnoreCase(host)
                || "::1".equals(host)
                || "[::1]".equals(host);
    }

    /** Compares two URLs ignoring a case difference in scheme and host and one trailing slash. */
    private static boolean sameUrl(String a, String b) {
        return canonical(a).equals(canonical(b));
    }

    private static String canonical(String url) {
        String trimmed = url.trim();
        try {
            URI uri = URI.create(trimmed);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return trimmed;
            }
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            if (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            String port = uri.getPort() == -1 ? "" : ":" + uri.getPort();
            String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
            return uri.getScheme().toLowerCase(Locale.ROOT) + "://"
                    + uri.getHost().toLowerCase(Locale.ROOT) + port + path + query;
        } catch (IllegalArgumentException e) {
            return trimmed;
        }
    }

    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static Set<String> strings(Object v) {
        Set<String> out = new LinkedHashSet<>();
        if (v instanceof Iterable<?> it) {
            for (Object o : it) {
                String s = str(o);
                if (s != null) {
                    out.add(s);
                }
            }
        } else {
            String s = str(v);
            if (s != null) {
                out.add(s);
            }
        }
        return Set.copyOf(out);
    }

    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    /**
     * A verified client identity metadata document.
     *
     * @param clientId the document URL, which is the client identifier
     * @param clientName human readable name, or {@code null}
     * @param redirectUris declared redirect URIs
     * @param grantTypes declared grant types
     * @param softwareId declared software id, or {@code null}
     * @param softwareStatement declared software statement JWT, or {@code null}
     * @author Viquar Khan
     */
    public record ClientDocument(
            String clientId,
            String clientName,
            Set<String> redirectUris,
            Set<String> grantTypes,
            String softwareId,
            String softwareStatement) {
    }

    private record CachedDocument(ClientDocument document, long expiresAtMillis) {
    }
}
