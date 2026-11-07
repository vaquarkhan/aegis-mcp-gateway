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
package io.github.vaquarkhan.aegis.mcp.protocol;

import java.util.List;
import java.util.Set;

/**
 * MCP protocol revisions Aegis speaks, and the per-request metadata keys of the modern era.
 *
 * <p>Modern (2026-07-28): no initialize handshake and no Mcp-Session-Id; every request carries its
 * version in {@code params._meta["io.modelcontextprotocol/protocolVersion"]} (SEP-2575), mirrored
 * in the {@code MCP-Protocol-Version} header. Legacy (2025-03-26 .. 2025-11-25): initialize +
 * optional server-assigned {@code Mcp-Session-Id}. Source: modelcontextprotocol.io specification
 * pages for 2026-07-28 (changelog, versioning, Streamable HTTP) and 2025-11-25.
 */
public final class McpVersions {

    private McpVersions() {}

    public static final String V2026_07_28 = "2026-07-28";
    public static final String V2025_11_25 = "2025-11-25";
    public static final String V2025_06_18 = "2025-06-18";
    public static final String V2025_03_26 = "2025-03-26";

    public static final List<String> MODERN = List.of(V2026_07_28);
    /** Newest first. */
    public static final List<String> LEGACY = List.of(V2025_11_25, V2025_06_18, V2025_03_26);
    public static final Set<String> ALL = Set.of(V2026_07_28, V2025_11_25, V2025_06_18, V2025_03_26);

    /** Spec: a request without MCP-Protocol-Version MAY be treated as 2025-03-26. */
    public static final String ASSUMED_WHEN_HEADER_MISSING = V2025_03_26;

    public static final String META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion";
    public static final String META_CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities";
    public static final String META_CLIENT_INFO = "io.modelcontextprotocol/clientInfo";
    public static final String META_SERVER_INFO = "io.modelcontextprotocol/serverInfo";

    public static final String HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version";
    public static final String HEADER_SESSION_ID = "Mcp-Session-Id";
    public static final String HEADER_METHOD = "Mcp-Method";
    public static final String HEADER_NAME = "Mcp-Name";

    public static boolean isModern(String v) {
        return MODERN.contains(v);
    }

    public static boolean isLegacy(String v) {
        return LEGACY.contains(v);
    }
}
