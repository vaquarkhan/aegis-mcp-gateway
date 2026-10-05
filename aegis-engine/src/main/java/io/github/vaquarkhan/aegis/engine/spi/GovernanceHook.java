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
package io.github.vaquarkhan.aegis.engine.spi;

import io.github.vaquarkhan.aegis.engine.catalog.GovernedTool;
import io.github.vaquarkhan.aegis.engine.model.Action;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.model.Decision;
import io.github.vaquarkhan.aegis.engine.model.ToolResult;
import java.util.Map;

/**
 * Extension point for deployment-specific controls (a PII detector, a data-classification lookup, an
 * organisation's own policy service) without forking the engine.
 *
 * <p>A hook runs for <b>every governed operation</b>, not only tool calls: {@code tools/call},
 * {@code resources/read}, {@code prompts/get} and {@code completion/complete} all pass through the
 * same hooks, because all four put data or instructions into the model's context. Which operations a
 * given hook sees, and whether a violation blocks or is only recorded, is the operator's choice; see
 * {@code io.github.vaquarkhan.aegis.engine.control.HookBinding}.
 *
 * <p>Contract, which keeps the 0.1 guarantees intact:
 * <ul>
 *   <li>hooks run sequentially in configured order, after the built-in steps 1 to 8 and before the
 *       dry-run receipt check and commit, so denial order and audit output are deterministic;</li>
 *   <li>a hook may only deny with an existing registry code ({@code POLICY_DENIED},
 *       {@code INVALID_INPUT} or {@code PROMPT_INJECTION}); anything else is mapped to
 *       {@code POLICY_DENIED};</li>
 *   <li>a hook that throws, returns null, or times out denies the operation (or withholds the
 *       output, for the after phase) when its mode is ENFORCE: fail-closed, never fail-open. In
 *       AUDIT mode the same event is recorded in the audit journal and the operation continues;</li>
 *   <li>a hook must not mutate the context it is given.</li>
 * </ul>
 */
public interface GovernanceHook extends AutoCloseable {

    /** The governed operations a hook can see. */
    enum Operation {
        /** {@code tools/call}. */
        TOOL_CALL,
        /** {@code resources/read}. */
        RESOURCE_READ,
        /** {@code prompts/get}. */
        PROMPT_GET,
        /** {@code completion/complete}. */
        COMPLETION;

        /** True for the three content operations, which return MCP content rather than a tool result. */
        public boolean isContent() {
            return this != TOOL_CALL;
        }
    }

    /** Stable name, used in audit records and denial reasons. */
    String name();

    /**
     * Read-only view of the operation being governed.
     *
     * @param requestId gateway request id (also in audit)
     * @param traceId   W3C trace id of the request
     * @param caller    the governed caller (carries no credential)
     * @param operation which MCP operation this is
     * @param method    exposed tool name for TOOL_CALL, else the MCP method name
     * @param tool      the governed tool for TOOL_CALL; null for content operations
     * @param sourceId  configured backend id
     * @param action    the canonical action: class, typed resources, argument digest
     * @param arguments backend arguments for a tool call; the request parameters for content
     * @param dryRun    true when the engine is previewing a write (never true for content)
     */
    record Context(String requestId, String traceId, CallerIdentity caller, Operation operation, String method,
                   GovernedTool tool, String sourceId, Action action, Map<String, Object> arguments, boolean dryRun) {

        public Context {
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        }
    }

    /** Runs before execution, for every operation this hook is bound to. Return allow to continue. */
    default Decision before(Context context) throws Exception {
        return Decision.allow();
    }

    /**
     * Runs on a tool result before the built-in output guard. Return the (possibly modified) result;
     * returning null or throwing withholds the output in ENFORCE mode.
     */
    default ToolResult afterTool(Context context, ToolResult result) throws Exception {
        return result;
    }

    /**
     * Runs on a resource, prompt or completion result before redaction and the content screen.
     * Return the (possibly modified) MCP result; returning null or throwing denies it in ENFORCE mode.
     */
    default Map<String, Object> afterContent(Context context, Map<String, Object> result) throws Exception {
        return result;
    }

    /** Releases resources (an HTTP client, a connection pool). Called when the gateway closes. */
    @Override
    default void close() {
        // most hooks hold nothing
    }

    /**
     * Denies with an explicit reason. The before phase can return {@link Decision#deny}, but the two
     * after phases return the result itself, so a hook that wants to withhold output <b>and</b> say
     * why throws this instead of returning null. The reason reaches the audit record and the caller's
     * error; the code is mapped through the same small allow list as a before-phase denial.
     */
    class HookDenied extends Exception {

        private static final long serialVersionUID = 1L;

        private final String code;

        public HookDenied(String code, String reason) {
            super(reason == null ? "denied by hook" : reason);
            this.code = code;
        }

        /** Requested deny code; mapped to {@code POLICY_DENIED} when it is not in the allow list. */
        public String code() {
            return code;
        }
    }
}
