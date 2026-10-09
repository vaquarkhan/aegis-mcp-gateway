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

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Client for an existing (downstream) MCP server that Aegis governs. Implementations handle era
 * detection (2026-07-28 stateless vs 2025 initialize/session) so adopters only configure a URL or a
 * command.
 */
public interface McpClient extends AutoCloseable {

    /** HTTP endpoint for egress checks; null for stdio subprocess servers. */
    URI endpoint();

    /** All tools (pagination followed, bounded). */
    List<Map<String, Object>> listTools(Map<String, String> headers, long timeoutMillis) throws McpCallException;

    /**
     * Generic request (resources, prompts, completion). The client adds era-specific metadata and
     * the mirrored headers; {@code mcpName} is the value for {@code Mcp-Name} (name or URI) or null.
     *
     * @return the JSON-RPC {@code result} object
     */
    Map<String, Object> call(String method, Map<String, Object> params, String mcpName, Map<String, String> headers,
                             long timeoutMillis) throws McpCallException;

    /** Calls a tool and returns the MCP result object. */
    Map<String, Object> callTool(String name, Map<String, Object> arguments, Map<String, String> headers, long timeoutMillis)
            throws McpCallException;

    @Override
    void close();

    /** Downstream failure. {@code rpcCode} is set when the server returned a JSON-RPC error. */
    final class McpCallException extends Exception {
        private static final long serialVersionUID = 1L;
        private final Integer rpcCode;
        private final boolean timeout;

        public McpCallException(String message, Integer rpcCode, boolean timeout, Throwable cause) {
            super(message, cause);
            this.rpcCode = rpcCode;
            this.timeout = timeout;
        }

        public McpCallException(String message) {
            this(message, null, false, null);
        }

        public Integer rpcCode() {
            return rpcCode;
        }

        public boolean timeout() {
            return timeout;
        }
    }
}
