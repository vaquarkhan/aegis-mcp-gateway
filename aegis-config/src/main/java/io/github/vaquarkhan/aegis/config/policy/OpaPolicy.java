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
package io.github.vaquarkhan.aegis.config.policy;

import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.json.JsonLimits;
import io.github.vaquarkhan.aegis.engine.json.JsonValues;
import io.github.vaquarkhan.aegis.engine.model.Action;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.spi.PolicyDecisionPoint;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Open Policy Agent decision via its REST Data API ({@code POST /v1/data/<package>/<rule>} with
 * {@code {"input": ...}}). Fixes the 0.1 integration: the input is the full canonical action
 * (tool, class, source, resources, argument digest, and optionally the arguments); there is no
 * per caller+tool cache; and the answer is parsed strictly. Only {@code {"result": true}} or
 * {@code {"result": {"allow": true}}} allows; anything else (missing, non-boolean, HTTP error,
 * timeout) denies.
 */
public final class OpaPolicy implements PolicyDecisionPoint {

    private final URI url;
    private final Duration timeout;
    private final boolean includeArguments;
    private final Map<String, String> headers;
    private final HttpClient http;

    public OpaPolicy(URI url, Duration timeout, boolean includeArguments, Map<String, String> headers) {
        this.url = url;
        this.timeout = timeout;
        this.includeArguments = includeArguments;
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public Result decide(CallerIdentity caller, Action action, Map<String, Object> backendArguments) throws Exception {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("actor", caller.actor());
        c.put("effectiveSubject", caller.effectiveSubject());
        c.put("groups", caller.groups().stream().sorted().toList());
        c.put("issuer", caller.issuer());
        c.put("actorChain", caller.actorChain());
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("tool", action.tool());
        a.put("toolClass", action.toolClass().name());
        a.put("source", action.sourceId());
        a.put("resources", action.resources());
        a.put("argsDigest", action.argsDigest());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("caller", c);
        input.put("action", a);
        if (includeArguments) {
            input.put("arguments", backendArguments);
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(url).timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(Json.writeBytes(Map.of("input", input))));
        headers.forEach(b::header);
        // Total deadline covers the body too; a slow OPA cannot stall callers past the timeout.
        io.github.vaquarkhan.aegis.mcp.http.BoundedHttp.Result resp =
                io.github.vaquarkhan.aegis.mcp.http.BoundedHttp.send(http, b.build(), 64 * 1024, timeout.toMillis());
        if (resp.status() != 200) {
            return Result.deny("OPA returned HTTP " + resp.status());
        }
        Map<String, Object> m = Json.parseObject(resp.body(), JsonLimits.DEFAULT.withMaxBytes(64 * 1024));
        Object result = m.get("result");
        if (Boolean.TRUE.equals(result)) {
            return Result.allow("allowed by OPA");
        }
        Map<String, Object> obj = JsonValues.asObject(result);
        if (obj != null && Boolean.TRUE.equals(obj.get("allow"))) {
            return Result.allow("allowed by OPA");
        }
        String reason = obj == null ? null : JsonValues.string(obj, "reason");
        return Result.deny(reason == null ? "denied by OPA" : "denied by OPA: " + reason);
    }
}
