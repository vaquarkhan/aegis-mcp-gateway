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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * examples/adopters/activemq: the canonical {@code adopters/configurations/adapters/activemq/activemq.yaml}
 * against a stock Apache ActiveMQ Classic 6.3.2 broker (the official binary distribution, started as
 * {@code bin/activemq start} starts it) through both front doors. The broker's shipped configuration is
 * kept except for the steps an operator takes: loopback ports, a dedicated gateway account instead of
 * {@code admin/admin}, the queues the test uses, and the documented Jolokia change that lets
 * {@code removeQueue} through ({@code exec} allowed, {@code removeQueue} no longer denied).
 */
class ActiveMqFrontDoorsTest {

    private static final String GATEWAY_USER = "aegis-gateway";
    private static final String GATEWAY_SECRET = "aegis-validation-broker-credential";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static JavaServer broker;
    private static int webPort;

    @TempDir
    Path dir;

    @BeforeAll
    static void startBroker() throws Exception {
        Path home = JavaServer.distribution("aegis.activemq.home");
        Path base = Path.of("target", "activemq-server").toAbsolutePath();
        deleteTree(base);
        Path conf = Files.createDirectories(base.resolve("conf"));
        Files.createDirectories(base.resolve("data").resolve("tmp"));
        try (Stream<Path> files = Files.walk(home.resolve("conf"))) {
            for (Path p : (Iterable<Path>) files::iterator) {
                Path to = conf.resolve(home.resolve("conf").relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(to);
                } else {
                    Files.copy(p, to);
                }
            }
        }
        webPort = JavaServer.freePort();
        int openwirePort = JavaServer.freePort();

        // Operator step 1: loopback only.
        edit(conf.resolve("jetty-spring.properties"), "jetty.http.port=8161",
                "jetty.http.port=" + webPort + "\njetty.http.host=127.0.0.1");
        edit(conf.resolve("activemq.xml"), "tcp://0.0.0.0:61616", "tcp://127.0.0.1:" + openwirePort);
        // The queues the scenario works on, created at startup (static destinations).
        edit(conf.resolve("activemq.xml"), "<transportConnectors>",
                "<destinations><queue physicalName=\"orders\"/><queue physicalName=\"scratch_ownhost\"/>"
                        + "<queue physicalName=\"scratch_knox\"/></destinations>\n        <transportConnectors>");
        // Operator step 2: a gateway account instead of the default admin/admin.
        edit(conf.resolve("users.properties"), "admin=admin", GATEWAY_USER + "=" + GATEWAY_SECRET);
        edit(conf.resolve("groups.properties"), "admins=admin", "admins=" + GATEWAY_USER);
        // Operator step 3, as activemq.yaml documents it: allow exec, and stop denying removeQueue only.
        edit(conf.resolve("jolokia-access.xml"), "<command>search</command>",
                "<command>search</command>\n    <command>exec</command>");
        edit(conf.resolve("jolokia-access.xml"), "<operation>removeQueue</operation>", "");
        io.github.vaquarkhan.aegis.validation.frontdoor.RunRecord.engineFile(conf.resolve("jolokia-access.xml"),
                "the broker's Jolokia policy after the operator step (`exec` allowed, `removeQueue` no longer denied).");
        io.github.vaquarkhan.aegis.validation.frontdoor.RunRecord.engineFile(conf.resolve("activemq.xml"),
                "the broker configuration with loopback ports and the three static queues.");
        io.github.vaquarkhan.aegis.validation.frontdoor.RunRecord.engineFile(conf.resolve("jetty-spring.properties"),
                "the web console port and loopback host.");

        String b = base.toString();
        broker = JavaServer.fromDistribution(home.resolve("bin").resolve("activemq.jar").toString(),
                List.of("-Xms256m", "-Xmx512m",
                        "-Djava.security.auth.login.config=" + conf.resolve("login.config"),
                        "-Dactivemq.classpath=" + conf,
                        "-Dactivemq.home=" + home, "-Dactivemq.base=" + b, "-Dactivemq.conf=" + conf,
                        "-Dactivemq.data=" + base.resolve("data"),
                        "-Djolokia.conf=file:" + conf.resolve("jolokia-access.xml").toString().replace('\\', '/'),
                        "-Djava.io.tmpdir=" + base.resolve("data").resolve("tmp")),
                "org.apache.activemq.console.Main", List.of("start"), Map.of(), base.resolve("activemq.log"));
        broker.awaitReady(() -> jolokia("{\"type\":\"version\"}").contains("\"status\":200"),
                Duration.ofSeconds(120), "ActiveMQ web console and Jolokia");
    }

