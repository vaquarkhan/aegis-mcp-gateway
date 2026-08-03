/*
 * Licensed to the Aegis MCP Gateway project under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
