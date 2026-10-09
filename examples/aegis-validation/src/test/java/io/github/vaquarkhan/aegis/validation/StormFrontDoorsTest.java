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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code adopters/configurations/adapters/storm/storm.yaml} against a real Apache Storm 2.6.2 cluster
 * (ZooKeeper + Nimbus + Supervisor + UI) through both front doors. Disposable WordCount topologies are
 * submitted outside Aegis and killed through governance.
 */
class StormFrontDoorsTest {

    private static final String IMAGE = "storm:2.6.2";
    private static final String ZK_IMAGE = "zookeeper:3.9.3";
    private static final String NET = "aegis-val-storm-net";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Pattern TOPO_ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");

    private static DockerContainer zk;
    private static DockerContainer nimbus;
    private static DockerContainer supervisor;
    private static DockerContainer ui;
    private static int port;
    private static String ownHostVictim;
    private static String knoxVictim;

    @TempDir
    Path dir;

    @BeforeAll
    static void startStorm() throws Exception {
        DockerContainer.requireDocker();
        port = JavaServer.freePort();
        Path log = Path.of("target", "storm-server").toAbsolutePath();
        execQuiet(List.of("docker", "network", "create", NET));
        execQuiet(List.of("docker", "rm", "-f", "aegis-val-storm-zk", "aegis-val-storm-nimbus",
                "aegis-val-storm-supervisor", "aegis-val-storm-ui"));
        zk = DockerContainer.start("aegis-val-storm-zk", ZK_IMAGE,
                List.of("--network", NET, "--network-alias", "zookeeper"), log.resolve("zk.log"));
        // Official storm image takes -c overrides (not STORM_* env); hostname aliases match the Hub compose example.
        nimbus = DockerContainer.start("aegis-val-storm-nimbus", IMAGE,
                List.of("--network", NET, "--network-alias", "nimbus"),
                List.of("storm", "nimbus", "-c", "storm.zookeeper.servers=[\"zookeeper\"]"),
                log.resolve("nimbus.log"));
        supervisor = DockerContainer.start("aegis-val-storm-supervisor", IMAGE,
                List.of("--network", NET),
                List.of("storm", "supervisor",
                        "-c", "storm.zookeeper.servers=[\"zookeeper\"]",
                        "-c", "nimbus.seeds=[\"nimbus\"]"),
                log.resolve("supervisor.log"));
        ui = DockerContainer.start("aegis-val-storm-ui", IMAGE,
                List.of("--network", NET, "-p", "127.0.0.1:" + port + ":8080"),
                List.of("storm", "ui",
                        "-c", "storm.zookeeper.servers=[\"zookeeper\"]",
                        "-c", "nimbus.seeds=[\"nimbus\"]"),
                log.resolve("ui.log"));
        ui.awaitReady(() -> get("/api/v1/cluster/summary").contains("\"stormVersion\""),
                Duration.ofMinutes(6), "Storm UI");
        ownHostVictim = submitTopology("aegis_ownhost");
        knoxVictim = submitTopology("aegis_knox");
        ui.awaitReady(() -> topologies().contains(ownHostVictim) && topologies().contains(knoxVictim),
                Duration.ofMinutes(3), "submitted topologies visible");
    }

    @AfterAll
    static void stopStorm() throws Exception {
        if (ui != null) {
            ui.close();
        }
        if (supervisor != null) {
            supervisor.close();
        }
        if (nimbus != null) {
            nimbus.close();
        }
        if (zk != null) {
            zk.close();
        }
        execQuiet(List.of("docker", "network", "rm", NET));
    }

