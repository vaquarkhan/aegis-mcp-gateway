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

import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.validation.frontdoor.AdopterScenario;
import io.github.vaquarkhan.aegis.validation.frontdoor.FrontDoor;
import io.github.vaquarkhan.aegis.validation.frontdoor.GovernedFlow;
import io.github.vaquarkhan.aegis.validation.frontdoor.JavaServer;
import io.github.vaquarkhan.aegis.validation.frontdoor.Persona;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.Uuid;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * examples/adopters/kafka: the canonical {@code adopters/configurations/kafka/kafka.yaml} against a REAL
 * Apache Kafka 3.9.1 broker (KRaft, combined broker and controller) and a REAL Kafka Connect worker, each in
 * its own JVM, through both front doors. Two of the profile's three sources are exercised: topics through
 * the {@code kafka-admin} plugin and connectors through the Connect REST API. The third, Schema Registry,
 * is a Confluent service rather than part of Apache Kafka and is not started here.
 */
class KafkaFrontDoorsTest {

    private static JavaServer broker;
    private static JavaServer connect;
    private static int brokerPort;
    private static int connectPort;
    private static Path work;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    Path dir;

    @BeforeAll
    static void startKafka() throws Exception {
        work = Path.of("target", "kafka-server").toAbsolutePath();
        Files.createDirectories(work);
        brokerPort = JavaServer.freePort();
        int controllerPort = JavaServer.freePort();
        connectPort = JavaServer.freePort();
        String logs = Files.createDirectories(work.resolve("kraft-logs-" + System.nanoTime())).toString().replace('\\', '/');

        Path server = work.resolve("server.properties");
        Files.writeString(server, String.join("\n",
                "process.roles=broker,controller",
                "node.id=1",
                "controller.quorum.voters=1@127.0.0.1:" + controllerPort,
                "listeners=PLAINTEXT://127.0.0.1:" + brokerPort + ",CONTROLLER://127.0.0.1:" + controllerPort,
                "advertised.listeners=PLAINTEXT://127.0.0.1:" + brokerPort,
                "controller.listener.names=CONTROLLER",
                "listener.security.protocol.map=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT",
                "inter.broker.listener.name=PLAINTEXT",
                "log.dirs=" + logs,
                "num.partitions=1",
                "offsets.topic.replication.factor=1",
                "transaction.state.log.replication.factor=1",
                "transaction.state.log.min.isr=1",
                "group.initial.rebalance.delay.ms=0", ""));
        // kafka-storage.sh format, then kafka-server-start.sh: the two steps a KRaft node needs.
        JavaServer format = new JavaServer("aegis.kafka.serverClasspathFile", List.of(), "kafka.tools.StorageTool",
                List.of("format", "-t", Uuid.randomUuid().toString(), "-c", server.toString()), work.resolve("kafka-storage-format.log"));
        if (format.awaitExit(Duration.ofSeconds(120)) != 0) {
            throw new IllegalStateException("kafka-storage format failed; see " + format.log());
        }
        broker = new JavaServer("aegis.kafka.serverClasspathFile", List.of(), "kafka.Kafka",
                List.of(server.toString()), work.resolve("kafka-broker.log"));
        broker.awaitReady(() -> {
            try (Admin a = admin()) {
                a.listTopics().names().get();
                return true;
            }
        }, Duration.ofSeconds(120), "Kafka broker");
        try (Admin a = admin()) {
            a.createTopics(List.of(new NewTopic("orders", 1, (short) 1))).all().get();
        }

        Path worker = work.resolve("connect-standalone.properties");
        Files.writeString(worker, String.join("\n",
                "bootstrap.servers=127.0.0.1:" + brokerPort,
                "key.converter=org.apache.kafka.connect.storage.StringConverter",
                "value.converter=org.apache.kafka.connect.storage.StringConverter",
                "offset.storage.file.filename=" + work.resolve("connect.offsets").toString().replace('\\', '/'),
                "listeners=http://127.0.0.1:" + connectPort, ""));
        connect = new JavaServer("aegis.kafka.serverClasspathFile", List.of(), "org.apache.kafka.connect.cli.ConnectStandalone",
                List.of(worker.toString()), work.resolve("kafka-connect.log"));
        connect.awaitReady(() -> get("/connectors").statusCode() == 200, Duration.ofSeconds(180), "Kafka Connect worker");
    }

    @AfterAll
    static void stopKafka() throws Exception {
        if (connect != null) {
            connect.close();
        }
        if (broker != null) {
            broker.close();
        }
    }

    @ParameterizedTest(name = "kafka through {0}")
    @ValueSource(strings = {FrontDoor.OWN_HOST, FrontDoor.KNOX})
    void governsARealKafkaBrokerAndConnectWorker(String door) throws Exception {
        String connector = "orders-file-source-" + door.replace("-", "");
        createConnector(connector);

        AdopterScenario scenario = AdopterScenario.of("kafka", AdopterScenario.canonical("kafka", "kafka.yaml"),
                yaml -> yaml.replace("http://kafka-connect:8083", "http://127.0.0.1:" + connectPort)
                        .replace("bootstrapServers: kafka-1:9092", "bootstrapServers: 127.0.0.1:" + brokerPort)
                        .replace("allowCidrs: [10.0.0.0/8]", "allowCidrs: [127.0.0.0/8]"),
                Map.of(Persona.READER, List.of("kafka-users"),
                        Persona.OPERATOR, List.of("kafka-operators"),
                        Persona.APPROVER, List.of("kafka-approvers")),
                Map.of()).withKnoxPlugin(AdopterScenario.plugin("aegis.kafka.pluginJar", "aegis.kafka.pluginClasspathFile"),
                List.of());

        try (FrontDoor d = FrontDoor.open(door, scenario, dir)) {
            d.note("real Kafka 3.9.1 broker 127.0.0.1:" + brokerPort + " (topic orders); Connect worker 127.0.0.1:"
                    + connectPort + " running connector " + connector + " " + connectors());
            GovernedFlow.read(d, "kafka_list_topics", Map.of(), "orders");
            GovernedFlow.read(d, "kafka_describe_cluster", Map.of(), "clusterId");
            GovernedFlow.read(d, "kafka_describe_topic_configs", Map.of("topic", "orders"), "configs");
            GovernedFlow.read(d, "connect_list_connectors", Map.of(), connector);
            GovernedFlow.destructive(d, "connect_delete_connector", Map.of("name", connector),
                    () -> assertTrue(connectors().contains(connector), "denied and previewed calls did not delete " + connector),
                    () -> {
                        assertFalse(connectors().contains(connector), connector + " is really gone");
                        d.note("Kafka Connect now lists " + connectors());
                    });
        }
    }

    private static Admin admin() {
        Properties p = new Properties();
        p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:" + brokerPort);
        p.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
        p.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10_000);
        return Admin.create(p);
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + connectPort + path))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Setup outside Aegis, as an operator's deployment pipeline would do. */
    private static void createConnector(String name) throws Exception {
        Path source = work.resolve(name + ".txt");
        Files.writeString(source, "order-1\norder-2\n");
        String body = Json.write(Map.of("name", name, "config", Map.of(
                "connector.class", "org.apache.kafka.connect.file.FileStreamSourceConnector",
                "tasks.max", "1",
                "file", source.toString().replace('\\', '/'),
                "topic", "orders")));
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + connectPort + "/connectors"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, r.statusCode(), r.body());
    }

    @SuppressWarnings("unchecked")
    private static Set<String> connectors() throws Exception {
        return Set.copyOf((List<String>) (List<?>) Json.parse(get("/connectors").body()));
    }
}
