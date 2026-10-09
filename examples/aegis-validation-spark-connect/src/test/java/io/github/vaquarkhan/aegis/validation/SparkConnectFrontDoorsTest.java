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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.validation.frontdoor.AdopterScenario;
import io.github.vaquarkhan.aegis.validation.frontdoor.FrontDoor;
import io.github.vaquarkhan.aegis.validation.frontdoor.GovernedFlow;
import io.github.vaquarkhan.aegis.validation.frontdoor.Persona;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * examples/adopters/spark-connect: the canonical {@code adopters/configurations/spark-connect/spark-connect.yaml}
 * governing a REAL Apache Spark 4.0.1 Spark Connect server in its own JVM, through both front doors. The
 * {@code spark-connect} source is a plugin, so with Knox it is installed in Knox's {@code ext/} as an
 * operator would: the plugin jar and its runtime dependencies, Knox keeping the libraries it already ships.
 */
class SparkConnectFrontDoorsTest {

    private static SparkConnectServer spark;

    @TempDir
    Path dir;

    @BeforeAll
    static void startSpark() throws Exception {
        // Under target/, not a JUnit temp dir: the server log stays available after the run, and Windows
        // does not have to delete a file the just-stopped server JVM may still hold.
        Path work = Path.of("target", "spark-connect-server");
        Files.createDirectories(work);
        spark = new SparkConnectServer(work.toAbsolutePath());
    }

    @AfterAll
    static void stopSpark() throws Exception {
        if (spark != null) {
            spark.close();
        }
    }

    @ParameterizedTest(name = "spark-connect through {0}")
    @ValueSource(strings = {FrontDoor.OWN_HOST, FrontDoor.KNOX})
    void governsARealSparkConnectServer(String door) throws Exception {
        String view = "scratch_" + door.replace("-", "");
        spark.sql("CREATE OR REPLACE GLOBAL TEMP VIEW " + view + " AS SELECT 1 AS x");

        AdopterScenario scenario = AdopterScenario.of("spark-connect", AdopterScenario.canonical("spark-connect", "spark-connect.yaml"),
                yaml -> yaml.replace("host: spark-connect", "host: 127.0.0.1")
                        .replace("port: 15002", "port: " + spark.port())
                        .replace("allowHosts: [spark-connect]", "allowHosts: [127.0.0.1]")
                        .replace("allowCidrs: [10.0.0.0/8]", "allowCidrs: [127.0.0.0/8]"),
                Map.of(Persona.READER, List.of("spark-users"),
                        Persona.OPERATOR, List.of("spark-operators"),
                        Persona.APPROVER, List.of("spark-approvers")),
                Map.of()).withKnoxPlugin(pluginJars(), SparkConnectServer.JAVA17_OPENS);

        try (FrontDoor d = FrontDoor.open(door, scenario, dir)) {
            d.note("real Spark Connect 4.0.1 server on 127.0.0.1:" + spark.port() + "; global temp views: orders, " + view);
            GovernedFlow.read(d, "spark_list_tables", Map.of("database", "global_temp"), "orders");
            GovernedFlow.read(d, "spark_preview_table", Map.of("table", "global_temp.orders", "limit", 2L), "widget");
            GovernedFlow.destructive(d, "spark_drop_table", Map.of("table", "global_temp." + view),
                    () -> assertTrue(spark.globalTempViewExists(view), "denied and previewed calls did not drop " + view),
                    () -> {
                        assertFalse(spark.globalTempViewExists(view), view + " is really gone");
                        d.note("Spark reports global_temp." + view + " no longer exists");
                    });
        }
    }

    /** The plugin an operator installs in Knox ext/: this module's jar and its runtime classpath. */
    private static List<Path> pluginJars() throws Exception {
        List<Path> jars = new ArrayList<>();
        jars.add(Path.of(System.getProperty("aegis.sparkConnect.pluginJar")));
        String cp = Files.readString(Path.of(System.getProperty("aegis.sparkConnect.pluginClasspathFile")), StandardCharsets.UTF_8).trim();
        for (String entry : cp.split(java.io.File.pathSeparator)) {
            if (!entry.isBlank()) {
                jars.add(Path.of(entry));
            }
        }
        return jars;
    }
}
