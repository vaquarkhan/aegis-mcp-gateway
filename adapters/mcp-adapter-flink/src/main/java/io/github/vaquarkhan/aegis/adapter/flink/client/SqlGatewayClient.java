package io.github.vaquarkhan.aegis.adapter.flink.client;

import io.github.vaquarkhan.aegis.core.governance.EgressConnect;
import io.github.vaquarkhan.aegis.core.observability.Metrics;
import io.github.vaquarkhan.aegis.core.util.Inputs;
import io.modelcontextprotocol.json.McpJsonMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Flink SQL Gateway REST client. Opens a session, submits one statement, drains the result
 * pages and always closes the session.
 *
 * @author Viquar Khan
 */
public final class SqlGatewayClient {

    public static final class GatewayException extends RuntimeException {
        public GatewayException(String message) {
            super(message);
        }

        public GatewayException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(SqlGatewayClient.class);

    private final String baseUrl;
    private final McpJsonMapper json;
    private final Metrics metrics;
    private final HttpClient http;
    private final String authHeader;

    public SqlGatewayClient(String baseUrl, McpJsonMapper json, Metrics metrics) {
        this(baseUrl, json, metrics, null);
    }

    public SqlGatewayClient(String baseUrl, McpJsonMapper json, Metrics metrics, String authHeader) {
        String u = baseUrl == null ? "" : baseUrl;
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        this.baseUrl = u;
        this.json = json;
        this.metrics = metrics;
        this.authHeader = (authHeader == null || authHeader.isBlank()) ? null : authHeader;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        if (!this.baseUrl.isBlank()) {
            EgressConnect.pin(this.baseUrl + "/");
        }
    }

    public String baseUrl() {
        return baseUrl;
    }

    public boolean ping() {
        try {
            // Flink SQL Gateway exposes /v1/info or /v3/info depending on version
            try {
                get("/v1/info");
            } catch (GatewayException e) {
                get("/v3/info");
            }
            return true;
        } catch (Exception e) {
            LOG.warn("gateway ping failed: {}", e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    public String execute(String statement) {
        String sessionHandle = null;
        try {
            String openBody = "{}";
            String openResp = post("/v1/sessions", openBody);
            Map<String, Object> openMap = json.readValue(openResp, Map.class);
            sessionHandle = String.valueOf(openMap.get("sessionHandle"));
            String submitBody = "{\"statement\":\"" + Inputs.jsonEscape(statement) + "\"}";
            String submitResp = post("/v1/sessions/" + sessionHandle + "/statements", submitBody);
            Map<String, Object> submitMap = json.readValue(submitResp, Map.class);
            String operationHandle = String.valueOf(submitMap.get("operationHandle"));
            String resultUri = "/v1/sessions/" + sessionHandle + "/operations/" + operationHandle + "/result/0";
            List<String> pages = new ArrayList<>();
            for (int i = 0; i < 600; i++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new GatewayException("interrupted while polling results");
                }
                String body = get(resultUri);
                Map<String, Object> page = json.readValue(body, Map.class);
                Object resultType = page.get("resultType");
                String type = resultType == null ? "" : String.valueOf(resultType);
                Object next = page.get("nextResultUri");
                if ("NOT_READY".equals(type)) {
                    Thread.sleep(200);
                    if (next != null) {
                        resultUri = relativize(String.valueOf(next));
                    }
                    continue;
                }
                pages.add(body);
                if ("EOS".equals(type) || next == null) {
                    break;
                }
                resultUri = relativize(String.valueOf(next));
            }
            LOG.info("gateway execute pages={} session={}", pages.size(), sessionHandle);
            return "[" + String.join(",", pages) + "]";
        } catch (GatewayException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new GatewayException("gateway execute failed: " + e.getMessage(), e);
        } finally {
            if (sessionHandle != null) {
                try {
                    delete("/v1/sessions/" + sessionHandle);
                } catch (Exception ignored) {
                    LOG.debug("session cleanup failed for {}", sessionHandle);
                }
            }
        }
    }

    private String relativize(String nextResultUri) {
        if (nextResultUri.startsWith("http://") || nextResultUri.startsWith("https://")) {
            URI u = URI.create(nextResultUri);
            String path = u.getRawPath();
            if (u.getRawQuery() != null) {
                path = path + "?" + u.getRawQuery();
            }
            return path;
        }
        return nextResultUri.startsWith("/") ? nextResultUri : "/" + nextResultUri;
    }

    private String get(String path) {
        return send("GET", path, null);
    }

    private String post(String path, String body) {
        return send("POST", path, body);
    }

    private void delete(String path) {
        send("DELETE", path, null);
    }

    private String send(String method, String path, String body) {
        try {
            String p = path.startsWith("/") ? path : "/" + path;
            EgressConnect.PinnedTarget pinned = EgressConnect.pin(baseUrl + p);
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(pinned.requestUri())
                    .timeout(Duration.ofSeconds(30))
                    ;
            String auth = OutboundAuth.toAuthorizationValue(OutboundAuth.resolveGateway(authHeader));
            if (auth != null) {
                b.header("Authorization", auth);
            }
            byte[] out = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            metrics.addBytesOut(out.length);
            switch (method) {
                case "GET" -> b.GET();
                case "DELETE" -> b.DELETE();
                case "POST" -> {
                    b.header("Content-Type", "application/json");
                    b.POST(HttpRequest.BodyPublishers.ofByteArray(out));
                }
                default -> throw new GatewayException("unsupported " + method);
            }
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            String respBody = resp.body() == null ? "" : resp.body();
            metrics.addBytesIn(respBody.getBytes(StandardCharsets.UTF_8).length);
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new GatewayException("HTTP " + resp.statusCode() + ": " + respBody);
            }
            return respBody;
        } catch (GatewayException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new GatewayException(method + " failed: " + e.getMessage(), e);
        }
    }
}
