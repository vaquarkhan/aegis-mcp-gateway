/*
 * Licensed to the Aegis MCP Gateway project under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.vaquarkhan.aegis.adapter.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.core.auth.CallerIdentity;
import io.github.vaquarkhan.aegis.core.config.GatewayConfig;
import io.github.vaquarkhan.aegis.core.spi.CallContext;
import io.github.vaquarkhan.aegis.core.spi.ResourceDef;
import io.github.vaquarkhan.aegis.core.spi.ToolClass;
import io.github.vaquarkhan.aegis.core.spi.ToolDef;
import io.github.vaquarkhan.aegis.core.util.Inputs;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** @author Viquar Khan */
class KafkaAdapterTest {

    private static final GatewayConfig CFG = GatewayConfig.builder().defaults().build();

    @Test
    void exposesExpectedToolsAndTaxonomy() {
        KafkaAdapter adapter = new KafkaAdapter();
        assertEquals("kafka", adapter.engineId());
        assertEquals("messaging", adapter.taxonomyClass());
        List<ToolDef> tools = adapter.tools(CFG);
        Set<String> names = tools.stream().map(ToolDef::name).collect(Collectors.toSet());
        assertTrue(names.containsAll(Set.of(
                "list_topics", "describe_topic", "query_schema_registry", "inspect_dlq",
                "create_topic", "alter_config", "reset_offsets", "delete_records")));
        assertEquals(ToolClass.DESTRUCTIVE,
                tools.stream().filter(t -> t.name().equals("delete_records")).findFirst().orElseThrow().cls());
    }

    @Test
    void describeTopicReachesTheClusterAndValidatesTheIdentifier() {
        FakeKafkaClusterOps fake = new FakeKafkaClusterOps();
        KafkaAdapter adapter = new KafkaAdapter(fake, null, null);
        String body = call(adapter, "describe_topic", Map.of("topic", "orders"));
        assertTrue(body.contains("\"topic\":\"orders\""));
        assertFalse(body.contains("password="));
        assertEquals(List.of("describeTopic:orders"), fake.calls());
        assertThrows(Inputs.InvalidInput.class,
                () -> call(adapter, "describe_topic", Map.of("topic", "../etc/passwd")));
    }

    @Test
    void everyAdminToolIsWiredToTheClusterBackend() {
        FakeKafkaClusterOps fake = new FakeKafkaClusterOps();
        KafkaAdapter adapter = new KafkaAdapter(fake, subject -> "{\"subject\":\"" + subject + "\",\"version\":3}",
                (topic, max) -> "{\"topic\":\"" + topic + "\",\"sampled\":" + max + "}");

        assertTrue(call(adapter, "list_topics", Map.of()).contains("orders"));
        assertTrue(call(adapter, "create_topic", Map.of("topic", "orders", "partitions", "6"))
                .contains("\"partitions\":6"));
        assertEquals(6, fake.lastPartitions());
        assertTrue(call(adapter, "alter_config",
                Map.of("resource", "orders", "configs", Map.of("retention.ms", "60000")))
                .contains("altered"));
        assertEquals(Map.of("retention.ms", "60000"), fake.lastConfigs());
        assertTrue(call(adapter, "reset_offsets", Map.of("groupId", "billing")).contains("earliest"));
        assertTrue(call(adapter, "delete_records", Map.of("topic", "orders")).contains("deleted"));
        assertTrue(call(adapter, "query_schema_registry", Map.of("subject", "orders-value"))
                .contains("orders-value"));
        assertTrue(call(adapter, "inspect_dlq", Map.of("topic", "orders-dlq", "maxRecords", "3"))
                .contains("\"sampled\":3"));

        assertEquals(
                List.of("listTopics", "createTopic:orders", "alterTopicConfig:orders",
                        "resetOffsets:billing", "deleteRecords:orders"),
                fake.calls());
    }

    @Test
    void clusterFailuresPropagateSoTheBreakerCanTrip() {
        FakeKafkaClusterOps fake = new FakeKafkaClusterOps();
        fake.failNextCalls();
        KafkaAdapter adapter = new KafkaAdapter(fake, null, null);
        assertThrows(IllegalStateException.class, () -> call(adapter, "list_topics", Map.of()),
                "a dead cluster must trip the breaker, not look like an empty topic list");
    }

    @Test
    void clusterResourceReadsLiveTopics() {
        FakeKafkaClusterOps fake = new FakeKafkaClusterOps();
        KafkaAdapter adapter = new KafkaAdapter(fake, null, null);
        ResourceDef resource = adapter.resources(CFG).get(0);
        assertEquals("kafka://cluster", resource.uri());
        assertTrue(resource.read().apply(readContext("kafka-cluster")).contains("orders"));
    }

    @Test
    void nonObjectConfigsAreRejectedAsCallerError() {
        KafkaAdapter adapter = new KafkaAdapter(new FakeKafkaClusterOps(), null, null);
        assertThrows(Inputs.InvalidInput.class,
                () -> call(adapter, "alter_config", Map.of("resource", "orders", "configs", "retention.ms=1")));
    }

    @Test
    void egressAllowsEveryBootstrapHostAndTheRegistryHost() {
        GatewayConfig cfg = GatewayConfig.builder().defaults()
                .adapterProperties(Map.of(
                        "kafka.bootstrap.servers", "broker-1:9092,broker-2:9092",
                        "kafka.schema.registry.url", "http://registry.internal:8081"))
                .build();
        assertEquals(Set.of("broker-1", "broker-2", "registry.internal"),
                new KafkaAdapter().egressAllowHosts(cfg));
    }

    private static String call(KafkaAdapter adapter, String tool, Map<String, Object> args) {
        ToolDef def = adapter.tools(CFG).stream()
                .filter(t -> t.name().equals(tool)).findFirst().orElseThrow();
        CallerIdentity caller = new CallerIdentity("test", Set.of("*"), false);
        CallContext ctx = new CallContext(tool, def.cls(), args, caller, "trace-1", Optional.empty());
        return def.backend().apply(ctx);
    }

    private static CallContext readContext(String name) {
        CallerIdentity caller = new CallerIdentity("test", Set.of("*"), false);
        return new CallContext(name, ToolClass.READ, Map.of(), caller, "trace-1", Optional.empty());
    }
}
