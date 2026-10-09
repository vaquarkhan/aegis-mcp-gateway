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
import io.github.vaquarkhan.aegis.validation.frontdoor.FrontDoor;
import io.github.vaquarkhan.aegis.validation.frontdoor.GovernedFlow;
import io.github.vaquarkhan.aegis.validation.frontdoor.JavaServer;
import io.github.vaquarkhan.aegis.validation.frontdoor.Persona;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The Spark + Iceberg tutorial ({@code docs/TUTORIAL-SPARK-ICEBERG.md}), run for real through both front
 * doors: an Apache Iceberg 1.10.1 REST catalog writing real metadata and Parquet files to a local
 * warehouse, an Apache Spark 4.0.1 Spark Connect server with Iceberg's Spark runtime and a {@code lake}
 * catalog pointing at that REST catalog, and the tutorial's own governance file
 * {@code adopters/tutorials/spark-iceberg/aegis-spark-iceberg.yaml} with only the two ports substituted.
 * Every governed change is checked by reading the table back through Spark, outside Aegis.
 */
class SparkIcebergFrontDoorsTest {

    private static JavaServer catalog;
    private static SparkConnectServer spark;
    private static int catalogPort;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    Path dir;

    @BeforeAll
    static void startCatalogAndSpark() throws Exception {
        Path work = Path.of("target", "spark-iceberg").toAbsolutePath();
        Files.createDirectories(work);
        Path warehouse = Files.createDirectories(work.resolve("warehouse-" + System.nanoTime()));
        catalogPort = JavaServer.freePort();
        // A real file-backed catalog: metadata JSON and Parquet data files land in the warehouse directory.
        Map<String, String> env = Map.of(
                "CATALOG_REST_PORT", Integer.toString(catalogPort),
                "CATALOG_WAREHOUSE", warehouse.toUri().toString(),
                "CATALOG_IO__IMPL", "org.apache.iceberg.hadoop.HadoopFileIO",
                "CATALOG_CATALOG__IMPL", "org.apache.iceberg.jdbc.JdbcCatalog",
                "CATALOG_URI", "jdbc:sqlite:file:" + work.resolve("catalog-" + System.nanoTime() + ".db").toString().replace('\\', '/'),
                "CATALOG_JDBC_USER", "aegis",
                "CATALOG_JDBC_PASSWORD", "aegis");
        // On Windows, Hadoop's local file system needs winutils.exe to set POSIX permissions; Apache Hadoop
        // does not ship it. Both JVMs then use the tutorial's WindowsLocalFileSystem (Hadoop's own local
        // file system without the permission calls). On Linux and macOS nothing is added.
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
        Path localFs = Path.of(System.getProperty("aegis.windowsLocalFs.jar", "")).toAbsolutePath();
        String catalogClasspathProperty = "aegis.iceberg.serverClasspathFile";
        List<String> sparkConf = new ArrayList<>(List.of(
                "-Dspark.sql.extensions=org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions",
                "-Dspark.sql.catalog.lake=org.apache.iceberg.spark.SparkCatalog",
                "-Dspark.sql.catalog.lake.type=rest",
                "-Dspark.sql.catalog.lake.uri=http://127.0.0.1:" + catalogPort));
        List<Path> sparkExtra = new ArrayList<>();
        if (windows) {
            if (!Files.isRegularFile(localFs)) {
                throw new IllegalStateException("build examples/aegis-windows-localfs with -Preal-engines: " + localFs);
            }
            Path conf = Files.createDirectories(work.resolve("catalog-conf"));
            Files.writeString(conf.resolve("core-site.xml"), "<?xml version=\"1.0\"?>\n<configuration>\n"
                    + "  <property><name>fs.file.impl</name>"
                    + "<value>io.github.vaquarkhan.aegis.examples.localfs.WindowsLocalFileSystem</value></property>\n"
                    + "</configuration>\n");
            Path cp = work.resolve("catalog.classpath");
            Files.writeString(cp, Files.readString(Path.of(System.getProperty(catalogClasspathProperty))).trim()
                    + java.io.File.pathSeparator + conf + java.io.File.pathSeparator + localFs);
            catalogClasspathProperty = "aegis.sparkIceberg.catalogClasspathFile";
            System.setProperty(catalogClasspathProperty, cp.toString());
            sparkConf.add("-Dspark.hadoop.fs.file.impl=io.github.vaquarkhan.aegis.examples.localfs.WindowsLocalFileSystem");
            sparkExtra.add(localFs);
        }
        catalog = new JavaServer(catalogClasspathProperty, List.of("-Xmx1G"),
                "org.apache.iceberg.rest.RESTCatalogServer", List.of(), env, work.resolve("iceberg-rest.log"));
        catalog.awaitReady(() -> get("/v1/config") == 200, Duration.ofSeconds(120), "Iceberg REST catalog");

        spark = new SparkConnectServer(work.resolve("spark"), sparkConf, sparkExtra);
        // The tutorial's tiny dataset, created as a data engineer would, outside Aegis.
        spark.sql("CREATE NAMESPACE IF NOT EXISTS lake.sales");
        spark.sql("CREATE NAMESPACE IF NOT EXISTS lake.finance");
        spark.sql("CREATE TABLE IF NOT EXISTS lake.sales.orders (id BIGINT, item STRING, price DOUBLE) USING iceberg");
        spark.sql("CREATE TABLE IF NOT EXISTS lake.finance.ledger (id BIGINT, amount DOUBLE) USING iceberg");
        spark.sql("INSERT INTO lake.finance.ledger VALUES (1, 100.0)");
    }

