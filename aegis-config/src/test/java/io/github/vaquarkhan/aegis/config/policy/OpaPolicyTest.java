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
package io.github.vaquarkhan.aegis.config.policy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.vaquarkhan.aegis.engine.model.Action;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.model.ToolClass;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OpaPolicyTest {

    @Test
    void onlyAStrictBooleanTrueAllowsAndTheFullActionIsSent() throws Exception {
        AtomicReference<String> reply = new AtomicReference<>();
        AtomicReference<String> lastInput = new AtomicReference<>();
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/v1/data/aegis/allow", ex -> {
            lastInput.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] b = reply.get().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        });
        s.start();
        try {
            OpaPolicy p = new OpaPolicy(URI.create("http://127.0.0.1:" + s.getAddress().getPort() + "/v1/data/aegis/allow"),
                    Duration.ofSeconds(2), false, Map.of());
            CallerIdentity c = CallerIdentity.of("alice", Set.of("analysts"), "idp", "jwt");
            Action a = new Action("drop_table", ToolClass.DESTRUCTIVE, "iceberg", List.of("iceberg:table:sales.orders"), "sha256:ab");

            reply.set("{\"result\":true}");
            assertTrue(p.decide(c, a, Map.of()).allow());
            assertTrue(lastInput.get().contains("iceberg:table:sales.orders") && lastInput.get().contains("sha256:ab"),
                    "resources and argument digest reach the policy");
            reply.set("{\"result\":{\"allow\":true}}");
            assertTrue(p.decide(c, a, Map.of()).allow());
            reply.set("{\"result\":\"true\"}");
            assertFalse(p.decide(c, a, Map.of()).allow(), "string \"true\" is not a boolean");
            reply.set("{\"result\":{\"allow\":\"yes\",\"note\":\"allow\"}}");
            assertFalse(p.decide(c, a, Map.of()).allow(), "no substring matching");
            reply.set("{}");
            assertFalse(p.decide(c, a, Map.of()).allow(), "undefined decision denies");
        } finally {
            s.stop(0);
        }
    }
}
