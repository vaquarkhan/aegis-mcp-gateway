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
package io.github.vaquarkhan.aegis.adapter.couchdb;

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
 * Apache CouchDB adapter. Talks to the configured HTTP/REST surface; failures propagate for the breaker.
 *
 * @author Viquar Khan
 */
public final class CouchdbAdapter implements EngineAdapter {

    @Override
    public String engineId() {
        return "couchdb";
    }

    @Override
    public String taxonomyClass() {
        return "datastore";
    }

    @Override
    public List<ToolDef> tools(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        List<ToolDef> tools = new ArrayList<>();
        tools.add(tool("list_dbs", ToolClass.READ, "List CouchDB databases",
                "{\"type\":\"object\",\"properties\":{}}",
                ctx -> client.get("/_all_dbs")));
        tools.add(tool("get_db", ToolClass.READ, "Get CouchDB database info",
                "{\"type\":\"object\",\"properties\":{\"db\":{\"type\":\"string\"}},\"required\":[\"db\"]}",
                ctx -> client.get("/" + Inputs.requireId(arg(ctx, "db")))));
        tools.add(tool("create_db", ToolClass.MUTATE, "Create a CouchDB database",
                "{\"type\":\"object\",\"properties\":{\"db\":{\"type\":\"string\"},\"approvalToken\":{\"type\":\"string\"}},\"required\":[\"db\",\"approvalToken\"]}",
                ctx -> client.put("/" + Inputs.requireId(arg(ctx, "db")), "{}")));
        tools.add(tool("delete_db", ToolClass.DESTRUCTIVE, "Delete a CouchDB database",
                "{\"type\":\"object\",\"properties\":{\"db\":{\"type\":\"string\"},\"approvalToken\":{\"type\":\"string\"}},\"required\":[\"db\",\"approvalToken\"]}",
                ctx -> client.delete("/" + Inputs.requireId(arg(ctx, "db")))));
        return tools;
    }

    @Override
    public List<ResourceDef> resources(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        return List.of(new ResourceDef(
                "couchdb://status",
                "couchdb-status",
                "application/json",
                ctx -> client.get("/_all_dbs"),
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
        return cfg.adapterProperty("couchdb.url",
                cfg.adapterProperty("COUCHDB_URL", "http://localhost:5984"));
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
