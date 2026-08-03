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
package io.github.vaquarkhan.aegis.adapter.activemq;

import io.github.vaquarkhan.aegis.core.config.GatewayConfig;
import io.github.vaquarkhan.aegis.core.spi.CallContext;
import io.github.vaquarkhan.aegis.core.spi.EngineAdapter;
import io.github.vaquarkhan.aegis.core.spi.ResourceDef;
import io.github.vaquarkhan.aegis.core.spi.ToolClass;
import io.github.vaquarkhan.aegis.core.spi.ToolDef;
import io.github.vaquarkhan.aegis.core.util.HttpJsonClient;
import io.github.vaquarkhan.aegis.core.util.Inputs;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Apache ActiveMQ adapter. Talks to the configured HTTP/REST surface; failures propagate for the breaker.
 *
 * @author Viquar Khan
 */
public final class ActivemqAdapter implements EngineAdapter {

    @Override
    public String engineId() {
        return "activemq";
    }

    @Override
    public String taxonomyClass() {
        return "messaging";
    }

    @Override
    public List<ToolDef> tools(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        List<ToolDef> tools = new ArrayList<>();
        tools.add(tool("get_broker", ToolClass.READ, "Read ActiveMQ broker Jolokia MBean",
                "{\"type\":\"object\",\"properties\":{}}",
                ctx -> client.get("/api/jolokia/read/org.apache.activemq:type=Broker,brokerName=localhost")));
        tools.add(tool("list_queues", ToolClass.READ, "List ActiveMQ queues via Jolokia search",
                "{\"type\":\"object\",\"properties\":{}}",
                ctx -> client.get("/api/jolokia/search/org.apache.activemq:type=Broker,brokerName=*,destinationType=Queue,destinationName=*")));
        tools.add(tool("purge_queue", ToolClass.DESTRUCTIVE, "Purge an ActiveMQ queue",
                "{\"type\":\"object\",\"properties\":{\"queue\":{\"type\":\"string\"},\"approvalToken\":{\"type\":\"string\"}},\"required\":[\"queue\",\"approvalToken\"]}",
                ctx -> client.post("/api/jolokia", "{\"type\":\"exec\",\"mbean\":\"org.apache.activemq:type=Broker,brokerName=localhost,destinationType=Queue,destinationName=" + Inputs.jsonEscape(Inputs.requireId(arg(ctx, "queue"))) + "\",\"operation\":\"purge\"}")));
        return tools;
    }

    @Override
    public List<ResourceDef> resources(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        return List.of(new ResourceDef(
                "activemq://status",
                "activemq-status",
                "application/json",
                ctx -> client.get("/api/jolokia/read/org.apache.activemq:type=Broker,brokerName=localhost"),
                true));
    }

    @Override
    public Set<String> egressAllowHosts(GatewayConfig cfg) {
        try {
            String host = URI.create(baseUrl(cfg)).getHost();
            return host == null || host.isBlank() ? Set.of() : Set.of(host);
        } catch (Exception e) {
            return Set.of();
        }
    }

    static String baseUrl(GatewayConfig cfg) {
        return cfg.adapterProperty("activemq.url",
                cfg.adapterProperty("ACTIVEMQ_API_URL", "http://localhost:8161"));
    }

    private static ToolDef tool(String name, ToolClass cls, String desc, String schema,
                                Function<CallContext, String> backend) {
        return new ToolDef(name, cls, desc, schema, backend);
    }

    private static String arg(CallContext ctx, String key) {
        Map<String, Object> args = ctx.arguments();
        if (args == null) {
            return null;
        }
        Object v = args.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
