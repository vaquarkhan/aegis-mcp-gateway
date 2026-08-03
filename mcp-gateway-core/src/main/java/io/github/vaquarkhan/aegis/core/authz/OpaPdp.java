package io.github.vaquarkhan.aegis.core.authz;

import io.github.vaquarkhan.aegis.core.auth.CallerIdentity;
import io.github.vaquarkhan.aegis.core.util.Inputs;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Open Policy Agent decision point.
 *
 * <p>POSTs an input document to {@code MCP_GW_POLICY_FILE} interpreted as an absolute OPA data API
 * URL (for example {@code http://opa:8181/v1/data/aegis/allow}). The response must contain a boolean
 * {@code result} (or {@code result.allow}). Transport and parse failures deny. Without a configured
 * http(s) URL every call is denied (fail-closed).
 *
 * @author Viquar Khan
 */
public final class OpaPdp implements PolicyDecisionPoint {

    private static final Logger LOG = LoggerFactory.getLogger(OpaPdp.class);
    private static final long CACHE_TTL_MS = 2_000L;

    private final String decisionUrl;
    private final HttpClient http;
    private final Duration timeout;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public OpaPdp(String decisionUrl) {
        this(decisionUrl, Duration.ofSeconds(2));
    }

    public OpaPdp(String decisionUrl, Duration timeout) {
        this.decisionUrl = blankToNull(decisionUrl);
        this.timeout = timeout == null ? Duration.ofSeconds(2) : timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        if (this.decisionUrl == null) {
            LOG.warn("OPA PDP has no decision URL; denying every call (set MCP_GW_POLICY_FILE to an "
                    + "http(s) OPA data API URL)");
        } else if (!isHttpUrl(this.decisionUrl)) {
            LOG.warn("OPA PDP decision URL is not http(s); denying every call (got {})", this.decisionUrl);
        } else {
            LOG.info("OPA PDP live against {}", this.decisionUrl);
        }
    }

    public String decisionUrl() {
        return decisionUrl;
    }

    @Override
    public boolean allows(CallerIdentity caller, String tool, Map<String, Object> args) {
        if (decisionUrl == null || !isHttpUrl(decisionUrl)) {
            return false;
        }
        String callerId = caller == null ? "-" : caller.callerId();
        String cacheKey = callerId + "|" + tool;
        CacheEntry cached = cache.get(cacheKey);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAt > now) {
            return cached.allow;
        }
        boolean allow = decideRemote(caller, tool, args);
        cache.put(cacheKey, new CacheEntry(allow, now + CACHE_TTL_MS));
        return allow;
    }

    private boolean decideRemote(CallerIdentity caller, String tool, Map<String, Object> args) {
        try {
            String body = buildInput(caller, tool, args);
            HttpRequest request = HttpRequest.newBuilder(URI.create(decisionUrl))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOG.warn("OPA decision HTTP {} for tool {}", response.statusCode(), tool);
                return false;
            }
            return parseAllow(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("OPA decision interrupted for tool {}", tool);
            return false;
        } catch (Exception e) {
            LOG.warn("OPA decision failed for tool {}: {}", tool, e.getMessage());
            return false;
        }
    }

    public static String buildInput(CallerIdentity caller, String tool, Map<String, Object> args) {
        String subject = caller == null ? "-" : Inputs.jsonEscape(caller.callerId());
        String tenant = caller == null || caller.tenant() == null ? "" : Inputs.jsonEscape(caller.tenant());
        boolean readonly = caller != null && caller.readonly();
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"input\":{")
                .append("\"principal\":{\"id\":\"").append(subject).append("\",\"tenant\":\"")
                .append(tenant).append("\",\"readonly\":").append(readonly).append("},")
                .append("\"action\":{\"name\":\"").append(Inputs.jsonEscape(tool)).append("\"},")
                .append("\"resource\":{\"tool\":\"").append(Inputs.jsonEscape(tool)).append("\"}")
                .append("}}");
        return sb.toString();
    }

    public static boolean parseAllow(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        String compact = body.replaceAll("\\s+", "");
        if (compact.contains("\"result\":true") || compact.contains("\"allow\":true")) {
            return true;
        }
        if (compact.contains("\"result\":{\"allow\":true}")) {
            return true;
        }
        return false;
    }

    private static boolean isHttpUrl(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private record CacheEntry(boolean allow, long expiresAt) {}
}
