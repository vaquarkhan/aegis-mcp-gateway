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
package io.github.vaquarkhan.aegis.mcp.client;

import io.github.vaquarkhan.aegis.engine.json.JsonValues;
import io.github.vaquarkhan.aegis.engine.model.ToolDescriptor;
import io.github.vaquarkhan.aegis.engine.model.ToolResult;
import io.github.vaquarkhan.aegis.engine.spi.ExecutionContext;
import io.github.vaquarkhan.aegis.engine.spi.ToolExecutor;
import io.github.vaquarkhan.aegis.engine.spi.ToolSource;
import io.github.vaquarkhan.aegis.mcp.protocol.JsonRpc;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Governs an EXISTING MCP server without changing it: its tools are discovered, pinned and
 * exposed through the gateway, and every call is proxied only after governance passes.
 * This is how a project that already has an MCP server "hooks in": configuration only.
 */
public final class McpProxyToolSource implements ToolSource {

    private final String id;
    private final McpClient client;
    private final Supplier<Map<String, String>> discoveryCredentials;
    private final long discoveryTimeoutMillis;

    /**
     * @param discoveryCredentials headers for tools/list at startup and refresh (a gateway service
     *                             identity, never a caller's token)
     */
    public McpProxyToolSource(String id, McpClient client, Supplier<Map<String, String>> discoveryCredentials,
                              long discoveryTimeoutMillis) {
        this.id = id;
        this.client = client;
        this.discoveryCredentials = discoveryCredentials == null ? Map::of : discoveryCredentials;
        this.discoveryTimeoutMillis = discoveryTimeoutMillis <= 0 ? 10_000 : discoveryTimeoutMillis;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public List<SourcedTool> discover() throws Exception {
        List<SourcedTool> out = new ArrayList<>();
        for (Map<String, Object> t : client.listTools(discoveryCredentials.get(), discoveryTimeoutMillis)) {
            out.add(new SourcedTool(descriptor(t), new ProxyExecutor(client)));
        }
        return out;
    }

    /** MCP Tool object to descriptor. Fields are copied as advertised so the digest covers them. */
    public static ToolDescriptor descriptor(Map<String, Object> t) {
        return new ToolDescriptor(
                JsonValues.string(t, "name"),
                JsonValues.string(t, "title"),
                JsonValues.string(t, "description"),
                JsonValues.object(t, "inputSchema"),
                JsonValues.object(t, "outputSchema"),
                JsonValues.object(t, "annotations"));
    }

    @Override
    public void close() {
        client.close();
    }

    /** Proxies one governed call to the downstream server. */
    static final class ProxyExecutor implements ToolExecutor {
        private final McpClient client;

        ProxyExecutor(McpClient client) {
            this.client = client;
        }

        @Override
        public List<URI> egressTargets(Map<String, Object> backendArguments) {
            return client.endpoint() == null ? List.of() : List.of(client.endpoint());
        }

        @Override
        public boolean dispatchesByUpstreamName() {
            return true; // tools/call carries the upstream name from the ExecutionContext
        }

        @Override
        public ToolResult execute(ExecutionContext ctx) throws ExecutionException {
            try {
                // Trace context travels in _meta (every era and transport) and as the HTTP header.
                Map<String, Object> params = new java.util.LinkedHashMap<>();
                params.put("name", ctx.upstreamToolName());
                params.put("arguments", ctx.arguments());
                Map<String, Object> meta = new java.util.LinkedHashMap<>();
                meta.put("traceparent", ctx.traceparent());
                if (!ctx.roundTrip().clientCapabilities().isEmpty()) {
                    // MRTR: tell a 2026 backend what the real client can answer (elicitation, sampling, roots).
                    meta.put(io.github.vaquarkhan.aegis.mcp.protocol.McpVersions.META_CLIENT_CAPABILITIES,
                            ctx.roundTrip().clientCapabilities());
                }
                params.put("_meta", meta);
                ctx.roundTrip().addTo(params);
                Map<String, String> headers = new java.util.LinkedHashMap<>(ctx.credentials());
                headers.put("traceparent", ctx.traceparent());
                return new ToolResult(client.call("tools/call", params, ctx.upstreamToolName(), headers, ctx.timeoutMillis()));
            } catch (McpClient.McpCallException e) {
                Integer code = e.rpcCode();
                if (code != null && (code == JsonRpc.INVALID_PARAMS || code == JsonRpc.METHOD_NOT_FOUND)) {
                    // The backend rejected the request itself; that is not a backend outage.
                    return ToolResult.text("Backend rejected the call: " + e.getMessage(), true);
                }
                throw new ExecutionException(e.getMessage(), e.timeout(), e);
            }
        }
    }
}
