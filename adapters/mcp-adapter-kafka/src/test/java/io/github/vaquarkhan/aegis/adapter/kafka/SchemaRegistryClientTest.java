package io.github.vaquarkhan.aegis.adapter.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.vaquarkhan.aegis.adapter.kafka.client.SchemaRegistryClient;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** @author Viquar Khan */
class SchemaRegistryClientTest {

    @Test
    void readsLatestVersionFromEmbeddedRegistry() throws Exception {
        List<String> requested = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/subjects", exchange -> {
            requested.add(exchange.getRequestURI().getPath());
            byte[] body = "{\"subject\":\"orders-value\",\"version\":7,\"id\":11,\"schema\":\"\\\"string\\\"\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            SchemaRegistryClient client = new SchemaRegistryClient(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/");
            String body = client.latestVersion("orders-value");
            assertTrue(body.contains("\"version\":7"));
            assertEquals(List.of("/subjects/orders-value/versions/latest"), requested);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void registryErrorStatusBecomesBackendFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/subjects", exchange -> {
            byte[] body = "{\"error_code\":40401,\"message\":\"Subject not found\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            SchemaRegistryClient client = new SchemaRegistryClient(
                    "http://127.0.0.1:" + server.getAddress().getPort());
            assertThrows(IllegalStateException.class, () -> client.latestVersion("missing-value"),
                    "an error status must surface as a backend failure, not an empty schema");
        } finally {
            server.stop(0);
        }
    }
}
