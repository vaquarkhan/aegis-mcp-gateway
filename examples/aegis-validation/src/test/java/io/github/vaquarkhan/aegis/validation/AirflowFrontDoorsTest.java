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
import io.github.vaquarkhan.aegis.validation.frontdoor.DockerContainer;
import io.github.vaquarkhan.aegis.validation.frontdoor.FrontDoor;
import io.github.vaquarkhan.aegis.validation.frontdoor.GovernedFlow;
import io.github.vaquarkhan.aegis.validation.frontdoor.JavaServer;
import io.github.vaquarkhan.aegis.validation.frontdoor.Persona;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code adopters/configurations/adapters/airflow/airflow.yaml} against a real Apache Airflow 2.9.3
 * standalone container ({@code /api/v1}) through both front doors.
 */
class AirflowFrontDoorsTest {

    private static final String USER = "admin";
    private static final String PASS = "admin";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static DockerContainer airflow;
    private static int port;
    private static String keepDag;
    private static String ownHostVictim;
    private static String knoxVictim;

    @TempDir
    Path dir;

    @BeforeAll
    static void startAirflow() throws Exception {
        DockerContainer.requireDocker();
        port = JavaServer.freePort();
        Path log = Path.of("target", "airflow-server", "docker.log").toAbsolutePath();
        // Examples are slow and version-specific; seed three disposable DAGs via the API filesystem instead.
        airflow = DockerContainer.start("aegis-val-airflow", "apache/airflow:2.9.3-python3.11",
                List.of("-p", "127.0.0.1:" + port + ":8080",
                        "-e", "AIRFLOW__CORE__LOAD_EXAMPLES=False",
                        "-e", "AIRFLOW__API__AUTH_BACKENDS=airflow.api.auth.backend.basic_auth",
                        "-e", "AIRFLOW__SCHEDULER__DAG_DIR_LIST_INTERVAL=5",
                        "-e", "_AIRFLOW_DB_MIGRATE=true",
                        "-e", "_AIRFLOW_WWW_USER_CREATE=true",
                        "-e", "_AIRFLOW_WWW_USER_USERNAME=" + USER,
                        "-e", "_AIRFLOW_WWW_USER_PASSWORD=" + PASS),
                List.of("standalone"),
                log);
        airflow.awaitReady(() -> unauthHealth().contains("healthy"),
                Duration.ofMinutes(8), "Airflow 2.9.3 /api/v1 health");
        resetAdminPassword();
        airflow.awaitReady(() -> listDags().startsWith("200:"), Duration.ofMinutes(2), "Airflow basic auth");
        keepDag = seedDisposableDag("aegis_keep");
        ownHostVictim = seedDisposableDag("aegis_ownhost");
        knoxVictim = seedDisposableDag("aegis_knox");
    }

    @AfterAll
    static void stopAirflow() throws Exception {
        if (airflow != null) {
            airflow.close();
        }
    }

    @ParameterizedTest(name = "airflow through {0}")
    @ValueSource(strings = {FrontDoor.OWN_HOST, FrontDoor.KNOX})
    void governsARealAirflow(String door) throws Exception {
        String victim = FrontDoor.OWN_HOST.equals(door) ? ownHostVictim : knoxVictim;
        assertTrue(listDags().contains(victim), victim + " present before run");

        String url = "http://127.0.0.1:" + port + "/api/v1";
        AdopterScenario scenario = AdopterScenario.of("airflow",
                AdopterScenario.canonical("adapters", "airflow", "airflow.yaml"),
                yaml -> yaml.replace("http://airflow-webserver:8080/api/v1", url)
                        .replace("allowCidrs: [10.0.0.0/8]", "allowCidrs: [127.0.0.0/8]"),
                Map.of(Persona.READER, List.of("airflow-users"),
                        Persona.OPERATOR, List.of("airflow-operators"),
                        Persona.APPROVER, List.of("airflow-approvers")),
                Map.of("AIRFLOW_API_USER", USER, "AIRFLOW_API_PASSWORD", PASS));

        try (FrontDoor d = FrontDoor.open(door, scenario, dir)) {
            d.note("apache/airflow:2.9.3 standalone on " + url + "; keep " + keepDag + "; delete " + victim);
            GovernedFlow.read(d, "airflow_get_health", Map.of(), "healthy");
            GovernedFlow.read(d, "airflow_list_dags", Map.of(), keepDag);
            GovernedFlow.read(d, "airflow_get_dag", Map.of("dagId", keepDag), keepDag);
            GovernedFlow.destructive(d, "airflow_delete_dag", Map.of("dagId", victim),
                    () -> assertTrue(listDags().contains(victim), victim + " still listed"),
                    () -> {
                        // Prevent the scheduler from re-importing the deleted DAG from disk.
                        removeDagFile(victim);
                        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
                        boolean gone = false;
                        while (System.nanoTime() < deadline) {
                            if (!listDags().contains(victim)) {
                                gone = true;
                                break;
                            }
                            Thread.sleep(1000);
                        }
                        assertTrue(gone, victim + " deleted; list=" + listDags());
                        assertTrue(listDags().contains(keepDag), keepDag + " untouched");
                        d.note("Airflow deleted " + victim + " and still has " + keepDag);
                    });
        }
    }

