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

import io.github.vaquarkhan.aegis.validation.frontdoor.AdopterScenario;
import io.github.vaquarkhan.aegis.validation.frontdoor.FrontDoor;
import io.github.vaquarkhan.aegis.validation.frontdoor.GovernedFlow;
import io.github.vaquarkhan.aegis.validation.frontdoor.JavaServer;
import io.github.vaquarkhan.aegis.validation.frontdoor.Persona;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * examples/adopters/iceberg: the canonical {@code adopters/configurations/iceberg/iceberg.yaml} against the
 * official Apache Iceberg 1.10.1 REST catalog server ({@code RESTCatalogServer} from {@code iceberg-open-api},
 * JDBC catalog on SQLite) in its own JVM, through both front doors. Besides the approval flow this profile
 * shows the two refusals no approval can lift: a resource scope (analysts only see sales and marketing) and a
 * DENY rule (nobody drops a finance table).
 */
class IcebergFrontDoorsTest {

    private static JavaServer catalog;
    private static int port;
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String SCHEMA =
            "{\"type\":\"struct\",\"schema-id\":0,\"fields\":[{\"id\":1,\"name\":\"id\",\"required\":true,\"type\":\"long\"}]}";

    @TempDir
    Path dir;

    @BeforeAll
    static void startCatalog() throws Exception {
        Path work = Path.of("target", "iceberg-server").toAbsolutePath();
        Path warehouse = Files.createDirectories(work.resolve("warehouse"));
        port = JavaServer.freePort();
        // The same settings the tutorial's start-catalog script uses: RESTCatalogServer reads CATALOG_* variables.
        Map<String, String> env = Map.of(
                "CATALOG_REST_PORT", Integer.toString(port),
                "CATALOG_WAREHOUSE", warehouse.toString().replace('\\', '/'),
                "CATALOG_IO__IMPL", "org.apache.iceberg.inmemory.InMemoryFileIO",
                "CATALOG_CATALOG__IMPL", "org.apache.iceberg.jdbc.JdbcCatalog",
                "CATALOG_URI", "jdbc:sqlite:file:" + work.resolve("catalog-" + System.nanoTime() + ".db").toString().replace('\\', '/'),
                "CATALOG_JDBC_USER", "aegis",
                "CATALOG_JDBC_PASSWORD", "aegis");
        catalog = new JavaServer("aegis.iceberg.serverClasspathFile", List.of("-Xmx1G"),
                "org.apache.iceberg.rest.RESTCatalogServer", List.of(), env, work.resolve("iceberg-rest.log"));
        catalog.awaitReady(() -> send("GET", "/v1/config", null) == 200, Duration.ofSeconds(120), "Iceberg REST catalog");
        send("POST", "/v1/namespaces", "{\"namespace\":[\"sales\"],\"properties\":{}}");
        send("POST", "/v1/namespaces", "{\"namespace\":[\"finance\"],\"properties\":{}}");
        createTable("sales", "orders");
        createTable("finance", "ledger");
    }

    @AfterAll
    static void stopCatalog() throws Exception {
        if (catalog != null) {
            catalog.close();
        }
    }

    @ParameterizedTest(name = "iceberg through {0}")
    @ValueSource(strings = {FrontDoor.OWN_HOST, FrontDoor.KNOX})
    void governsARealIcebergRestCatalog(String door) throws Exception {
        String scratch = "scratch_" + door.replace("-", "");
        createTable("sales", scratch);

        AdopterScenario scenario = AdopterScenario.of("iceberg", AdopterScenario.canonical("iceberg", "iceberg.yaml"),
                yaml -> yaml.replace("http://iceberg-rest:8181", "http://127.0.0.1:" + port)
                        .replace("allowCidrs: [10.0.0.0/8]", "allowCidrs: [127.0.0.0/8]"),
                Map.of(Persona.READER, List.of("analysts"),
                        Persona.OPERATOR, List.of("data-engineers"),
                        Persona.APPROVER, List.of("data-stewards")),
                Map.of("ICEBERG_CATALOG_TOKEN", "aegis-validation-catalog-credential"));

        try (FrontDoor d = FrontDoor.open(door, scenario, dir)) {
            d.note("official Iceberg 1.10.1 REST catalog on 127.0.0.1:" + port + "; tables sales.orders, sales." + scratch + ", finance.ledger");
            GovernedFlow.read(d, "iceberg_list_tables", Map.of("namespace", "sales"), "orders");
            GovernedFlow.refused(d, "analyst outside their resource scope", Persona.READER, "iceberg_list_tables",
                    Map.of("namespace", "finance"), "SCOPE_DENIED", () -> { });
            GovernedFlow.refused(d, "drop a finance table (DENY rule, before any approval)", Persona.OPERATOR, "iceberg_drop_table",
                    Map.of("namespace", "finance", "table", "ledger"), "POLICY_DENIED",
                    () -> assertEquals(200, send("GET", "/v1/namespaces/finance/tables/ledger", null), "finance.ledger untouched"));
            GovernedFlow.destructive(d, "iceberg_drop_table", Map.of("namespace", "sales", "table", scratch),
                    () -> assertEquals(200, send("GET", "/v1/namespaces/sales/tables/" + scratch, null),
                            "denied and previewed calls did not drop sales." + scratch),
                    () -> {
                        assertEquals(404, send("GET", "/v1/namespaces/sales/tables/" + scratch, null), "sales." + scratch + " is gone");
                        assertEquals(200, send("GET", "/v1/namespaces/finance/tables/ledger", null), "finance.ledger untouched");
                        d.note("the catalog answers 404 for sales." + scratch + " and still has finance.ledger");
                    });
        }
    }

    /** Talks to the catalog directly, outside Aegis, for setup and to check real state. */
    static int send(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static void createTable(String namespace, String table) throws Exception {
        int s = send("POST", "/v1/namespaces/" + namespace + "/tables", "{\"name\":\"" + table + "\",\"schema\":" + SCHEMA + "}");
        if (s != 200) {
            throw new IllegalStateException("could not create " + namespace + "." + table + ": HTTP " + s);
        }
    }
}