    @ParameterizedTest(name = "storm through {0}")
    @ValueSource(strings = {FrontDoor.OWN_HOST, FrontDoor.KNOX})
    void governsARealStorm(String door) throws Exception {
        String victim = FrontDoor.OWN_HOST.equals(door) ? ownHostVictim : knoxVictim;
        assertTrue(topologies().contains(victim), victim + " present");

        String url = "http://127.0.0.1:" + port;
        AdopterScenario scenario = AdopterScenario.of("storm",
                AdopterScenario.canonical("adapters", "storm", "storm.yaml"),
                yaml -> yaml.replace("http://storm-ui:8080", url)
                        .replace("allowCidrs: [10.0.0.0/8]", "allowCidrs: [127.0.0.0/8]"),
                Map.of(Persona.READER, List.of("storm-users"),
                        Persona.OPERATOR, List.of("storm-operators"),
                        Persona.APPROVER, List.of("storm-approvers")),
                Map.of());

        try (FrontDoor d = FrontDoor.open(door, scenario, dir)) {
            d.note("storm:2.6.2 UI on " + url + "; kill topology " + victim);
            GovernedFlow.read(d, "storm_cluster_summary", Map.of(), "stormVersion");
            GovernedFlow.read(d, "storm_list_topologies", Map.of(), victim);
            GovernedFlow.read(d, "storm_get_topology", Map.of("id", victim), victim);
            GovernedFlow.destructive(d, "storm_kill_topology", Map.of("id", victim),
                    () -> assertTrue(topologies().contains(victim), victim + " still listed"),
                    () -> {
                        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
                        boolean done = false;
                        while (System.nanoTime() < deadline) {
                            if (topologyKilledOrGone(victim)) {
                                done = true;
                                break;
                            }
                            Thread.sleep(1000);
                        }
                        assertTrue(done, victim + " killed; summary=" + topologies());
                        d.note("Storm killed topology " + victim);
                    });
        }
    }

    private static String submitTopology(String name) throws Exception {
        // Prefer a host-cached Maven Central jar (docker cp). Avoid downloading the full Apache
        // Storm tarball inside the container (slow and fragile on this Windows Docker path).
        Path cache = Path.of("target", "storm-cache", "storm-starter-2.6.2.jar").toAbsolutePath();
        if (!java.nio.file.Files.isRegularFile(cache) || java.nio.file.Files.size(cache) < 1_000_000) {
            java.nio.file.Files.createDirectories(cache.getParent());
            Process dl = new ProcessBuilder("curl.exe", "-fsSL", "-o", cache.toString(),
                    "https://repo1.maven.org/maven2/org/apache/storm/storm-starter/2.6.2/storm-starter-2.6.2.jar")
                    .redirectErrorStream(true).start();
            String dlOut = new String(dl.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!dl.waitFor(10, java.util.concurrent.TimeUnit.MINUTES) || dl.exitValue() != 0) {
                throw new IllegalStateException("could not download storm-starter jar: " + dlOut);
            }
        }
        Process cp = new ProcessBuilder("docker", "cp", cache.toString(),
                "aegis-val-storm-nimbus:/tmp/storm-starter-topologies.jar").redirectErrorStream(true).start();
        String cpOut = new String(cp.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!cp.waitFor(2, java.util.concurrent.TimeUnit.MINUTES) || cp.exitValue() != 0) {
            throw new IllegalStateException("docker cp storm-starter jar failed: " + cpOut);
        }
        Process p = new ProcessBuilder("docker", "exec", "aegis-val-storm-nimbus", "bash", "-lc",
                "storm jar /tmp/storm-starter-topologies.jar org.apache.storm.starter.WordCountTopology " + name
                        + " -c nimbus.seeds='[\"nimbus\"]' -c storm.zookeeper.servers='[\"zookeeper\"]'")
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(3, java.util.concurrent.TimeUnit.MINUTES) || p.exitValue() != 0) {
            throw new IllegalStateException("topology submit failed: " + out);
        }
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (System.nanoTime() < deadline) {
            Matcher m = TOPO_ID.matcher(topologies());
            while (m.find()) {
                if (m.group(1).startsWith(name)) {
                    return m.group(1);
                }
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("topology " + name + " not visible: " + topologies() + "\n" + out);
    }

    private static String topologies() throws Exception {
        return get("/api/v1/topology/summary");
    }

    /** Storm keeps a KILLED row in the summary briefly (sometimes longer); treat that as success. */
    private static boolean topologyKilledOrGone(String id) throws Exception {
        String body = topologies();
        if (!body.contains(id)) {
            return true;
        }
        int at = body.indexOf(id);
        String slice = body.substring(at, Math.min(body.length(), at + 500));
        return slice.contains("\"status\":\"KILLED\"") || slice.contains("\"status\":\"INACTIVE\"");
    }

    private static String get(String path) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
        return r.body();
    }

    private static void execQuiet(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            p.waitFor();
        } catch (Exception ignored) {
            // best effort
        }
    }
}
