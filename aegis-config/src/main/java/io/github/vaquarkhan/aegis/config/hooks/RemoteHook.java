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
package io.github.vaquarkhan.aegis.config.hooks;

import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.json.JsonLimits;
import io.github.vaquarkhan.aegis.engine.json.JsonValues;
import io.github.vaquarkhan.aegis.engine.model.Decision;
import io.github.vaquarkhan.aegis.engine.model.Digests;
import io.github.vaquarkhan.aegis.engine.model.ToolResult;
import io.github.vaquarkhan.aegis.engine.spi.GovernanceHook;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A governance hook that lives in another process: Aegis POSTs a description of the operation to an
 * HTTP endpoint and the endpoint answers allow or deny.
 *
 * <p>This is the no-Java extension path. A team that wants a PII detector, a data-classification
 * lookup or their own policy service does not have to write a Java hook, publish a jar and restart
 * the gateway with a new classpath; they run a small service and name its URL in configuration. It is
 * also how a control written in Python or Go participates in the chain.
 *
 * <p>Wire contract, deliberately tiny so an endpoint cannot become a second policy language:
 * <pre>
 * POST &lt;url&gt;
 * {"aegis":"2.0","phase":"before|afterTool|afterContent","requestId":"...","traceId":"...",
 *  "operation":"TOOL_CALL|RESOURCE_READ|PROMPT_GET|COMPLETION","method":"...","source":"...",
 *  "toolClass":"READ|MUTATE|DESTRUCTIVE","resources":[...],"argsDigest":"...","dryRun":false,
 *  "caller":{"actor":"...","effectiveSubject":"...","groups":[...],"issuer":"...","actorChain":[...]},
 *  "arguments":{...}   // only when includeArguments
 *  "result":{...}      // after phases, only when includeResult
 * }
 *
 * 200 {"allow":true}
 * 200 {"allow":false,"code":"POLICY_DENIED","reason":"free text"}
 * </pre>
 *
 * <p>Fail-closed: a non-200 status, a malformed body, a missing {@code allow}, a body over the limit,
 * a timeout or a connection failure all throw, which the hook runner turns into a denial when this
 * hook is in ENFORCE mode (and into an audit note in AUDIT mode). {@code allow} must be a literal
 * boolean; a truthy string does not allow.
 *
 * <p>Privacy defaults: arguments and results are <b>not</b> sent unless the operator opts in, because
 * the endpoint is a third party from the data's point of view. Without them the endpoint still sees
 * the caller, the operation, the typed resources and the argument digest, which is enough for
 * identity-based and resource-based controls. The endpoint URL is checked against the gateway's
 * egress allow list at startup, so this hook cannot become an unreviewed data path.
 */
public final class RemoteHook implements GovernanceHook {

    /** Which phases the endpoint is consulted in. */
    public enum Phase {
        /** Before execution: the endpoint can deny. */
        BEFORE,
        /** After execution: the endpoint can withhold the output or content. */
        AFTER
    }

    /** Hard ceiling on the response body, independent of the configured value. */
    public static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private final String name;
    private final URI url;
    private final Duration timeout;
    private final Map<String, String> headers;
    private final boolean includeArguments;
    private final boolean includeResult;
    private final int maxResponseBytes;
    private final Set<Phase> phases;
    private final HttpClient http;

