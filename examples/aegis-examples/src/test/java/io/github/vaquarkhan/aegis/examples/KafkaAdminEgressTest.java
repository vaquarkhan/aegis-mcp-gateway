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
package io.github.vaquarkhan.aegis.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.config.BuildContext;
import io.github.vaquarkhan.aegis.config.ConfigException;
import io.github.vaquarkhan.aegis.config.Gateway;
import io.github.vaquarkhan.aegis.config.GatewayFactory;
import io.github.vaquarkhan.aegis.engine.spi.AddressResolver;
import io.github.vaquarkhan.aegis.examples.kafka.KafkaAdminToolSourceFactory;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Kafka Admin source reports its bootstrap servers to the egress control, so a broker name is
 * checked like an HTTP source's host: every DNS answer must be public or inside {@code allowCidrs}.
 */
class KafkaAdminEgressTest {

    @TempDir
    Path dir;

    /** kafka-1 resolves to a private address; every lookup is recorded. */
    final List<String> lookups = new ArrayList<>();
    final AddressResolver resolver = host -> {
        lookups.add(host);
        if (host.equals("kafka-1")) {
            return new InetAddress[] {InetAddress.getByAddress("kafka-1", new byte[] {10, 1, 2, 3})};
        }
        throw new UnknownHostException(host);
    };

    Gateway load(String bootstrap, String cidrs) throws Exception {
        String yaml = Files.readString(AdopterConfigurations.profile("kafka"));
        assertTrue(yaml.contains("bootstrapServers: kafka-1:9092") && yaml.contains("allowCidrs: [10.0.0.0/8]"));
        yaml = yaml.replace("bootstrapServers: kafka-1:9092", "bootstrapServers: " + bootstrap)
                .replace("allowCidrs: [10.0.0.0/8]", "allowCidrs: " + cidrs);
        Path p = dir.resolve("kafka.yaml");
        Files.writeString(p, yaml);
        return GatewayFactory.build(p, new BuildContext(ExampleConfigsTest.ENV::get, dir, null, resolver, null, null, null, null));
    }

    @Test
    void aBootstrapServerOutsideTheEgressCidrsIsDeniedBeforeTheAdminClientIsUsed() throws Exception {
        try (Gateway g = load("kafka-1:9092", "[192.0.2.0/24]")) {
            io.github.vaquarkhan.aegis.engine.CallOutcome o = g.engine().call(ExampleConfigsTest.who("ana", "kafka-users"),
                    new io.github.vaquarkhan.aegis.engine.model.ToolCall("1", "kafka_list_topics", Map.of(), Map.of(), "2026-07-28"));
            assertEquals(io.github.vaquarkhan.aegis.engine.model.DenyCodes.EGRESS_DENIED, o.decision().code(), o.decision().toString());
            assertTrue(lookups.contains("kafka-1"), "the broker name was resolved and its answer classified");
        }
    }

    @Test
    void theBootstrapListIsParsedIntoTcpEndpointsAndABadEntryStopsStartup() throws Exception {
        assertEquals(List.of(URI.create("tcp://kafka-1:9092"), URI.create("tcp://kafka-2.example:9093"), URI.create("tcp://[::1]:9094")),
                KafkaAdminToolSourceFactory.bootstrapEndpoints("kafka-1:9092, SASL_SSL://kafka-2.example:9093,[::1]:9094"));
        for (String bad : List.of("kafka-1", "kafka-1:0", "kafka-1:70000", "user@kafka-1:9092", ":9092", " , ")) {
            assertThrows(IllegalArgumentException.class, () -> KafkaAdminToolSourceFactory.bootstrapEndpoints(bad), bad);
        }
        ConfigException e = assertThrows(ConfigException.class, () -> load("kafka-1", "[10.0.0.0/8]").close());
        assertTrue(e.getMessage().contains("bootstrapServers"), e.getMessage());
    }
}
