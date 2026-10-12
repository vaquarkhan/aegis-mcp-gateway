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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.validation.frontdoor.AdopterScenario;
import io.github.vaquarkhan.aegis.validation.frontdoor.DockerContainer;
import io.github.vaquarkhan.aegis.validation.frontdoor.FrontDoor;
import io.github.vaquarkhan.aegis.validation.frontdoor.GovernedFlow;
import io.github.vaquarkhan.aegis.validation.frontdoor.JavaServer;
import io.github.vaquarkhan.aegis.validation.frontdoor.Persona;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * examples/adopters/hive: the canonical {@code adopters/configurations/adapters/hive/hive.yaml}
 * against a real Apache Hive 4.0.1 HiveServer2 (official {@code apache/hive} image) through both
 * front doors. The profile is JDBC and read-only. The governed story is a metadata read and a
 * SHOW that HiveServer2 answers, plus a write smuggled into the read tool that Aegis refuses.
 */
class HiveFrontDoorsTest {

    private static final String USER = "hive";
    private static final String PASSWORD = "hive";

    private static DockerContainer hive;
    private static int port;

    @TempDir
    Path dir;

    @BeforeAll
    static void startHive() throws Exception {
        DockerContainer.requireDocker();
        port = JavaServer.freePort();
        Path log = Path.of("target", "hive-server", "docker.log").toAbsolutePath();
        hive = DockerContainer.start("aegis-val-hive", "apache/hive:4.0.1",
                List.of("-p", "127.0.0.1:" + port + ":10000",
                        "-e", "SERVICE_NAME=hiveserver2"),
                log);
        Class.forName("org.apache.hive.jdbc.HiveDriver");
        hive.awaitReady(() -> "default".equals(currentDatabase()), Duration.ofMinutes(4), "HiveServer2 4.0.1");
        try (Connection c = open(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS aegis_orders (id int, item string) STORED AS TEXTFILE");
        }
    }

    @AfterAll
    static void stopHive() throws Exception {
        if (hive != null) {
            hive.close();
        }
    }

    /** Asks HiveServer2 directly, outside Aegis. */
    static String currentDatabase() throws Exception {
        try (Connection c = open(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT current_database()")) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    static boolean tableExists() throws Exception {
        try (Connection c = open(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SHOW TABLES")) {
            while (rs.next()) {
                if ("aegis_orders".equalsIgnoreCase(rs.getString(1))) {
                    return true;
                }
            }
            return false;
        }
    }

    private static Connection open() throws Exception {
        Properties p = new Properties();
        p.setProperty("user", USER);
        p.setProperty("password", PASSWORD);
        return DriverManager.getConnection("jdbc:hive2://127.0.0.1:" + port + "/default", p);
    }

    @ParameterizedTest(name = "hive through {0}")
    @ValueSource(strings = {FrontDoor.OWN_HOST, FrontDoor.KNOX})
    void governsRealHiveServer(String door) throws Exception {
        AdopterScenario scenario = AdopterScenario.of("hive",
                AdopterScenario.canonical("adapters", "hive", "hive.yaml"),
                yaml -> yaml.replace("jdbc:hive2://hiveserver2:10000/default", "jdbc:hive2://127.0.0.1:" + port + "/default")
                        .replace("allowCidrs: [10.0.0.0/8]", "allowCidrs: [127.0.0.0/8]"),
                Map.of(Persona.READER, List.of("hive-users"),
                        Persona.OPERATOR, List.of("hive-operators"),
                        Persona.APPROVER, List.of("hive-approvers")),
                Map.of("HIVE_USER", USER, "HIVE_PASSWORD", PASSWORD))
                .withKnoxPlugin(List.of(Path.of(System.getProperty("aegis.jdbc.pluginJar")),
                        Path.of(System.getProperty("aegis.hive.driverJar"))), List.of());

        try (FrontDoor d = FrontDoor.open(door, scenario, dir)) {
            d.note("real HiveServer2 4.0.1 on 127.0.0.1:" + port + "; current database " + currentDatabase());
            GovernedFlow.read(d, "jdbc_list_schemas", Map.of(), "default");
            GovernedFlow.read(d, "jdbc_list_tables", Map.of("schema", "default"), "aegis_orders");
            GovernedFlow.read(d, "jdbc_run_query", Map.of("sql", "SHOW TABLES"), "aegis_orders");
            GovernedFlow.refused(d, "write smuggled into the read-only SQL tool", Persona.OPERATOR, "jdbc_run_query",
                    Map.of("sql", "DROP TABLE aegis_orders"), "INVALID_INPUT",
                    () -> assertTrue(tableExists(), "Hive still has aegis_orders"));
            GovernedFlow.refused(d, "write hidden after a comment", Persona.OPERATOR, "jdbc_run_query",
                    Map.of("sql", "/* report */ DROP TABLE aegis_orders"), "INVALID_INPUT",
                    () -> assertEquals("default", currentDatabase()));
        }
    }
}