    @AfterAll
    static void stopBroker() throws Exception {
        if (broker != null) {
            broker.close();
        }
    }

    @ParameterizedTest(name = "activemq through {0}")
    @ValueSource(strings = {FrontDoor.OWN_HOST, FrontDoor.KNOX})
    void governsARealActiveMqBroker(String door) throws Exception {
        String scratch = "scratch_" + door.replace("-", "");
        assertTrue(queues().contains("destinationName=" + scratch), "broker starts with queue " + scratch);

        String url = "http://127.0.0.1:" + webPort;
        AdopterScenario scenario = AdopterScenario.of("activemq",
                AdopterScenario.canonical("adapters", "activemq", "activemq.yaml"),
                yaml -> yaml.replace("http://activemq:8161", url)
                        .replace("allowCidrs: [10.0.0.0/8]", "allowCidrs: [127.0.0.0/8]"),
                Map.of(Persona.READER, List.of("activemq-users"),
                        Persona.OPERATOR, List.of("activemq-operators"),
                        Persona.APPROVER, List.of("activemq-approvers")),
                Map.of("ACTIVEMQ_JOLOKIA_USER", GATEWAY_USER, "ACTIVEMQ_JOLOKIA_PASSWORD", GATEWAY_SECRET));

        try (FrontDoor d = FrontDoor.open(door, scenario, dir)) {
            d.note("stock ActiveMQ Classic 6.3.2, Jolokia on " + url + "/api/jolokia; queues orders and " + scratch);
            GovernedFlow.read(d, "activemq_get_broker", Map.of(), "6.3.2");
            GovernedFlow.read(d, "activemq_list_queues", Map.of(), "destinationName=orders");
            GovernedFlow.destructive(d, "activemq_remove_queue", Map.of("queue", scratch),
                    () -> assertTrue(queues().contains("destinationName=" + scratch),
                            "denied and previewed calls did not remove " + scratch),
                    () -> {
                        String now = queues();
                        assertFalse(now.contains("destinationName=" + scratch), scratch + " is gone: " + now);
                        assertTrue(now.contains("destinationName=orders"), "orders untouched: " + now);
                        d.note("the broker no longer has queue " + scratch + " and still has orders");
                    });
        }
    }

    /** The broker's queue MBeans, read through Jolokia directly, outside Aegis. */
    private static String queues() throws Exception {
        String r = jolokia("{\"type\":\"search\",\"mbean\":\"org.apache.activemq:type=Broker,brokerName=*,destinationType=Queue,destinationName=*\"}");
        assertTrue(r.contains("\"status\":200"), r);
        return r;
    }

    private static String jolokia(String body) throws Exception {
        String auth = Base64.getEncoder().encodeToString((GATEWAY_USER + ":" + GATEWAY_SECRET).getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + webPort + "/api/jolokia"))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Basic " + auth)
                .header("Origin", "http://127.0.0.1:" + webPort).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new IllegalStateException("Jolokia HTTP " + r.statusCode());
        }
        return r.body();
    }

    private static void edit(Path file, String from, String to) throws Exception {
        String s = Files.readString(file, StandardCharsets.UTF_8);
        if (!s.contains(from)) {
            throw new IllegalStateException(file + " does not contain " + from + "; has the distribution changed?");
        }
        Files.writeString(file, s.replace(from, to), StandardCharsets.UTF_8);
    }

    private static void deleteTree(Path p) throws Exception {
        if (!Files.exists(p)) {
            return;
        }
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : (Iterable<Path>) s.sorted(java.util.Comparator.reverseOrder())::iterator) {
                Files.delete(x);
            }
        }
    }
}
