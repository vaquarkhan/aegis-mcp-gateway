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
package io.github.vaquarkhan.aegis.knox.rs;

import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.knox.KnoxGatewayRegistry;
import io.github.vaquarkhan.aegis.knox.KnoxIdentityBridge;
import io.github.vaquarkhan.aegis.mcp.http.McpHttpRequest;
import io.github.vaquarkhan.aegis.mcp.http.McpHttpResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

/**
 * Knox native service endpoint for Aegis. Knox has already authenticated the caller, asserted the
 * identity and run the topology's authorization provider (for example AclsAuthz, which gates who
 * may reach this route at all). This resource only translates JAX-RS to the host-neutral request
 * and delegates to the shared engine; per-tool, per-argument governance happens there.
 */
@Path("aegis-mcp/v1")
public class AegisMcpResource {

    private static final int MAX_BODY = 1 << 20;

    @Context
    private HttpServletRequest request;

    @Context
    private ServletContext servletContext;

    @POST
    @Path("mcp")
    public Response post(InputStream body) {
        return mcp("POST", body);
    }

    @GET
    @Path("mcp")
    public Response get() {
        return mcp("GET", null);
    }

    @DELETE
    @Path("mcp")
    public Response delete() {
        return mcp("DELETE", null);
    }

    @GET
    @Path("admin/{path: .*}")
    public Response adminGet(@PathParam("path") String path) {
        return admin("GET", path, null);
    }

    @POST
    @Path("admin/{path: .*}")
    public Response adminPost(@PathParam("path") String path, InputStream body) {
        return admin("POST", path, body);
    }

    @GET
    @Path("health")
    public Response health() {
        KnoxGatewayRegistry.Holder h = holder();
        return h.gateway() == null
                ? json(McpHttpResponse.json(503, Map.of("status", "unavailable", "reason", h.error())))
                : json(McpHttpResponse.json(200, Map.of("status", "ok")));
    }

    /**
     * Prometheus text metrics. Behind the same Knox provider chain as {@code mcp} (authentication,
     * identity assertion, ACLs), so access is whatever the topology grants to this service.
     */
    @GET
    @Path("metrics")
    public Response metrics() {
        KnoxGatewayRegistry.Holder h = holder();
        if (h.gateway() == null || h.gateway().metrics() == null) {
            return json(McpHttpResponse.json(404, Map.of("error", "metrics are not enabled")));
        }
        if (caller() == null) {
            return json(McpHttpResponse.json(401, Map.of("error", "authentication required")));
        }
        return Response.ok(h.gateway().metrics().renderPrometheus().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .header("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
                .header("X-Content-Type-Options", "nosniff").build();
    }

    private Response mcp(String method, InputStream body) {
        KnoxGatewayRegistry.Holder h = holder();
        if (h.gateway() == null) {
            return json(McpHttpResponse.json(503, Map.of("error", "Aegis is not available on this topology")));
        }
        McpHttpRequest req;
        try {
            req = new McpHttpRequest(method, "", headers(), read(body));
        } catch (IOException e) {
            return json(McpHttpResponse.json(413, Map.of("error", "request body too large")));
        }
        return json(h.gateway().mcp().handleHttp(req, caller()));
    }

    private Response admin(String method, String path, InputStream body) {
        KnoxGatewayRegistry.Holder h = holder();
        if (h.gateway() == null) {
            return json(McpHttpResponse.json(503, Map.of("error", "Aegis is not available on this topology")));
        }
        try {
            read(body); // bounded and discarded: admin routes take no body
        } catch (IOException e) {
            return json(McpHttpResponse.json(413, Map.of("error", "request body too large")));
        }
        return json(h.gateway().admin().handle(new McpHttpRequest(method, "/" + path, headers(), null), caller()));
    }

    private KnoxGatewayRegistry.Holder holder() {
        return KnoxGatewayRegistry.get(servletContext, servletContext.getInitParameter(KnoxGatewayRegistry.CONFIG_PARAM));
    }

    private CallerIdentity caller() {
        String ctx = servletContext.getContextPath();
        String topology = ctx == null ? "" : ctx.substring(ctx.lastIndexOf('/') + 1);
        return KnoxIdentityBridge.fromCurrentSubject(topology);
    }

    private Map<String, List<String>> headers() {
        Map<String, List<String>> h = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            h.put(name, Collections.list(request.getHeaders(name)));
        }
        return h;
    }

    private static byte[] read(InputStream in) throws IOException {
        if (in == null) {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) >= 0) {
            total += n;
            if (total > MAX_BODY) {
                throw new IOException("too large");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static Response json(McpHttpResponse r) {
        Response.ResponseBuilder b = Response.status(r.status());
        r.headers().forEach(b::header);
        if (r.body().length > 0) {
            b.entity(r.body());
        }
        return b.build();
    }
}
