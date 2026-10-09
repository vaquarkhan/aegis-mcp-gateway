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
package io.github.vaquarkhan.aegis.engine.model;

import java.util.Map;

/**
 * A {@code tools/call} as received from the client, before governance.
 *
 * @param requestId  JSON-RPC id rendered as text (for audit correlation)
 * @param toolName   {@code params.name}
 * @param arguments  {@code params.arguments} exactly as sent (may contain 0.1 control arguments)
 * @param meta       {@code params._meta} (may carry 2.0 control values)
 * @param protocolVersion MCP protocol version the client is speaking for this request
 * @param roundTrip  2026-07-28 Multi Round-Trip retry fields ({@code inputResponses},
 *                   {@code requestState}) and the client's declared capabilities; empty otherwise
 */
public record ToolCall(
        String requestId,
        String toolName,
        Map<String, Object> arguments,
        Map<String, Object> meta,
        String protocolVersion,
        RoundTrip roundTrip) {

    public ToolCall(String requestId, String toolName, Map<String, Object> arguments, Map<String, Object> meta,
                    String protocolVersion) {
        this(requestId, toolName, arguments, meta, protocolVersion, RoundTrip.NONE);
    }

    public ToolCall {
        requestId = requestId == null ? "" : requestId;
        arguments = arguments == null ? Map.of() : arguments;
        meta = meta == null ? Map.of() : meta;
        protocolVersion = protocolVersion == null ? "" : protocolVersion;
        roundTrip = roundTrip == null ? RoundTrip.NONE : roundTrip;
    }
}
