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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.engine.catalog.SignedServerCatalog;
import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.validation.frontdoor.AdopterScenario;
import io.github.vaquarkhan.aegis.validation.frontdoor.FrontDoor;
import io.github.vaquarkhan.aegis.validation.frontdoor.GovernedFlow;
import io.github.vaquarkhan.aegis.validation.frontdoor.Persona;
import io.github.vaquarkhan.aegis.validation.frontdoor.SignedServers;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.deploy.master.Master;
import org.apache.spark.rpc.RpcEnv;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import scala.Tuple3;

/**
 * examples/adopters/spark: the canonical {@code adopters/configurations/spark/spark.yaml} against two REAL
 * Apache Spark 4.0.1 services, through both front doors. Its {@code spark-history} source reads the
 * monitoring REST API of a running Spark application (a local SparkContext with its UI). Its
 * {@code spark-master} source drives a standalone Master's REST submission server: a driver submitted to
 * a master with no worker waits in state SUBMITTED, and the governed {@code spark_kill_submission} must
 * move it to KILLED, which the test reads back from the master directly.
 */
class SparkFrontDoorsTest {

    private static JavaSparkContext app;
    private static RpcEnv masterEnv;
    private static int uiPort;
    private static int restPort;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    Path dir;

    @BeforeAll
    static void startSpark() throws Exception {
        restPort = freePort();
        SparkConf masterConf = new SparkConf()
                .set("spark.master.rest.enabled", "true")
                .set("spark.master.rest.host", "127.0.0.1")
                .set("spark.master.rest.port", Integer.toString(restPort))
                .set("spark.master.ui.port", Integer.toString(freePort()));
        Tuple3<RpcEnv, Object, scala.Option<Object>> master =
                Master.startRpcEnvAndEndpoint("127.0.0.1", freePort(), freePort(), masterConf);
        masterEnv = master._1();

        SparkConf conf = new SparkConf().setMaster("local[1]").setAppName("aegis-spark-example")
                .set("spark.ui.enabled", "true").set("spark.ui.port", "0").set("spark.driver.host", "127.0.0.1")
                .set("spark.driver.bindAddress", "127.0.0.1").set("spark.ui.showConsoleProgress", "false");
        app = new JavaSparkContext(conf);
        assertEquals(5, app.parallelize(List.of(1, 2, 3, 4, 5)).count());
        uiPort = URI.create(app.sc().uiWebUrl().get()).getPort();
        io.github.vaquarkhan.aegis.validation.frontdoor.RunRecord.engineInProcess(
                "Apache Spark 4.0.1 runs inside the test JVM as two real services: a standalone `Master` "
                        + "(`Master.startRpcEnvAndEndpoint`, what `sbin/start-master.sh` runs) with its REST submission "
                        + "server on `127.0.0.1:" + restPort + "` (`spark.master.rest.enabled=true`), and a `local[1]` "
                        + "application whose web UI REST API is on `127.0.0.1:" + uiPort + "`. To use your own, start "
                        + "`sbin/start-master.sh` with `spark.master.rest.enabled=true` and point the YAML at the Master's "
                        + "REST port and a History Server or driver UI.");
    }

    @AfterAll
    static void stopSpark() throws Exception {
        if (app != null) {
            app.close();
        }
        if (masterEnv != null) {
            masterEnv.shutdown();
            masterEnv.awaitTermination();
        }
    }

    @ParameterizedTest(name = "spark through {0}")
    @ValueSource(strings = {FrontDoor.OWN_HOST, FrontDoor.KNOX})
    void governsARealSparkApplicationAndStandaloneMaster(String door) throws Exception {
        AdopterScenario scenario = AdopterScenario.of("spark", AdopterScenario.canonical("spark", "spark.yaml"),
                yaml -> yaml.replace("http://spark-history:18080", "http://127.0.0.1:" + uiPort)
                        .replace("http://spark-master:6066", "http://127.0.0.1:" + restPort)
                        .replace("allowCidrs: [10.0.0.0/8]", "allowCidrs: [127.0.0.0/8]"),
                Map.of(Persona.READER, List.of("spark-users"),
                        Persona.OPERATOR, List.of("spark-operators"),
                        Persona.APPROVER, List.of("spark-approvers")),
                Map.of());
        try (FrontDoor d = FrontDoor.open(door, scenario, dir)) {
            String driver = submitWaitingDriver();
            d.note("real Spark application " + app.sc().applicationId() + " (UI port " + uiPort + "); standalone Master REST "
                    + restPort + " accepted driver " + driver + " in state " + driverState(driver));

            GovernedFlow.read(d, "spark_list_applications", Map.of("status", "running"), "aegis-spark-example");
            GovernedFlow.read(d, "spark_submission_status", Map.of("submissionId", driver), "SUBMITTED");
            GovernedFlow.destructive(d, "spark_kill_submission", Map.of("submissionId", driver),
                    () -> assertEquals("SUBMITTED", driverState(driver), "denied and previewed calls did not kill " + driver),
                    () -> {
                        assertEquals("KILLED", driverState(driver));
                        d.note("the Spark Master reports driver " + driver + " " + driverState(driver));
                    });
        }
    }

