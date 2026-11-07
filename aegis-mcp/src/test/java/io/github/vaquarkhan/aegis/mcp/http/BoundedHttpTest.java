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
package io.github.vaquarkhan.aegis.mcp.http;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class BoundedHttpTest {

    @Test
    void aBackendThatTricklesItsBodyHitsTheTotalDeadline() throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/slow", ex -> {
            ex.sendResponseHeaders(200, 0); // headers now, body "forever"
            try (OutputStream os = ex.getResponseBody()) {
                for (int i = 0; i < 100; i++) {
                    os.write('x');
                    os.flush();
                    Thread.sleep(200);
                }
            } catch (Exception ignored) {
                // client went away
            }
        });
        s.start();
        try {
            HttpClient http = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + s.getAddress().getPort() + "/slow"))
                    .timeout(Duration.ofSeconds(30)).GET().build();
            long t0 = System.nanoTime();
            assertThrows(HttpTimeoutException.class, () -> BoundedHttp.send(http, req, 1 << 20, 800));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(ms < 5_000, "returned after " + ms + " ms; the header timeout alone would have waited ~20 s");
        } finally {
            s.stop(0);
        }
    }
}
