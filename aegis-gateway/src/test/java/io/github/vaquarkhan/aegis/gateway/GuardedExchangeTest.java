/*
 * Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vaquarkhan.aegis.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The request guard the own host wraps around every MCP, admin and metrics exchange, run on the real
 * JDK HTTP server and read back with the real JDK HTTP client.
 */
class GuardedExchangeTest {

    HttpServer server;
    final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fails-before-response", ex -> AegisServer.guarded(ex, "test", () -> {
            throw new IllegalStateException("internal detail that must not reach the client");
        }));
        server.createContext("/fails-after-response", ex -> AegisServer.guarded(ex, "test", () -> {
            byte[] body = "partial".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
            throw new IllegalStateException("after the response started");
        }));
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void anUnexpectedFailureIsAnsweredWithAGeneric500() throws Exception {
        HttpResponse<String> r = get("/fails-before-response");
        assertEquals(500, r.statusCode());
        assertEquals("{\"error\":\"internal error\"}", r.body());
        assertEquals("nosniff", r.headers().firstValue("X-Content-Type-Options").orElse(""));
        assertFalse(r.body().contains("internal detail"), "exception text never reaches the client");
    }

    @Test
    void aFailureAfterTheResponseStartedKeepsTheResponseThatWasSent() throws Exception {
        HttpResponse<String> r = get("/fails-after-response");
        assertEquals(200, r.statusCode());
        assertEquals("partial", r.body());
    }
}