    private static AdopterScenario scenario() throws Exception {
        return AdopterScenario.of("spark", AdopterScenario.canonical("spark", "spark.yaml"),
                yaml -> yaml.replace("http://spark-history:18080", "http://127.0.0.1:" + uiPort)
                        .replace("http://spark-master:6066", "http://127.0.0.1:" + restPort)
                        .replace("allowCidrs: [10.0.0.0/8]", "allowCidrs: [127.0.0.0/8]"),
                Map.of(Persona.READER, List.of("spark-users"),
                        Persona.OPERATOR, List.of("spark-operators"),
                        Persona.APPROVER, List.of("spark-approvers")),
                Map.of());
    }

    /**
     * Both real Spark services under a signed server catalog, then a break-glass order that revokes the
     * standalone Master during an incident: the Master's tools disappear for everyone (the driver it holds
     * stays untouched), while the reviewed application-UI tools keep governing real reads.
     */
    @ParameterizedTest(name = "spark signed server catalog through {0}")
    @ValueSource(strings = {FrontDoor.OWN_HOST, FrontDoor.KNOX})
    void aSignedServerCatalogGovernsBothSparkServicesAndBreakGlassRevokesTheMaster(String door) throws Exception {
        String driver = submitWaitingDriver();
        SignedServers.Signed signed = SignedServers.sign(scenario(), "spark-server-catalog", dir, Map.of());
        assertTrue(signed.sources().containsValue("spark-history") && signed.sources().containsValue("spark-master"),
                signed.sources().toString());
        try (FrontDoor d = FrontDoor.open(door, signed.scenario(), dir.resolve("signed"))) {
            d.note("server catalog signed for " + signed.pins().size() + " Spark tools from two sources; driver " + driver);
            SignedServers.assertOnlySignedToolsAreActive(d, signed);
            GovernedFlow.read(d, "spark_list_applications", Map.of("status", "running"), "aegis-spark-example");
            GovernedFlow.read(d, "spark_submission_status", Map.of("submissionId", driver), "SUBMITTED");
        }

        SignedServers.Signed contained = SignedServers.sign(scenario(), "spark-break-glass", dir,
                Map.of("spark-master", SignedServerCatalog.State.REVOKED));
        assertFalse(contained.sources().containsValue("spark-master"));
        try (FrontDoor d = FrontDoor.open(door, contained.scenario(), dir.resolve("contained"))) {
            d.note("break-glass order revokes spark-master; spark-history stays ACTIVE");
            SignedServers.assertOnlySignedToolsAreActive(d, contained);
            GovernedFlow.read(d, "spark_list_applications", Map.of("status", "running"), "aegis-spark-example");
            SignedServers.assertNotExposed(d, "spark_submission_status", Map.of("submissionId", driver));
            SignedServers.assertNotExposed(d, "spark_kill_submission", Map.of("submissionId", driver));
            assertEquals("SUBMITTED", driverState(driver), "the revoked Master was never contacted through Aegis");
        }
    }

    /** Setup outside Aegis, as spark-submit --deploy-mode cluster would do: the driver waits for a worker. */
    private static String submitWaitingDriver() throws Exception {
        String body = Json.write(Map.of(
                "action", "CreateSubmissionRequest",
                "clientSparkVersion", "4.0.1",
                "appResource", "file:/opt/jobs/aegis-example-job.jar",
                "mainClass", "org.example.NightlyAggregation",
                "appArgs", List.of(),
                "environmentVariables", Map.of(),
                "sparkProperties", Map.of(
                        "spark.app.name", "nightly-aggregation",
                        "spark.master", "spark://127.0.0.1:7077",
                        "spark.submit.deployMode", "cluster",
                        "spark.driver.supervise", "false")));
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + restPort + "/v1/submissions/create"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        Map<String, Object> m = Json.parseObject(r.body());
        if (!Boolean.TRUE.equals(m.get("success"))) {
            throw new IllegalStateException("Spark Master refused the submission: " + r.body());
        }
        return (String) m.get("submissionId");
    }

    private static String driverState(String driver) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + restPort + "/v1/submissions/status/" + driver)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return String.valueOf(Json.parseObject(r.body()).get("driverState"));
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
