package io.github.vaquarkhan.aegis.adapter.flink;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.vaquarkhan.aegis.adapter.flink.client.SqlGatewayClient;
import io.github.vaquarkhan.aegis.core.observability.Metrics;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Session lifecycle and result paging against a fake SQL Gateway; no gateway process is required.
 *
 * @author Viquar Khan
 */
class SqlGatewayClientTest {

    private static HttpServer server;
    private static String baseUrl;
    private static final List<String> CALLS = Collections.synchronizedList(new ArrayList<>());

    private static final McpJsonMapper JSON = new JacksonMcpJsonMapperSupplier().get();

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/info", ex -> respond(ex, 200, "{\"productVersion\":\"1.20\"}"));
        server.createContext("/v1/sessions", SqlGatewayClientTest::handleSession);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void handleSession(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        CALLS.add(method + " " + path);
        if ("POST".equals(method) && path.equals("/v1/sessions")) {
            respond(ex, 200, "{\"sessionHandle\":\"s1\"}");
        } else if ("POST".equals(method) && path.endsWith("/statements")) {
            respond(ex, 200, "{\"operationHandle\":\"op1\"}");
        } else if ("GET".equals(method) && path.endsWith("/result/0")) {
            respond(ex, 200, "{\"resultType\":\"PAYLOAD\",\"results\":{\"data\":[{\"c\":1}]},"
                    + "\"nextResultUri\":\"/v1/sessions/s1/operations/op1/result/1\"}");
        } else if ("GET".equals(method) && path.endsWith("/result/1")) {
            respond(ex, 200, "{\"resultType\":\"EOS\",\"results\":{\"data\":[]}}");
        } else if ("DELETE".equals(method)) {
            respond(ex, 200, "{\"status\":\"CLOSED\"}");
        } else {
            respond(ex, 404, "{\"errors\":[\"not found\"]}");
        }
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Test
    void executeDrainsPagesAndClosesTheSession() {
        CALLS.clear();
        SqlGatewayClient client = new SqlGatewayClient(baseUrl, JSON, new Metrics());
        String result = client.execute("SELECT 1");
        assertTrue(result.startsWith("["));
        assertTrue(result.contains("PAYLOAD"));
        assertTrue(result.contains("EOS"));
        assertTrue(CALLS.contains("DELETE /v1/sessions/s1"), "session was not closed: " + CALLS);
    }

    @Test
    void executeRecordsBandwidth() {
        Metrics metrics = new Metrics();
        new SqlGatewayClient(baseUrl, JSON, metrics).execute("SELECT 1");
        assertTrue(bandwidth(metrics, "bytes_in") > 0);
        assertTrue(bandwidth(metrics, "bytes_out") > 0);
    }

    /** Metrics exposes bandwidth only through its JSON snapshot. */
    private static long bandwidth(Metrics metrics, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(\\d+)").matcher(metrics.toJson());
        return m.find() ? Long.parseLong(m.group(1)) : -1L;
    }

    @Test
    void pingSucceedsAgainstInfoEndpoint() {
        assertTrue(new SqlGatewayClient(baseUrl, JSON, new Metrics()).ping());
    }

    @Test
    void pingFailsWhenGatewayIsUnreachable() {
        assertFalse(new SqlGatewayClient("http://127.0.0.1:1", JSON, new Metrics()).ping());
    }

    @Test
    void unreachableGatewayThrowsGatewayException() {
        SqlGatewayClient client = new SqlGatewayClient("http://127.0.0.1:1", JSON, new Metrics());
        assertThrows(SqlGatewayClient.GatewayException.class, () -> client.execute("SELECT 1"));
    }
}
