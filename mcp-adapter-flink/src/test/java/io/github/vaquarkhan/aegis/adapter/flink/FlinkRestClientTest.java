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

package io.github.vaquarkhan.aegis.adapter.flink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.vaquarkhan.aegis.adapter.flink.client.FlinkRestClient;
import io.github.vaquarkhan.aegis.core.observability.Metrics;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Offline tests against an embedded HTTP server; no Flink cluster is required.
 *
 * @author Viquar Khan
 */
class FlinkRestClientTest {

    private static HttpServer server;
    private static String baseUrl;
    private static final AtomicReference<String> LAST_AUTH = new AtomicReference<>();

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jobs/overview", ex -> {
            LAST_AUTH.set(ex.getRequestHeaders().getFirst("Authorization"));
            respond(ex, 200, "{\"jobs\":[]}");
        });
        server.createContext("/jobs/bad", ex -> respond(ex, 404, "missing"));
        server.createContext("/jars/x/run", ex -> respond(ex, 200, "{\"jobid\":\"abc\"}"));
        server.createContext("/jars/upload", ex -> respond(ex, 200, "{\"filename\":\"job.jar\"}"));
        server.createContext("/jobs/job-1", ex -> respond(ex, 200, "{\"mode\":\"" + ex.getRequestMethod() + "\"}"));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Test
    void get_returnsBodyAndRecordsBandwidth() {
        Metrics metrics = new Metrics();
        FlinkRestClient client = new FlinkRestClient(baseUrl, metrics);
        String body = client.get("/jobs/overview");
        assertTrue(body.contains("jobs"));
        assertTrue(bandwidth(metrics, "bytes_in") > 0);
    }

    /** Metrics exposes bandwidth only through its JSON snapshot. */
    private static long bandwidth(Metrics metrics, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(\\d+)").matcher(metrics.toJson());
        return m.find() ? Long.parseLong(m.group(1)) : -1L;
    }

    @Test
    void get_non2xxThrowsBackendException() {
        FlinkRestClient client = new FlinkRestClient(baseUrl, new Metrics());
        assertThrows(FlinkRestClient.BackendException.class, () -> client.get("/jobs/bad"));
    }

    @Test
    void post_returnsBody() {
        FlinkRestClient client = new FlinkRestClient(baseUrl, new Metrics());
        String body = client.post("/jars/x/run", "{}");
        assertTrue(body.contains("abc"));
    }

    @Test
    void patch_usesPatchMethod() {
        FlinkRestClient client = new FlinkRestClient(baseUrl, new Metrics());
        String body = client.patch("/jobs/job-1", "{\"mode\":\"cancel\"}");
        assertTrue(body.contains("PATCH"));
    }

    @Test
    void connectionRefused_throwsBackendException() {
        FlinkRestClient client = new FlinkRestClient("http://127.0.0.1:1", new Metrics());
        assertThrows(FlinkRestClient.BackendException.class, () -> client.get("/jobs/overview"));
    }

    @Test
    void trailingSlashesAreStrippedFromBaseUrl() {
        FlinkRestClient client = new FlinkRestClient(baseUrl + "///", new Metrics());
        assertEquals(baseUrl, client.baseUrl());
        assertTrue(client.get("/jobs/overview").contains("jobs"));
    }

    @Test
    void staticCredentialBecomesBearerHeader() {
        FlinkRestClient client = new FlinkRestClient(baseUrl, new Metrics(), "token-abc");
        client.get("/jobs/overview");
        assertEquals("Bearer token-abc", LAST_AUTH.get());
    }

    @Test
    void uploadJar_postsMultipartBody() throws IOException {
        Path jar = Files.createTempFile("aegis-flink-test", ".jar");
        try {
            Files.write(jar, new byte[]{1, 2, 3});
            FlinkRestClient client = new FlinkRestClient(baseUrl, new Metrics());
            assertTrue(client.uploadJar(jar).contains("job.jar"));
        } finally {
            Files.deleteIfExists(jar);
        }
    }
}
