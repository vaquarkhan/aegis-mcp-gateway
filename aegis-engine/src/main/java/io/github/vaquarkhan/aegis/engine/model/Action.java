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

/**
 * The canonical description of what a call would do. Policy, approval, dry-run receipts and audit
 * all bind to this one value, so "what was approved" and "what executed" cannot drift apart.
 *
 * @param tool        exposed tool name
 * @param toolClass   effective authority class
 * @param sourceId    configured source (backend) id
 * @param resources   typed resources extracted by configured JSON pointers, e.g.
 *                    {@code iceberg:table:sales.orders}; empty when the tool governs no resource
 * @param argsDigest  {@code sha256:} digest of the canonical backend arguments (control values excluded)
 */
public record Action(String tool, ToolClass toolClass, String sourceId, List<String> resources, String argsDigest) {

    public Action {
        resources = resources == null ? List.of() : List.copyOf(resources);
    }

    /**
     * The digest approvals and dry-run receipts are bound to: every field of this action plus the
     * gateway that governs it and the pinned definition of the tool. A token minted for this binding
     * is useless on another gateway sharing the signing key, for another source or resource set, at
     * another class, or after the tool definition it was granted for has changed.
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
        b.put("definition", definitionDigest == null ? "" : definitionDigest);
        return Digests.canonicalSha256(b);
    }
}
