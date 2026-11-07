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

import java.util.List;
import java.util.TreeSet;

/**
 * The canonical description of what a call would do. Policy, approval, dry-run receipts and audit
 * all bind to this one value, so "what was approved" and "what executed" cannot drift apart.
 *
 * @param tool                 exposed tool name
 * @param toolClass            effective authority class
 * @param sourceId             configured source (backend) id
 * @param resources            typed resources extracted by configured JSON pointers, e.g.
 *                             {@code iceberg:table:sales.orders}; empty when the tool governs no resource
 * @param argsDigest           {@code sha256:} digest of the canonical backend arguments (control values excluded)
 * @param requiredActionScopes exact verified OAuth grants required by trusted operator configuration
 */
public record Action(String tool, ToolClass toolClass, String sourceId, List<String> resources, String argsDigest,
                     List<String> requiredActionScopes) {

    public Action {
        resources = resources == null ? List.of() : List.copyOf(resources);
        requiredActionScopes = requiredActionScopes == null ? List.of()
                : List.copyOf(new TreeSet<>(requiredActionScopes));
    }

    /** Compatibility constructor for actions without configured action scopes. */
    public Action(String tool, ToolClass toolClass, String sourceId, List<String> resources, String argsDigest) {
        this(tool, toolClass, sourceId, resources, argsDigest, List.of());
    }

    /**
     * The digest approvals and dry-run receipts are bound to: every field of this action plus the
     * gateway that governs it and the pinned definition of the tool. A token minted for this binding
     * is useless on another gateway sharing the signing key, for another source or resource set, at
     * another class, after action-scope requirements change, or after the tool definition changes.
     *
     * @param gatewayName       the governing gateway's configured name
     * @param definitionDigest  the catalog digest of the tool definition in force, or empty
     */
    public String binding(String gatewayName, String definitionDigest) {
        java.util.Map<String, Object> b = new java.util.LinkedHashMap<>();
        b.put("gateway", gatewayName == null ? "" : gatewayName);
        b.put("tool", tool);
        b.put("class", toolClass == null ? "" : toolClass.name());
        b.put("source", sourceId == null ? "" : sourceId);
        b.put("resources", resources);
        b.put("args", argsDigest);
        if (!requiredActionScopes.isEmpty()) {
            // Omitted when empty so bindings of unscoped tools keep their pre-action-scope digest.
            b.put("actionScopes", requiredActionScopes);
        }
        b.put("definition", definitionDigest == null ? "" : definitionDigest);
        return Digests.canonicalSha256(b);
    }
}
