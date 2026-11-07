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
 * {@code adopters/configurations/adapters/hadoop/hadoop.yaml} against a real HDFS NameNode + DataNode
 * (bde2020 Hadoop 3.2.1 images) through both front doors. WebHDFS with simple {@code user.name=root}.
 */
class HadoopFrontDoorsTest {

    private static final String IMAGE_NN = "bde2020/hadoop-namenode:2.0.0-hadoop3.2.1-java8";
    private static final String IMAGE_DN = "bde2020/hadoop-datanode:2.0.0-hadoop3.2.1-java8";
    private static final String NET = "aegis-val-hadoop-net";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static DockerContainer namenode;
    private static DockerContainer datanode;
    private static int port;

    @TempDir
    Path dir;

    @BeforeAll
    static void startHdfs() throws Exception {
        DockerContainer.requireDocker();
        port = JavaServer.freePort();
        Path log = Path.of("target", "hadoop-server").toAbsolutePath();
        execQuiet(List.of("docker", "network", "create", NET));
        execQuiet(List.of("docker", "rm", "-f", "aegis-val-namenode", "aegis-val-datanode"));
        namenode = DockerContainer.start("aegis-val-namenode", IMAGE_NN,
                List.of("--network", NET, "-p", "127.0.0.1:" + port + ":9870",
                        "-e", "CLUSTER_NAME=aegis",
                        "-e", "CORE_CONF_fs_defaultFS=hdfs://aegis-val-namenode:8020"),
                log.resolve("namenode.log"));
        datanode = DockerContainer.start("aegis-val-datanode", IMAGE_DN,
                List.of("--network", NET,
                        "-e", "CORE_CONF_fs_defaultFS=hdfs://aegis-val-namenode:8020",
                        "-e", "SERVICE_PRECONDITION=aegis-val-namenode:9870"),
                log.resolve("datanode.log"));
        namenode.awaitReady(() -> webhdfs("GET", "/", "LISTSTATUS").statusCode() == 200,
                Duration.ofMinutes(3), "HDFS NameNode WebHDFS");
        webhdfs("PUT", "/aegis", "MKDIRS");
        webhdfs("PUT", "/aegis/orders", "MKDIRS");
    }

    @AfterAll
    static void stopHdfs() throws Exception {
        if (datanode != null) {
            datanode.close();
        }
        if (namenode != null) {
            namenode.close();
        }
        execQuiet(List.of("docker", "network", "rm", NET));
    }

    @ParameterizedTest(name = "hadoop through {0}")
    @ValueSource(strings = {FrontDoor.OWN_HOST, FrontDoor.KNOX})
    void governsARealWebHdfs(String door) throws Exception {
        String scratch = "/aegis/scratch_" + door.replace("-", "");
        assertTrue(webhdfs("PUT", scratch, "MKDIRS").statusCode() == 200, "seed " + scratch);

        String url = "http://127.0.0.1:" + port;
        AdopterScenario scenario = AdopterScenario.of("hadoop",
                AdopterScenario.canonical("adapters", "hadoop", "hadoop.yaml"),
                yaml -> yaml.replace("http://namenode:9870", url)
                        .replace("allowCidrs: [10.0.0.0/8]", "allowCidrs: [127.0.0.0/8]"),
                Map.of(Persona.READER, List.of("hadoop-users"),
                        Persona.OPERATOR, List.of("hadoop-operators"),
                        Persona.APPROVER, List.of("hadoop-approvers")),
                Map.of());

        try (FrontDoor d = FrontDoor.open(door, scenario, dir)) {
            d.note("bde2020 Hadoop 3.2.1 NameNode WebHDFS on " + url + "; seeded /aegis/orders and " + scratch);
            GovernedFlow.read(d, "hadoop_list_status", Map.of("path", "/aegis"), "orders");
            GovernedFlow.read(d, "hadoop_get_file_status", Map.of("path", "/aegis/orders"), "DIRECTORY");
            GovernedFlow.destructive(d, "hadoop_delete_path", Map.of("path", scratch),
                    () -> assertTrue(webhdfs("GET", scratch, "GETFILESTATUS").statusCode() == 200, scratch + " exists"),
                    () -> {
                        assertTrue(webhdfs("GET", scratch, "GETFILESTATUS").statusCode() == 404, scratch + " gone");
                        assertTrue(webhdfs("GET", "/aegis/orders", "GETFILESTATUS").statusCode() == 200, "orders untouched");
                        d.note("HDFS deleted " + scratch + " and still has /aegis/orders");
                    });
        }
    }

    private static HttpResponse<String> webhdfs(String method, String path, String op) throws Exception {
        String p = path.startsWith("/") ? path : "/" + path;
        URI uri = URI.create("http://127.0.0.1:" + port + "/webhdfs/v1" + p + "?op=" + op + "&user.name=root");
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15));
        HttpRequest req = "GET".equals(method) ? b.GET().build()
                : "DELETE".equals(method) ? b.DELETE().build()
                : b.PUT(HttpRequest.BodyPublishers.noBody()).build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static void execQuiet(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            p.waitFor();
        } catch (Exception ignored) {
            // best effort cleanup / network create
        }
    }
}
