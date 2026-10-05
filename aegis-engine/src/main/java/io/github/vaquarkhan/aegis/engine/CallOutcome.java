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
package io.github.vaquarkhan.aegis.engine;

import io.github.vaquarkhan.aegis.engine.model.ControlTokens;
import io.github.vaquarkhan.aegis.engine.model.Decision;
import io.github.vaquarkhan.aegis.engine.model.ToolResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of governing one {@code tools/call}.
 *
 * <p>Denials are rendered as an MCP tool result with {@code isError: true} (not a JSON-RPC protocol
 * error), so the agent sees the reason, including the approval request id for
 * {@code APPROVAL_REQUIRED}, and can act on it. Machine-readable details are in {@code _meta}
 * under the {@code io.github.vaquarkhan.aegis/} prefix; {@code structuredContent} is not used for
 * denials because it must conform to the tool's own output schema.
 *
 * @param requestId   correlation id
 * @param decision    ALLOWED or the first denial
 * @param result      backend result (guarded) when allowed; null when denied
 * @param governance  governance metadata (receipt, approval request, redaction counts)
 */
public record CallOutcome(String requestId, Decision decision, ToolResult result, Map<String, Object> governance) {

    public CallOutcome {
        governance = governance == null ? Map.of() : Map.copyOf(governance);
    }

    public boolean allowed() {
        return decision.allowed();
    }

    /** MCP {@code CallToolResult} body (without the 2026 {@code resultType}, added by the protocol layer). */
    public Map<String, Object> toMcpResult() {
        Map<String, Object> body;
        if (decision.allowed()) {
            body = new LinkedHashMap<>(result.body());
        } else {
            body = new LinkedHashMap<>();
            String text = "Aegis denied this call: " + decision.code() + " (step " + decision.step() + "). "
                    + decision.reason();
            Object approvalId = decision.data().get("approvalRequestId");
            if (approvalId != null) {
                text += " Approval request " + approvalId + " is pending; retry with the approval token once approved.";
            }
            body.put("content", List.of(Map.of("type", "text", "text", text)));
            body.put("isError", true);
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        Object existing = body.get("_meta");
        if (existing instanceof Map<?, ?> em) {
            for (Map.Entry<?, ?> e : em.entrySet()) {
                meta.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("outcome", decision.auditOutcome());
        d.put("requestId", requestId);
        if (!decision.allowed()) {
            d.put("code", decision.code());
            d.put("step", (long) decision.step());
            d.put("reason", decision.reason());
            d.putAll(decision.data());
        }
        d.putAll(governance);
        meta.put(ControlTokens.META_PREFIX + "decision", d);
        body.put("_meta", meta);
        return body;
    }
}