    public RemoteHook(String name, URI url, Duration timeout, Map<String, String> headers, boolean includeArguments,
                      boolean includeResult, int maxResponseBytes, Set<Phase> phases) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a remote hook needs a name");
        }
        if (url == null) {
            throw new IllegalArgumentException("a remote hook needs a url");
        }
        this.name = name;
        this.url = url;
        this.timeout = timeout == null ? Duration.ofSeconds(2) : timeout;
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
        this.includeArguments = includeArguments;
        this.includeResult = includeResult;
        this.maxResponseBytes = Math.min(Math.max(maxResponseBytes, 256), MAX_RESPONSE_BYTES);
        this.phases = phases == null || phases.isEmpty() ? Set.copyOf(EnumSet.of(Phase.BEFORE)) : Set.copyOf(phases);
        this.http = HttpClient.newBuilder().connectTimeout(this.timeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public String name() {
        return name;
    }

    /** The endpoint, so the gateway can check it against the egress allow list before serving traffic. */
    public URI endpoint() {
        return url;
    }

    public Set<Phase> phases() {
        return phases;
    }

    @Override
    public Decision before(Context context) throws Exception {
        if (!phases.contains(Phase.BEFORE)) {
            return Decision.allow();
        }
        ask("before", context, null);
        return Decision.allow();
    }

    /**
     * Withholds the output when the endpoint denies. The endpoint cannot rewrite a result: a rewrite
     * would make the gateway's output guard and audit digest describe something the backend never
     * returned.
     */
    @Override
    public ToolResult afterTool(Context context, ToolResult result) throws Exception {
        if (!phases.contains(Phase.AFTER)) {
            return result;
        }
        ask("afterTool", context, result == null ? null : result.body());
        return result;
    }

    @Override
    public Map<String, Object> afterContent(Context context, Map<String, Object> result) throws Exception {
        if (!phases.contains(Phase.AFTER)) {
            return result;
        }
        ask("afterContent", context, result);
        return result;
    }

    /** @throws HookDenied when the endpoint denies, or when its answer cannot be trusted */
    private void ask(String phase, Context context, Map<String, Object> result) throws Exception {
        Map<String, Object> caller = new LinkedHashMap<>();
        caller.put("actor", context.caller().actor());
        caller.put("effectiveSubject", context.caller().effectiveSubject());
        caller.put("groups", context.caller().groups().stream().sorted().toList());
        caller.put("issuer", context.caller().issuer());
        caller.put("actorChain", context.caller().actorChain());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("aegis", "2.0");
        body.put("phase", phase);
        body.put("requestId", context.requestId());
        body.put("traceId", context.traceId());
        body.put("operation", context.operation().name());
        body.put("method", context.method() == null ? "" : context.method());
        body.put("source", context.sourceId() == null ? "" : context.sourceId());
        body.put("toolClass", context.action() == null ? "" : context.action().toolClass().name());
        body.put("resources", context.action() == null ? List.of() : context.action().resources());
        body.put("argsDigest", context.action() == null ? "" : context.action().argsDigest());
        body.put("dryRun", context.dryRun());
        body.put("caller", caller);
        if (includeArguments) {
            body.put("arguments", context.arguments());
        }
        if (result != null) {
            body.put("resultDigest", Digests.canonicalSha256(result));
            if (includeResult) {
                body.put("result", result);
            }
        }

        HttpRequest.Builder b = HttpRequest.newBuilder(url).timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(Json.writeBytes(body)));
        headers.forEach(b::header);
        io.github.vaquarkhan.aegis.mcp.http.BoundedHttp.Result resp =
                io.github.vaquarkhan.aegis.mcp.http.BoundedHttp.send(http, b.build(), maxResponseBytes, timeout.toMillis());
        if (resp.status() != 200) {
            throw new HookDenied(null, "the hook endpoint returned HTTP " + resp.status());
        }
        Map<String, Object> answer = Json.parseObject(resp.body(), JsonLimits.DEFAULT.withMaxBytes(maxResponseBytes));
        Object allow = answer.get("allow");
        if (Boolean.TRUE.equals(allow)) {
            return;
        }
        if (!Boolean.FALSE.equals(allow)) {
            // Neither true nor false: the endpoint did not answer the question that was asked.
            throw new HookDenied(null, "the hook endpoint did not return a boolean 'allow'");
        }
        String reason = JsonValues.string(answer, "reason");
        throw new HookDenied(JsonValues.string(answer, "code"), reason == null ? "denied by " + name : reason);
    }
}
