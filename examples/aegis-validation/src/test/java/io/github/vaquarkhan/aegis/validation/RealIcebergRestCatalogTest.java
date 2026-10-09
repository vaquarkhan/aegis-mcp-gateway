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
package io.github.vaquarkhan.aegis.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.config.BuildContext;
import io.github.vaquarkhan.aegis.config.Gateway;
import io.github.vaquarkhan.aegis.config.GatewayFactory;
import io.github.vaquarkhan.aegis.engine.CallOutcome;
import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.model.ControlTokens;
import io.github.vaquarkhan.aegis.engine.model.DenyCodes;
import io.github.vaquarkhan.aegis.engine.model.ToolCall;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * The canonical adopters/configurations/iceberg/iceberg.yaml governing a REAL Apache Iceberg REST catalog: the official
 * RESTCatalogServer from org.apache.iceberg:iceberg-open-api (the same server the
 * apache/iceberg-rest-fixture image runs), started separately. Set AEGIS_ICEBERG_REST_URL to its
 * base URL, e.g. http://127.0.0.1:18181. Skipped when unset.
 */
@EnabledIfEnvironmentVariable(named = "AEGIS_ICEBERG_REST_URL", matches = "http://127\\.0\\.0\\.1:\\d+")
class RealIcebergRestCatalogTest {

    @TempDir
    Path dir;

    private final HttpClient http = HttpClient.newHttpClient();

    private int send(String method, String url, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void shippedIcebergConfigGovernsARealRestCatalog() throws Exception {
        String base = System.getenv("AEGIS_ICEBERG_REST_URL");
        // Fixture data created directly against the catalog (not through Aegis).
        String suffix = Long.toHexString(System.nanoTime());
        String sales = "sales";
        String table = "orders_" + suffix;
        send("POST", base + "/v1/namespaces", "{\"namespace\":[\"sales\"],\"properties\":{}}");
        send("POST", base + "/v1/namespaces", "{\"namespace\":[\"finance\"],\"properties\":{}}");
        String schema = "{\"type\":\"struct\",\"schema-id\":0,\"fields\":[{\"id\":1,\"name\":\"id\",\"required\":true,\"type\":\"long\"}]}";
        assertEquals(200, send("POST", base + "/v1/namespaces/sales/tables", "{\"name\":\"" + table + "\",\"schema\":" + schema + "}"));
        send("POST", base + "/v1/namespaces/finance/tables", "{\"name\":\"ledger_" + suffix + "\",\"schema\":" + schema + "}");

        String yaml = Files.readString(Path.of("..", "..", "adopters", "configurations", "iceberg", "iceberg.yaml"))
                .replace("http://iceberg-rest:8181", base)
                .replace("allowCidrs: [10.0.0.0/8]", "allowCidrs: [127.0.0.0/8]");
        Path cfg = dir.resolve("iceberg.yaml");
        Files.writeString(cfg, yaml);
        Map<String, String> env = Map.of("AEGIS_SIGNING_KEY", "real-iceberg-validation-key-0123456789abcdef",
                "ICEBERG_CATALOG_TOKEN", "unused-by-the-fixture");
        try (Gateway g = GatewayFactory.build(cfg, BuildContext.defaults(dir).withEnv(env::get))) {
            CallerIdentity analyst = CallerIdentity.of("ana", Set.of("analysts"), "idp", "jwt");
            CallerIdentity eng = CallerIdentity.of("eve", Set.of("data-engineers"), "idp", "jwt");
            CallerIdentity steward = CallerIdentity.of("sten", Set.of("data-stewards"), "idp", "jwt");

            CallOutcome ns = g.engine().call(analyst, new ToolCall("1", "iceberg_list_namespaces", Map.of(), Map.of(), ""));
            assertTrue(ns.allowed() && Json.write(ns.toMcpResult()).contains("sales"), Json.write(ns.toMcpResult()));
            CallOutcome tables = g.engine().call(analyst, new ToolCall("2", "iceberg_list_tables", Map.of("namespace", sales), Map.of(), ""));
            assertTrue(Json.write(tables.toMcpResult()).contains(table), Json.write(tables.toMcpResult()));
            assertEquals(DenyCodes.SCOPE_DENIED, g.engine().call(analyst,
                    new ToolCall("3", "iceberg_list_tables", Map.of("namespace", "finance"), Map.of(), "")).decision().code());
            CallOutcome load = g.engine().call(analyst, new ToolCall("4", "iceberg_load_table", Map.of("namespace", sales, "table", table), Map.of(), ""));
            assertTrue(Json.write(load.toMcpResult()).contains("\"name\":\"id\""), Json.write(load.toMcpResult()));
            assertEquals(DenyCodes.POLICY_DENIED, g.engine().call(eng, new ToolCall("5", "iceberg_drop_table",
                    Map.of("namespace", "finance", "table", "ledger_" + suffix), Map.of(), "")).decision().code());

            Map<String, Object> args = Map.of("namespace", sales, "table", table);
            CallOutcome dry = g.engine().call(eng, new ToolCall("6", "iceberg_drop_table", args, Map.of(ControlTokens.META_DRY_RUN, true), ""));
            assertTrue(dry.allowed() && Json.write(dry.toMcpResult()).contains("\"name\":\"id\""), "TOOL dry run loaded the real table");
            String rid = (String) dry.governance().get("approvalRequestId");
            String receipt = (String) dry.governance().get("dryRunReceipt");
            assertNotNull(receipt);
            assertEquals(200, send("GET", base + "/v1/namespaces/sales/tables/" + table, null), "still exists before promote");
            g.engine().approvals().decide(rid, steward, true);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put(ControlTokens.META_APPROVAL, g.engine().approvals().get(rid, eng).get("token"));
            meta.put(ControlTokens.META_RECEIPT, receipt);
            CallOutcome drop = g.engine().call(eng, new ToolCall("7", "iceberg_drop_table", args, meta, ""));
            assertTrue(drop.allowed(), Json.write(drop.toMcpResult()));
            assertEquals(404, send("GET", base + "/v1/namespaces/sales/tables/" + table, null), "the real catalog dropped the table");
        }
    }
}