    @AfterAll
    static void stop() throws Exception {
        if (spark != null) {
            spark.close();
        }
        if (catalog != null) {
            catalog.close();
        }
    }

    @ParameterizedTest(name = "spark + iceberg through {0}")
    @ValueSource(strings = {FrontDoor.OWN_HOST, FrontDoor.KNOX})
    void governsSparkOnIcebergEndToEnd(String door) throws Exception {
        // Same three rows before each front door, so both doors run the identical scenario.
        spark.sql("DELETE FROM lake.sales.orders");
        spark.sql("INSERT INTO lake.sales.orders VALUES (1, 'widget', 9.5), (2, 'gadget', 12.0), (3, 'gizmo', 3.25)");

        Path config = Path.of("..", "..", "adopters", "tutorials", "spark-iceberg", "aegis-spark-iceberg.yaml");
        AdopterScenario scenario = AdopterScenario.of("spark-iceberg", config,
                yaml -> yaml.replace("baseUrl: http://127.0.0.1:8181", "baseUrl: http://127.0.0.1:" + catalogPort)
                        .replace("port: 15002", "port: " + spark.port()),
                Map.of(Persona.READER, List.of("lake-readers"),
                        Persona.OPERATOR, List.of("lake-engineers"),
                        Persona.APPROVER, List.of("lake-approvers")),
                Map.of()).withKnoxPlugin(pluginJars(), SparkConnectServer.JAVA17_OPENS);

        try (FrontDoor d = FrontDoor.open(door, scenario, dir)) {
            d.note("real Iceberg 1.10.1 REST catalog on 127.0.0.1:" + catalogPort
                    + " (file warehouse); real Spark 4.0.1 Spark Connect on 127.0.0.1:" + spark.port()
                    + " with catalog lake; lake.sales.orders has 3 rows, lake.finance.ledger has 1");

            // Read the catalog and the data through one gateway.
            GovernedFlow.read(d, "iceberg_list_tables", Map.of("namespace", "sales"), "orders");
            GovernedFlow.read(d, "spark_preview_table", Map.of("table", "lake.sales.orders", "limit", 3L), "widget");

            // Scope: a reader does not see the finance namespace at all.
            GovernedFlow.refused(d, "reader outside their resource scope", Persona.READER, "iceberg_list_tables",
                    Map.of("namespace", "finance"), "SCOPE_DENIED", () -> { });
            GovernedFlow.refused(d, "reader outside their resource scope", Persona.READER, "spark_preview_table",
                    Map.of("table", "lake.finance.ledger"), "SCOPE_DENIED", () -> { });

            // Policy: finance tables are never dropped, before any approval is even considered.
            GovernedFlow.refused(d, "drop a finance table (DENY rule)", Persona.OPERATOR, "iceberg_drop_table",
                    Map.of("namespace", "finance", "table", "ledger"), "POLICY_DENIED",
                    () -> assertEquals(200, get("/v1/namespaces/finance/tables/ledger"), "lake.finance.ledger untouched"));

            // A data change: reader refused, then dry run (EXPLAIN), independent approval, execute, replay refused.
            GovernedFlow.destructive(d, "spark_sql", Map.of("query", "DELETE FROM lake.sales.orders WHERE id = 3"),
                    () -> assertEquals(3L, count("lake.sales.orders"), "denied and previewed calls changed no data"),
                    () -> {
                        assertEquals(2L, count("lake.sales.orders"), "the approved DELETE really removed one row");
                        assertTrue(spark.sql("SELECT item FROM lake.sales.orders").stream().noneMatch(r -> r.contains("gizmo")));
                        d.note("Spark reads 2 rows back from lake.sales.orders; gizmo is gone");
                    });

            // The change is an Iceberg commit: the catalog now lists the delete snapshot.
            GovernedFlow.read(d, "iceberg_list_snapshots", Map.of("namespace", "sales", "table", "orders"), "snapshot-id");
        }
    }

    private static long count(String table) {
        String row = spark.sql("SELECT count(*) FROM " + table).get(0);
        return Long.parseLong(row.replaceAll("[^0-9]", ""));
    }

    private static int get(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + catalogPort + path)).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    /** The spark-connect plugin as an operator installs it in Knox ext/. */
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
