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
package io.github.vaquarkhan.aegis.adapter.airflow;

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
 * Apache Airflow adapter. Talks to the configured HTTP/REST surface; failures propagate for the breaker.
 *
 * @author Viquar Khan
 */
public final class AirflowAdapter implements EngineAdapter {

    @Override
    public String engineId() {
        return "airflow";
    }

    @Override
    public String taxonomyClass() {
        return "orchestration";
    }

    @Override
    public List<ToolDef> tools(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        List<ToolDef> tools = new ArrayList<>();
        tools.add(tool("get_health", ToolClass.READ, "Airflow health",
                "{\"type\":\"object\",\"properties\":{}}",
                ctx -> client.get("/health")));
        tools.add(tool("list_dags", ToolClass.READ, "List DAGs",
                "{\"type\":\"object\",\"properties\":{}}",
                ctx -> client.get("/dags")));
        tools.add(tool("get_dag", ToolClass.READ, "Get a DAG",
                "{\"type\":\"object\",\"properties\":{\"dagId\":{\"type\":\"string\"}},\"required\":[\"dagId\"]}",
                ctx -> client.get("/dags/" + Inputs.requireId(arg(ctx, "dagId")))));
        tools.add(tool("trigger_dag", ToolClass.MUTATE, "Trigger a DAG run",
                "{\"type\":\"object\",\"properties\":{\"dagId\":{\"type\":\"string\"},\"approvalToken\":{\"type\":\"string\"}},\"required\":[\"dagId\",\"approvalToken\"]}",
                ctx -> client.post("/dags/" + Inputs.requireId(arg(ctx, "dagId")) + "/dagRuns", "{}")));
        tools.add(tool("delete_dag", ToolClass.DESTRUCTIVE, "Delete a DAG",
                "{\"type\":\"object\",\"properties\":{\"dagId\":{\"type\":\"string\"},\"approvalToken\":{\"type\":\"string\"}},\"required\":[\"dagId\",\"approvalToken\"]}",
                ctx -> client.delete("/dags/" + Inputs.requireId(arg(ctx, "dagId")))));
        return tools;
    }

    @Override
    public List<ResourceDef> resources(GatewayConfig cfg) {
        HttpJsonClient client = new HttpJsonClient(baseUrl(cfg));
        return List.of(new ResourceDef(
                "airflow://status",
                "airflow-status",
                "application/json",
                ctx -> client.get("/health"),
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
        return cfg.adapterProperty("airflow.url",
                cfg.adapterProperty("AIRFLOW_API_URL", "http://localhost:8080/api/v1"));
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
