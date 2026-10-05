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
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code adopters/configurations/adapters/hive/hive.yaml} against Apache Hive 4.0.1 HiveServer2
 * ({@code apache/hive:4.0.1} Docker) through both front doors. SQL metadata and read-only query
 * tools run over the JDBC source; smuggled writes are refused before HS2 sees them.
 */
class HiveFrontDoorsTest {

    private static final String USER = "hive";
    private static final String PASS = "";
    private static final String TABLE = "aegis_keep_demo";

    private static final String CONTAINER = "aegis-val-hive";

    private static DockerContainer hive;
    private static int port;
    private static Path hiveClientLib;
    private static ClassLoader hiveClassLoader;

    @TempDir
    Path dir;

    @BeforeAll
    static void startHiveServer2() throws Exception {
        DockerContainer.requireDocker();
        port = JavaServer.freePort();
        Path log = Path.of("target", "hive-server", "docker.log").toAbsolutePath();
        hive = DockerContainer.start(CONTAINER, "apache/hive:4.0.1",
                List.of("-p", "127.0.0.1:" + port + ":10000",
                        "-e", "SERVICE_NAME=hiveserver2"),
                log);
        hiveClientLib = extractHiveClientLib();
        hiveClassLoader = jarClassLoader(hiveClientLib);
        Thread.currentThread().setContextClassLoader(hiveClassLoader);
        hive.awaitReady(HiveFrontDoorsTest::hs2AcceptsJdbc, Duration.ofMinutes(6), "HiveServer2 4.0.1 JDBC");
        execSql("CREATE TABLE IF NOT EXISTS " + TABLE + " (id INT, label STRING)");
        execSql("INSERT INTO TABLE " + TABLE + " VALUES (1, 'keep-row')");
        assertEquals(1L, rowCount(), "seed row");
    }

    @AfterAll
    static void stopHive() throws Exception {
        if (hive != null) {
            hive.close();
        }
    }

    static boolean hs2AcceptsJdbc() {
        try (Connection c = open()) {
            return c.isValid(5);
        } catch (Exception e) {
            return false;
        }
    }

    static long rowCount() throws Exception {
        try (Connection c = open();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + TABLE)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    static void execSql(String sql) throws Exception {
        try (Connection c = open(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    static Connection open() throws Exception {
        Class.forName("org.apache.hive.jdbc.HiveDriver", true, hiveClassLoader);
        Thread.currentThread().setContextClassLoader(hiveClassLoader);
        return DriverManager.getConnection(jdbcUrl(), USER, PASS);
    }

    static Path extractHiveClientLib() throws Exception {
        // hive-jdbc on Maven Central is a thin jar; HS2's own image carries the matching client stack.
        Path dest = Path.of("target", "hive-hs2-lib").toAbsolutePath();
        if (Files.isDirectory(dest)) {
            try (Stream<Path> walk = Files.walk(dest)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                        // best-effort cleanup of a prior run
                    }
                });
            }
        }
        Files.createDirectories(dest);
        dockerCp(CONTAINER + ":/opt/hive/lib/.", dest);
        return dest;
    }

    static void dockerCp(String from, Path to) throws Exception {
        Process p = new ProcessBuilder("docker", "cp", from, to.toString())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(5, TimeUnit.MINUTES) || p.exitValue() != 0) {
            throw new IllegalStateException("docker cp failed: docker cp " + from + " " + to + "\n" + out);
        }
    }

    static ClassLoader jarClassLoader(Path libDir) throws Exception {
        List<URL> urls = new ArrayList<>();
        try (Stream<Path> jars = Files.list(libDir)) {
            jars.filter(p -> p.toString().endsWith(".jar")).forEach(p -> {
                try {
                    urls.add(p.toUri().toURL());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        }
        return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
    }

    static String jdbcUrl() {
        return "jdbc:hive2://127.0.0.1:" + port + "/default";
    }

    static List<Path> knoxPluginJars() throws Exception {
        List<Path> jars = new ArrayList<>(AdopterScenario.plugin("aegis.jdbc.pluginJar", "aegis.jdbc.pluginClasspathFile"));
        try (Stream<Path> stream = Files.list(hiveClientLib)) {
            stream.filter(p -> p.toString().endsWith(".jar")).forEach(jars::add);
        }
        return jars;
    }

    @ParameterizedTest(name = "hive through {0}")
    @ValueSource(strings = {FrontDoor.OWN_HOST, FrontDoor.KNOX})
    void governsRealHiveServer2(String door) throws Exception {
        AdopterScenario scenario = AdopterScenario.of("hive",
                AdopterScenario.canonical("adapters", "hive", "hive.yaml"),
                yaml -> yaml.replace("jdbc:hive2://hiveserver2:10000/default", jdbcUrl())
                        .replace("allowCidrs: [10.0.0.0/8]", "allowCidrs: [127.0.0.0/8]"),
                Map.of(Persona.READER, List.of("hive-users"),
                        Persona.OPERATOR, List.of("hive-operators"),
                        Persona.APPROVER, List.of("hive-approvers")),
                Map.of("HIVE_USER", USER, "HIVE_PASSWORD", PASS))
                .withKnoxPlugin(knoxPluginJars(), List.of());

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(hiveClassLoader);
        try (FrontDoor d = FrontDoor.open(door, scenario, dir)) {
            d.note("Hive 4.0.1 HS2 on " + jdbcUrl() + "; " + TABLE + " has " + rowCount() + " row(s)");
            GovernedFlow.read(d, "jdbc_list_tables", Map.of("schema", "default"), TABLE);
            GovernedFlow.read(d, "jdbc_run_query",
                    Map.of("sql", "SELECT label FROM " + TABLE + " WHERE id = 1"), "keep-row");
            GovernedFlow.refused(d, "write smuggled into the read-only SQL tool", Persona.OPERATOR, "jdbc_run_query",
                    Map.of("sql", "DROP TABLE " + TABLE), "INVALID_INPUT",
                    () -> assertEquals(1L, rowCount(), "Hive still has the seed row"));
            d.note(TABLE + " still has " + rowCount() + " row after refused write");
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }
}
