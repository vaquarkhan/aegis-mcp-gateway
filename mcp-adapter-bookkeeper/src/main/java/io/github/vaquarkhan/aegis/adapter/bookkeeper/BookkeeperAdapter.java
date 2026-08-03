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
package io.github.vaquarkhan.aegis.adapter.bookkeeper;

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
 * Apache BookKeeper adapter. Talks to the configured HTTP/REST surface; failures propagate for the breaker.
 *
 * @author Viquar Khan
 */
public final class BookkeeperAdapter implements EngineAdapter {

    @Override
    public String engineId() {
        return "bookkeeper";
    }

    @Override
    public String taxonomyClass() {
        return "logstore";
    }

    @Override
    public List<ToolDef> tools(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        List<ToolDef> tools = new ArrayList<>();
        tools.add(tool("bookie_info", ToolClass.READ, "BookKeeper bookie info",
                "{\"type\":\"object\",\"properties\":{}}",
                ctx -> client.get("/api/v1/bookie/info")));
        tools.add(tool("list_bookies", ToolClass.READ, "List BookKeeper bookies",
                "{\"type\":\"object\",\"properties\":{}}",
                ctx -> client.get("/api/v1/bookie/list_bookies")));
        tools.add(tool("list_ledgers", ToolClass.READ, "List ledgers",
                "{\"type\":\"object\",\"properties\":{}}",
                ctx -> client.get("/api/v1/ledger/list")));
        tools.add(tool("delete_ledger", ToolClass.DESTRUCTIVE, "Delete a ledger",
                "{\"type\":\"object\",\"properties\":{\"ledgerId\":{\"type\":\"string\"},\"approvalToken\":{\"type\":\"string\"}},\"required\":[\"ledgerId\",\"approvalToken\"]}",
                ctx -> client.delete("/api/v1/ledger/delete?ledger_id=" + Inputs.requireId(arg(ctx, "ledgerId")))));
        return tools;
    }

    @Override
    public List<ResourceDef> resources(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        return List.of(new ResourceDef(
                "bookkeeper://status",
                "bookkeeper-status",
                "application/json",
                ctx -> client.get("/api/v1/bookie/info"),
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
        return cfg.adapterProperty("bookkeeper.url",
                cfg.adapterProperty("BOOKKEEPER_HTTP_URL", "http://localhost:8000"));
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
