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
import io.github.vaquarkhan.aegis.engine.spi.ContentSource;
import io.github.vaquarkhan.aegis.engine.spi.ToolExecutor.ExecutionException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resources, resource templates, prompts and completion of an existing MCP server, exposed to the
 * engine as a {@link ContentSource}. Pagination is followed (bounded); the gateway returns merged,
 * governed lists without cursors. The trace context is forwarded in {@code _meta.traceparent} (all
 * eras and transports) and, over HTTP, as the {@code traceparent} header.
 */
public final class McpContentSource implements ContentSource {

    private static final int MAX_PAGES = 50;

    private final String id;
    private final McpClient client;

    public McpContentSource(String id, McpClient client) {
        this.id = id;
        this.client = client;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public URI endpoint() {
        return client.endpoint();
    }

    @Override
    public List<Map<String, Object>> listResources(Request request) throws ExecutionException {
        return paged("resources/list", "resources", request);
    }

    @Override
    public List<Map<String, Object>> listResourceTemplates(Request request) throws ExecutionException {
        return paged("resources/templates/list", "resourceTemplates", request);
    }

    @Override
    public Map<String, Object> readResource(String uri, Request request) throws ExecutionException {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("uri", uri);
        return call("resources/read", p, uri, request);
    }

    @Override
    public List<Map<String, Object>> listPrompts(Request request) throws ExecutionException {
        return paged("prompts/list", "prompts", request);
    }

    @Override
    public Map<String, Object> getPrompt(String name, Map<String, Object> arguments, Request request) throws ExecutionException {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        if (arguments != null && !arguments.isEmpty()) {
            p.put("arguments", arguments);
        }
        return call("prompts/get", p, name, request);
    }

    @Override
    public Map<String, Object> complete(Map<String, Object> params, Request request) throws ExecutionException {
        return call("completion/complete", new LinkedHashMap<>(params), null, request);
    }

    private Map<String, Object> call(String method, Map<String, Object> params, String name, Request request)
            throws ExecutionException {
        Map<String, String> headers = new LinkedHashMap<>(request.credentials());
        Map<String, Object> meta = new LinkedHashMap<>();
        if (request.traceparent() != null) {
            meta.put("traceparent", request.traceparent());
            headers.put("traceparent", request.traceparent());
        }
        if (!request.roundTrip().clientCapabilities().isEmpty()) {
            meta.put(io.github.vaquarkhan.aegis.mcp.protocol.McpVersions.META_CLIENT_CAPABILITIES,
                    request.roundTrip().clientCapabilities());
        }
        if (!meta.isEmpty()) {
            params.put("_meta", meta);
        }
        request.roundTrip().addTo(params);
        try {
            return client.call(method, params, name, headers, request.timeoutMillis());
        } catch (McpClient.McpCallException e) {
            throw new ExecutionException(e.getMessage(), e.timeout(), e);
        }
    }

    private List<Map<String, Object>> paged(String method, String key, Request request) throws ExecutionException {
        List<Map<String, Object>> out = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, Object> params = new LinkedHashMap<>();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            Map<String, Object> r = call(method, params, null, request);
            List<Object> items = JsonValues.asList(r.get(key));
            if (items != null) {
                for (Object o : items) {
                    Map<String, Object> m = JsonValues.asObject(o);
                    if (m != null) {
                        out.add(m);
                    }
                }
            }
            cursor = JsonValues.string(r, "nextCursor");
            if (cursor == null || cursor.isEmpty()) {
                return out;
            }
        }
        throw new ExecutionException("downstream " + method + " pagination exceeded " + MAX_PAGES + " pages");
    }
}
