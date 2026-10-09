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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.config.BuildContext;
import io.github.vaquarkhan.aegis.config.Gateway;
import io.github.vaquarkhan.aegis.config.GatewayFactory;
import io.github.vaquarkhan.aegis.engine.CallOutcome;
import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.model.ControlTokens;
import io.github.vaquarkhan.aegis.engine.model.DenyCodes;
import io.github.vaquarkhan.aegis.engine.model.ToolCall;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * The canonical adopters/configurations/kafka/kafka.yaml against a REAL Apache Kafka 3.9.1 broker (KRaft) and a REAL Kafka
 * Connect worker, both started separately from the Apache binary distribution. Set
 * AEGIS_KAFKA_BOOTSTRAP (e.g. 127.0.0.1:19092) and AEGIS_KAFKA_CONNECT_URL (e.g.
 * http://127.0.0.1:18083, with a connector named aegis-file-source). Skipped when unset.
 */
@EnabledIfEnvironmentVariable(named = "AEGIS_KAFKA_BOOTSTRAP", matches = "127\\.0\\.0\\.1:\\d+")
class RealKafkaTest {

    @TempDir
    Path dir;

    static final String CONNECTOR = "aegis-file-source";

    static CallOutcome call(Gateway g, CallerIdentity c, String tool, Map<String, Object> args, Map<String, Object> meta) {
        return g.engine().call(c, new ToolCall("1", tool, args, meta, ""));
    }

    static String text(CallOutcome o) {
        return Json.write(o.toMcpResult());
    }

    @Test
    void shippedKafkaConfigGovernsARealBrokerAndConnectWorker() throws Exception {
        String bootstrap = System.getenv("AEGIS_KAFKA_BOOTSTRAP");
        String connect = System.getenv("AEGIS_KAFKA_CONNECT_URL");
        String yaml = Files.readString(Path.of("..", "..", "adopters", "configurations", "kafka", "kafka.yaml"))
                .replace("bootstrapServers: kafka-1:9092", "bootstrapServers: " + bootstrap)
                .replace("http://kafka-connect:8083", connect)
                .replace("allowCidrs: [10.0.0.0/8]", "allowCidrs: [127.0.0.0/8]");
        Path cfg = dir.resolve("kafka.yaml");
        Files.writeString(cfg, yaml);
        Map<String, String> env = Map.of("AEGIS_SIGNING_KEY", "real-kafka-validation-key-0123456789abcdef");
        String topic = "aegis.validation." + Long.toHexString(System.nanoTime());
        Properties p = new Properties();
        p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        try (Admin direct = Admin.create(p);
             Gateway g = GatewayFactory.build(cfg, BuildContext.defaults(dir).withEnv(env::get))) {
            CallerIdentity user = CallerIdentity.of("ana", Set.of("kafka-users"), "idp", "jwt");
            CallerIdentity op = CallerIdentity.of("oscar", Set.of("kafka-operators"), "idp", "jwt");
            CallerIdentity approver = CallerIdentity.of("petra", Set.of("kafka-approvers"), "idp", "jwt");

            // Kafka Connect REST (real worker).
            assertTrue(text(call(g, user, "connect_list_connectors", Map.of(), Map.of())).contains(CONNECTOR));
            CallOutcome status = call(g, user, "connect_connector_status", Map.of("name", CONNECTOR), Map.of());
            assertTrue(text(status).contains("RUNNING"), text(status));
            assertEquals(DenyCodes.READONLY_CALLER, call(g, user, "connect_pause_connector", Map.of("name", CONNECTOR), Map.of()).decision().code());
            CallOutcome pauseDry = call(g, op, "connect_pause_connector", Map.of("name", CONNECTOR), Map.of(ControlTokens.META_DRY_RUN, true));
            String prid = (String) pauseDry.governance().get("approvalRequestId");
            g.engine().approvals().decide(prid, approver, true);
            CallOutcome pause = call(g, op, "connect_pause_connector", Map.of("name", CONNECTOR),
                    Map.of(ControlTokens.META_APPROVAL, g.engine().approvals().get(prid, op).get("token")));
            assertTrue(pause.allowed(), text(pause));
            boolean paused = false;
            for (int i = 0; i < 50 && !paused; i++) {
                paused = text(call(g, user, "connect_connector_status", Map.of("name", CONNECTOR), Map.of())).contains("PAUSED");
                Thread.sleep(200);
            }
            assertTrue(paused, "the real connector reached PAUSED");

            // Admin client source (real broker).
            Map<String, Object> newTopic = Map.of("topic", topic, "partitions", 2L, "replicationFactor", 1L);
            CallOutcome dry = call(g, op, "kafka_create_topic", newTopic, Map.of(ControlTokens.META_DRY_RUN, true));
            assertTrue(dry.allowed() && text(dry).contains("Validated (not created)"), text(dry));
            assertFalse(direct.listTopics().names().get().contains(topic), "the real broker validated without creating");
            String rid = (String) dry.governance().get("approvalRequestId");
            g.engine().approvals().decide(rid, approver, true);
            CallOutcome create = call(g, op, "kafka_create_topic", newTopic,
                    Map.of(ControlTokens.META_APPROVAL, g.engine().approvals().get(rid, op).get("token")));
            assertTrue(create.allowed(), text(create));
            assertTrue(direct.listTopics().names().get().contains(topic));
            CallOutcome described = call(g, user, "kafka_describe_topic", Map.of("topic", topic), Map.of());
            assertTrue(text(described).contains("\"partitions\":2"), text(described));

            assertEquals(DenyCodes.POLICY_DENIED, call(g, op, "kafka_delete_topic", Map.of("topic", "__consumer_offsets"), Map.of()).decision().code());
            CallOutcome delDry = call(g, op, "kafka_delete_topic", Map.of("topic", topic), Map.of(ControlTokens.META_DRY_RUN, true));
            String drid = (String) delDry.governance().get("approvalRequestId");
            String receipt = (String) delDry.governance().get("dryRunReceipt");
            assertNotNull(receipt);
            g.engine().approvals().decide(drid, approver, true);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put(ControlTokens.META_APPROVAL, g.engine().approvals().get(drid, op).get("token"));
            meta.put(ControlTokens.META_RECEIPT, receipt);
            CallOutcome del = call(g, op, "kafka_delete_topic", Map.of("topic", topic), meta);
            assertTrue(del.allowed(), text(del));
            boolean gone = false;
            for (int i = 0; i < 50 && !gone; i++) {
                gone = !direct.listTopics().names().get().contains(topic);
                Thread.sleep(200);
            }
            assertTrue(gone, "the real broker deleted the topic");
        }
    }
}
