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
package io.github.vaquarkhan.aegis.mcp.server;

import io.github.vaquarkhan.aegis.engine.json.JsonLimits;
import io.github.vaquarkhan.aegis.mcp.protocol.McpVersions;
import java.util.Set;

/**
 * MCP server-side options for the gateway endpoint.
 *
 * @param serverName      advertised serverInfo.name
 * @param serverVersion   advertised serverInfo.version
 * @param enabledVersions protocol revisions accepted (default: all four)
 * @param legacySessions  issue and require Mcp-Session-Id for 2025-era clients (stateful mode);
 *                        false serves 2025-era clients without sessions (stateless legacy)
 * @param allowedOrigins  browser Origins accepted; any other present Origin gets 403 (DNS-rebinding defence)
 * @param toolsListTtlMs  ttlMs advertised on 2026 tools/list results (cacheScope is always "private",
 *                        because the list is filtered per caller)
 * @param instructions    optional server instructions
 * @param limits          inbound JSON limits
 */
public record ServerOptions(
        String serverName,
        String serverVersion,
        Set<String> enabledVersions,
        boolean legacySessions,
        Set<String> allowedOrigins,
        long toolsListTtlMs,
        String instructions,
        JsonLimits limits) {

    public ServerOptions {
        serverName = serverName == null ? "aegis-gateway" : serverName;
        serverVersion = serverVersion == null ? "2.0.0-SNAPSHOT" : serverVersion;
        enabledVersions = enabledVersions == null || enabledVersions.isEmpty() ? McpVersions.ALL : Set.copyOf(enabledVersions);
        for (String v : enabledVersions) {
            if (!McpVersions.ALL.contains(v)) {
                throw new IllegalArgumentException("unsupported MCP protocol version in configuration: " + v);
            }
        }
        allowedOrigins = allowedOrigins == null ? Set.of() : Set.copyOf(allowedOrigins);
        limits = limits == null ? JsonLimits.DEFAULT : limits;
        toolsListTtlMs = Math.max(0, toolsListTtlMs);
    }

    public static ServerOptions defaults() {
        return new ServerOptions(null, null, null, true, null, 0, null, null);
    }
}
