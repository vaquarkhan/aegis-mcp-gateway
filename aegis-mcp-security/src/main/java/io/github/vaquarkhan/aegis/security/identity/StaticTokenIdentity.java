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
package io.github.vaquarkhan.aegis.security.identity;

import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.model.Digests;
import io.github.vaquarkhan.aegis.mcp.http.McpHttpRequest;
import java.util.List;

/**
 * Pre-shared bearer tokens mapped to identities, for local development, CI, and service-to-service
 * setups without an identity provider. Only SHA-256 digests of the tokens are held; comparison is
 * constant-time. Use JWT for anything multi-user.
 */
public final class StaticTokenIdentity implements IdentityResolver {

    /** @param sha256Hex hex SHA-256 of the token */
    public record Entry(String sha256Hex, CallerIdentity identity) {}

    private final List<Entry> entries;

    public StaticTokenIdentity(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    @Override
    public CallerIdentity resolve(McpHttpRequest request) {
        String token = IdentityResolver.bearer(request);
        if (token == null) {
            return null;
        }
        String digest = Digests.sha256Hex(token);
        CallerIdentity match = null;
        for (Entry e : entries) {
            // Loop over all entries so timing does not reveal which entry matched.
            if (Digests.constantTimeEquals(e.sha256Hex(), digest)) {
                match = e.identity();
            }
        }
        return match;
    }
}
