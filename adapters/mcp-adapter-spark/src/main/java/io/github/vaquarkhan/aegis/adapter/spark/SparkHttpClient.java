package io.github.vaquarkhan.aegis.adapter.spark;

import io.github.vaquarkhan.aegis.core.governance.EgressConnect;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal JDK HTTP client for Livy, the History Server and an optional SQL endpoint. No Spark core
 * dependency, so the adapter stays independent of the cluster version.
 *
 * <p>Any non 2xx status or transport failure is rethrown as {@link IllegalStateException} so the
 * gateway circuit breaker observes a real backend failure. Redirects are never followed; each
 * request is pinned to a resolved non-denied IP.
 *
 * @author Viquar Khan
 */
public final class SparkHttpClient {

    private static final Logger LOG = LoggerFactory.getLogger(SparkHttpClient.class);

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration WRITE_TIMEOUT = Duration.ofSeconds(30);

    private final String baseUrl;
    private final HttpClient http;

    public SparkHttpClient(String baseUrl) {
        String u = baseUrl == null ? "" : baseUrl.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        this.baseUrl = u;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        if (!this.baseUrl.isBlank()) {
            EgressConnect.pin(this.baseUrl + "/");
        }
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String get(String path) {
        return send("GET", path, null, READ_TIMEOUT);
    }

    public String post(String path, String jsonBody) {
        return send("POST", path, jsonBody == null ? "{}" : jsonBody, WRITE_TIMEOUT);
    }

    public String delete(String path) {
        return send("DELETE", path, null, WRITE_TIMEOUT);
    }

    private String send(String method, String path, String jsonBody, Duration timeout) {
        String p = path == null || path.isEmpty() ? "" : path.startsWith("/") ? path : "/" + path;
        try {
            EgressConnect.PinnedTarget pinned = EgressConnect.pin(URI.create(baseUrl + p));
            HttpRequest.Builder builder = HttpRequest.newBuilder(pinned.requestUri())
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    ;
            switch (method) {
                case "GET" -> builder.GET();
                case "DELETE" -> builder.DELETE();
                case "POST" -> {
                    builder.header("Content-Type", "application/json");
                    builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
                }
                default -> throw new IllegalStateException("unsupported method " + method);
            }
            HttpResponse<String> resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                throw new IllegalStateException("HTTP " + resp.statusCode() + ": " + truncate(resp.body()));
            }
            return resp.body() == null ? "" : resp.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("spark backend request interrupted", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            LOG.warn("spark http {} failed path={} msg={}", method, p, reason);
            throw new IllegalStateException("spark backend error: " + reason, e);
        }
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 300 ? body : body.substring(0, 300);
    }
}
