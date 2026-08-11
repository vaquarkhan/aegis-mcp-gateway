package io.github.vaquarkhan.aegis.adapter.flink.client;

import io.github.vaquarkhan.aegis.core.governance.EgressConnect;
import io.github.vaquarkhan.aegis.core.observability.Metrics;
import io.github.vaquarkhan.aegis.core.observability.Trace;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin JDK HttpClient wrapper over the Flink JobManager REST API. No Flink runtime classes
 * are referenced, so the adapter stays version independent.
 *
 * @author Viquar Khan
 */
public final class FlinkRestClient {

    public static final class BackendException extends RuntimeException {
        private final int status;

        public BackendException(String message) {
            this(message, -1, null);
        }

        public BackendException(String message, Throwable cause) {
            this(message, -1, cause);
        }

        public BackendException(String message, int status) {
            this(message, status, null);
        }

        public BackendException(String message, int status, Throwable cause) {
            super(message, cause);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(FlinkRestClient.class);

    private final String baseUrl;
    private final Metrics metrics;
    private final HttpClient http;
    private final String authHeader;

    public FlinkRestClient(String baseUrl, Metrics metrics) {
        this(baseUrl, metrics, null);
    }

    public FlinkRestClient(String baseUrl, Metrics metrics, String authHeader) {
        String u = baseUrl == null ? "" : baseUrl;
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        this.baseUrl = u;
        this.metrics = metrics;
        this.authHeader = (authHeader == null || authHeader.isBlank()) ? null : authHeader;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        if (!this.baseUrl.isBlank()) {
            io.github.vaquarkhan.aegis.core.governance.EgressConnect.pin(this.baseUrl + "/");
        }
    }

    public String baseUrl() {
        return baseUrl;
    }

    public boolean ping() {
        try {
            get("/overview");
            return true;
        } catch (Exception e) {
            LOG.warn("flink ping failed: {}", e.getMessage());
            return false;
        }
    }

    public String get(String path) {
        return send("GET", path, null, Duration.ofSeconds(15));
    }

    public String post(String path, String jsonBody) {
        return send("POST", path, jsonBody, Duration.ofSeconds(30));
    }

    public String patch(String path, String jsonBody) {
        return send("PATCH", path, jsonBody, Duration.ofSeconds(30));
    }

    public String delete(String path) {
        return send("DELETE", path, null, Duration.ofSeconds(30));
    }

    public String uploadJar(Path jar) {
        try {
            String boundary = "----AegisFlink" + UUID.randomUUID().toString().replace("-", "");
            byte[] fileBytes = Files.readAllBytes(jar);
            String filename = jar.getFileName().toString();
            byte[] preamble = (
                    "--" + boundary + "\r\n"
                            + "Content-Disposition: form-data; name=\"jarfile\"; filename=\"" + filename + "\"\r\n"
                            + "Content-Type: application/java-archive\r\n\r\n"
            ).getBytes(StandardCharsets.UTF_8);
            byte[] epilogue = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
            byte[] body = new byte[preamble.length + fileBytes.length + epilogue.length];
            System.arraycopy(preamble, 0, body, 0, preamble.length);
            System.arraycopy(fileBytes, 0, body, preamble.length, fileBytes.length);
            System.arraycopy(epilogue, 0, body, preamble.length + fileBytes.length, epilogue.length);

            EgressConnect.PinnedTarget pinned = EgressConnect.pin(baseUrl + "/jars/upload");
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(pinned.requestUri())
                    .timeout(Duration.ofSeconds(60))
                    
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            applyAuth(b);
            metrics.addBytesOut(body.length);
            long t0 = System.nanoTime();
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            String respBody = resp.body() == null ? "" : resp.body();
            metrics.addBytesIn(respBody.getBytes(StandardCharsets.UTF_8).length);
            LOG.info("flink upload status={} ms={} bytes_out={} bytes_in={}",
                    resp.statusCode(), (System.nanoTime() - t0) / 1_000_000L, body.length, respBody.length());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new BackendException("HTTP " + resp.statusCode() + ": " + truncate(respBody), resp.statusCode());
            }
            return respBody;
        } catch (BackendException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new BackendException("upload failed: " + e.getMessage(), e);
        }
    }

    private String send(String method, String path, String jsonBody, Duration timeout) {
        try {
            String p = path.startsWith("/") ? path : "/" + path;
            EgressConnect.PinnedTarget pinned = EgressConnect.pin(baseUrl + p);
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(pinned.requestUri())
                    .timeout(timeout)
                    ;
            applyAuth(b);
            byte[] out = jsonBody == null ? new byte[0] : jsonBody.getBytes(StandardCharsets.UTF_8);
            metrics.addBytesOut(out.length);
            switch (method) {
                case "GET" -> b.GET();
                case "DELETE" -> b.DELETE();
                case "POST" -> {
                    b.header("Content-Type", "application/json");
                    b.POST(HttpRequest.BodyPublishers.ofByteArray(out));
                }
                case "PATCH" -> {
                    b.header("Content-Type", "application/json");
                    b.method("PATCH", HttpRequest.BodyPublishers.ofByteArray(out));
                }
                default -> throw new BackendException("unsupported method " + method);
            }
            long t0 = System.nanoTime();
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            String respBody = resp.body() == null ? "" : resp.body();
            metrics.addBytesIn(respBody.getBytes(StandardCharsets.UTF_8).length);
            LOG.debug("flink {} {} status={} ms={} trace={}",
                    method, p, resp.statusCode(), (System.nanoTime() - t0) / 1_000_000L, Trace.get());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new BackendException("HTTP " + resp.statusCode() + ": " + truncate(respBody), resp.statusCode());
            }
            return respBody;
        } catch (BackendException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new BackendException(method + " failed: " + e.getMessage(), e);
        }
    }

    private void applyAuth(HttpRequest.Builder b) {
        String header = OutboundAuth.toAuthorizationValue(OutboundAuth.resolveFlink(authHeader));
        if (header != null) {
            b.header("Authorization", header);
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 300 ? s : s.substring(0, 300);
    }
}
