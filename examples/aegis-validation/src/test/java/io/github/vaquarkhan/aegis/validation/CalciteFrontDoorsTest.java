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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * examples/adopters/calcite: the canonical {@code adopters/configurations/adapters/calcite/calcite.yaml}
 * against REAL Apache Calcite 1.42.0 behind the Avatica 1.28.0 standalone server, in its own JVM, through
 * both front doors. Calcite serves a schema from its file adapter over CSV files the test writes. The
 * profile is read-only by design, so the governed story is reads that really run in Calcite, and a write
 * smuggled into the read tool that is refused before Calcite sees it.
 */
class CalciteFrontDoorsTest {

    private static JavaServer avatica;
    private static int port;

    @TempDir
    Path dir;

    @BeforeAll
    static void startCalcite() throws Exception {
        Path work = Path.of("target", "calcite-server").toAbsolutePath();
        Path data = Files.createDirectories(work.resolve("sales"));
        Files.writeString(data.resolve("ORDERS.csv"),
                "ID:int,ITEM:string,PRICE:double\n1,widget,9.5\n2,gadget,12.0\n3,gizmo,3.25\n");
        Path model = work.resolve("model.json");
        Files.writeString(model, """
                {"version": "1.0", "defaultSchema": "SALES",
                 "schemas": [{"name": "SALES", "type": "custom",
                              "factory": "org.apache.calcite.adapter.file.FileSchemaFactory",
                              "operand": {"directory": "%s"}}]}
                """.formatted(data.toString().replace('\\', '/')));
        port = JavaServer.freePort();
        avatica = new JavaServer("aegis.calcite.serverClasspathFile", List.of(),
                "org.apache.calcite.avatica.standalone.StandaloneServer",
                List.of("-u", "jdbc:calcite:model=" + model.toString().replace('\\', '/'), "-p", Integer.toString(port), "-s", "JSON"),
                work.resolve("avatica-server.log"));
        avatica.awaitReady(() -> orderCount() == 3, Duration.ofSeconds(120), "Avatica server over Calcite");
    }

    @AfterAll
    static void stopCalcite() throws Exception {
        if (avatica != null) {
            avatica.close();
        }
    }

    /** Asks Calcite directly, as any Avatica client would, outside Aegis. */
    static long orderCount() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:avatica:remote:url=http://127.0.0.1:" + port);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM SALES.ORDERS")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @ParameterizedTest(name = "calcite through {0}")
    @ValueSource(strings = {FrontDoor.OWN_HOST, FrontDoor.KNOX})
    void governsRealCalciteThroughAvatica(String door) throws Exception {
        AdopterScenario scenario = AdopterScenario.of("calcite",
                AdopterScenario.canonical("adapters", "calcite", "calcite.yaml"),
                yaml -> yaml.replace("url=http://avatica:8765", "url=http://127.0.0.1:" + port)
                        .replace("allowCidrs: [10.0.0.0/8]", "allowCidrs: [127.0.0.0/8]"),
                Map.of(Persona.READER, List.of("calcite-users"),
                        Persona.OPERATOR, List.of("calcite-operators"),
                        Persona.APPROVER, List.of("calcite-approvers")),
                Map.of("CALCITE_USER", "aegis", "CALCITE_PASSWORD", "aegis-validation-db-password"))
                // An operator adds the jdbc source and the Avatica client driver to Knox ext/.
                .withKnoxPlugin(List.of(Path.of(System.getProperty("aegis.jdbc.pluginJar")),
                        Path.of(System.getProperty("aegis.calcite.driverJar"))), List.of());

        // Both hosts now resolve jdbc userEnv/passwordEnv from the scenario BuildContext. Knox receives
        // those values in its process environment; own-host receives the same lookup directly.
        try (FrontDoor d = FrontDoor.open(door, scenario, dir)) {
            d.note("real Calcite 1.42.0 behind Avatica 1.28.0 on 127.0.0.1:" + port + "; SALES.ORDERS has " + orderCount() + " rows");
            GovernedFlow.read(d, "jdbc_list_tables", Map.of("schema", "SALES"), "ORDERS");
            GovernedFlow.read(d, "jdbc_run_query", Map.of("sql", "SELECT ITEM, PRICE FROM SALES.ORDERS WHERE PRICE > 5 ORDER BY ID"),
                    "gadget");
            GovernedFlow.refused(d, "write smuggled into the read-only SQL tool", Persona.OPERATOR, "jdbc_run_query",
                    Map.of("sql", "DELETE FROM SALES.ORDERS"), "INVALID_INPUT",
                    () -> assertEquals(3, orderCount(), "Calcite still has every row"));
            GovernedFlow.refused(d, "write hidden after a comment", Persona.OPERATOR, "jdbc_run_query",
                    Map.of("sql", "/* report */ DROP TABLE SALES.ORDERS"), "INVALID_INPUT",
                    () -> assertEquals(3, orderCount(), "Calcite still has every row"));
            d.note("Calcite reports SALES.ORDERS with " + orderCount() + " rows after both refused writes");
        }
    }
}
