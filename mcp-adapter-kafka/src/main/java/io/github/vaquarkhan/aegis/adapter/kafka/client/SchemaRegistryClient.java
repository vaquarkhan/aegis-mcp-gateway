package io.github.vaquarkhan.aegis.adapter.kafka.client;

import io.github.vaquarkhan.aegis.core.governance.EgressConnect;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Confluent compatible Schema Registry client over the JDK HTTP client. No registry client
 * dependency is pulled in, so the adapter stays free of the Confluent artifact.
 *
 * @author Viquar Khan
 */
public final class SchemaRegistryClient implements SchemaRegistryOps {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaRegistryClient.class);

    private final String baseUrl;
    private final HttpClient http;

    public SchemaRegistryClient(String baseUrl) {
        String u = baseUrl == null ? "" : baseUrl.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        if (u.isEmpty()) {
            throw new IllegalArgumentException("schema registry url required");
        }
        this.baseUrl = u;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        EgressConnect.pin(this.baseUrl + "/");
    }

    public String baseUrl() {
        return baseUrl;
    }

    @Override
    public String latestVersion(String subject) {
        String path = "/subjects/" + URLEncoder.encode(subject, StandardCharsets.UTF_8) + "/versions/latest";
        try {
            EgressConnect.PinnedTarget pinned = EgressConnect.pin(URI.create(baseUrl + path));
            HttpRequest req = HttpRequest.newBuilder(pinned.requestUri())
                    .timeout(Duration.ofSeconds(15))
                    
                    .header("Accept", "application/vnd.schemaregistry.v1+json, application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                throw new IllegalStateException("HTTP " + resp.statusCode());
            }
            return resp.body() == null ? "" : resp.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("schema registry request interrupted", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            LOG.warn("schema registry lookup failed subject={} msg={}", subject, e.getMessage());
            throw new IllegalStateException("schema registry error: " + e.getMessage(), e);
        }
    }
}
