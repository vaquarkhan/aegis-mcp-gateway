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
package io.github.vaquarkhan.aegis.adapter.pinot;

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
 * Apache Pinot adapter. Talks to the configured HTTP/REST surface; failures propagate for the breaker.
 *
 * @author Viquar Khan
 */
public final class PinotAdapter implements EngineAdapter {

    @Override
    public String engineId() {
        return "pinot";
    }

    @Override
    public String taxonomyClass() {
        return "olap";
    }

    @Override
    public List<ToolDef> tools(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        List<ToolDef> tools = new ArrayList<>();
        tools.add(tool("list_tables", ToolClass.READ, "List Pinot tables",
                "{\"type\":\"object\",\"properties\":{}}",
                ctx -> client.get("/tables")));
        tools.add(tool("get_table", ToolClass.READ, "Get Pinot table config",
                "{\"type\":\"object\",\"properties\":{\"table\":{\"type\":\"string\"}},\"required\":[\"table\"]}",
                ctx -> client.get("/tables/" + Inputs.requireTable(arg(ctx, "table")))));
        tools.add(tool("list_tenants", ToolClass.READ, "List Pinot tenants",
                "{\"type\":\"object\",\"properties\":{}}",
                ctx -> client.get("/tenants")));
        tools.add(tool("delete_table", ToolClass.DESTRUCTIVE, "Delete a Pinot table",
                "{\"type\":\"object\",\"properties\":{\"table\":{\"type\":\"string\"},\"approvalToken\":{\"type\":\"string\"}},\"required\":[\"table\",\"approvalToken\"]}",
                ctx -> client.delete("/tables/" + Inputs.requireTable(arg(ctx, "table")))));
        return tools;
    }

    @Override
    public List<ResourceDef> resources(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        return List.of(new ResourceDef(
                "pinot://status",
                "pinot-status",
                "application/json",
                ctx -> client.get("/tables"),
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
        return cfg.adapterProperty("pinot.url",
                cfg.adapterProperty("PINOT_CONTROLLER_URL", "http://localhost:9000"));
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
