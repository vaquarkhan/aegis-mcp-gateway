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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * examples/adopters/flume: the canonical {@code adopters/configurations/adapters/flume/flume.yaml} against a real
 * Apache Flume 1.11.0 agent ({@code org.apache.flume.node.Application}, as {@code bin/flume-ng agent} runs it)
 * with HTTP monitoring on, through both front doors. The agent moves events from a sequence source through a
 * memory channel to a null sink, so its metrics are live counters. The profile is read-only.
 */
class FlumeFrontDoorsTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static JavaServer agent;
    private static int port;

    @TempDir
    Path dir;

    @BeforeAll
    static void startAgent() throws Exception {
        Path work = Files.createDirectories(Path.of("target", "flume-agent").toAbsolutePath());
        Path conf = work.resolve("aegis-agent.properties");
        Files.writeString(conf, String.join("\n",
                "a1.sources = seq",
                "a1.channels = mem",
                "a1.sinks = drop",
                "a1.sources.seq.type = seq",
                "a1.sources.seq.channels = mem",
                "a1.channels.mem.type = memory",
                "a1.channels.mem.capacity = 1000",
                "a1.sinks.drop.type = null",
                "a1.sinks.drop.channel = mem", ""));
        port = JavaServer.freePort();
        agent = new JavaServer("aegis.flume.serverClasspathFile",
                List.of("-Xmx256m", "-Dflume.monitoring.type=http", "-Dflume.monitoring.port=" + port),
                "org.apache.flume.node.Application", List.of("-n", "a1", "-f", conf.toString()), Map.of(),
                work.resolve("flume-agent.log"));
        agent.awaitReady(() -> metrics().contains("SOURCE.seq"), Duration.ofSeconds(90), "Flume agent HTTP monitoring");
    }

    @AfterAll
    static void stopAgent() throws Exception {
        if (agent != null) {
            agent.close();
        }
    }

    @ParameterizedTest(name = "flume through {0}")
    @ValueSource(strings = {FrontDoor.OWN_HOST, FrontDoor.KNOX})
    void governsARealFlumeAgent(String door) throws Exception {
        AdopterScenario scenario = AdopterScenario.of("flume", AdopterScenario.canonical("adapters", "flume", "flume.yaml"),
                yaml -> yaml.replace("http://flume-agent:41414", "http://127.0.0.1:" + port)
                        .replace("allowCidrs: [10.0.0.0/8]", "allowCidrs: [127.0.0.0/8]"),
                Map.of(Persona.READER, List.of("flume-users"),
                        Persona.OPERATOR, List.of("flume-operators"),
                        Persona.APPROVER, List.of("flume-approvers")),
                Map.of());

        try (FrontDoor d = FrontDoor.open(door, scenario, dir)) {
            d.note("real Flume 1.11.0 agent a1 (seq source, memory channel, null sink), HTTP monitoring on 127.0.0.1:" + port);
            GovernedFlow.read(d, "flume_get_metrics", Map.of(), "EventAcceptedCount");
            GovernedFlow.read(d, "flume_get_health", Map.of(), "metrics");
        }
    }

    private static String metrics() throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/metrics"))
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
    }
}