    private static String unauthHealth() throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(api() + "/health"))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
        return r.statusCode() + ":" + r.body();
    }

    private static void resetAdminPassword() throws Exception {
        Process p = new ProcessBuilder("docker", "exec", "aegis-val-airflow", "bash", "-lc",
                "airflow users create --role Admin --username " + USER
                        + " --email aegis@example.com --firstname Aegis --lastname Admin --password " + PASS
                        + " || airflow users reset-password -u " + USER + " -p " + PASS)
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(2, java.util.concurrent.TimeUnit.MINUTES) || p.exitValue() != 0) {
            throw new IllegalStateException("could not set Airflow admin password: " + out);
        }
    }

    private static String listDags() throws Exception {
        return get("/dags?limit=100");
    }

    /**
     * Writes a one-shot DAG into the container via stdin and waits until the metadata API lists it.
     * Leaves the file in place so Airflow keeps the DAG {@code is_active} (default {@code only_active}
     * listing would otherwise hide it after a file removal).
     */
    private static String seedDisposableDag(String dagId) throws Exception {
        String py = "from airflow import DAG\n"
                + "from airflow.operators.empty import EmptyOperator\n"
                + "from datetime import datetime\n"
                + "with DAG(dag_id='" + dagId + "', start_date=datetime(2024,1,1), schedule=None, "
                + "catchup=False) as dag:\n"
                + "    EmptyOperator(task_id='t')\n";
        Process write = new ProcessBuilder("docker", "exec", "-i", "aegis-val-airflow",
                "tee", "/opt/airflow/dags/" + dagId + ".py").redirectErrorStream(true).start();
        write.getOutputStream().write(py.getBytes(StandardCharsets.UTF_8));
        write.getOutputStream().close();
        String wout = new String(write.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(write.waitFor(60, java.util.concurrent.TimeUnit.SECONDS) && write.exitValue() == 0, wout);
        Process chown = new ProcessBuilder("docker", "exec", "aegis-val-airflow", "bash", "-lc",
                "chown airflow:root /opt/airflow/dags/" + dagId + ".py || true")
                .redirectErrorStream(true).start();
        chown.getInputStream().readAllBytes();
        chown.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
        long deadline = System.nanoTime() + Duration.ofMinutes(4).toNanos();
        while (System.nanoTime() < deadline) {
            if (listDags().contains(dagId)) {
                return dagId;
            }
            Thread.sleep(2000);
        }
        throw new IllegalStateException("DAG " + dagId + " never appeared: " + listDags()
                + " file=" + wout);
    }

    private static void removeDagFile(String dagId) throws Exception {
        Process rm = new ProcessBuilder("docker", "exec", "aegis-val-airflow", "rm", "-f",
                "/opt/airflow/dags/" + dagId + ".py").redirectErrorStream(true).start();
        rm.getInputStream().readAllBytes();
        rm.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static String get(String path) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(api() + path))
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString((USER + ":" + PASS).getBytes(StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString());
        return r.statusCode() + ":" + r.body();
    }

    private static String api() {
        return "http://127.0.0.1:" + port + "/api/v1";
    }
}
