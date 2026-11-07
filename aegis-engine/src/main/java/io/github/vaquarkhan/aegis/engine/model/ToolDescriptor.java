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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A tool definition exactly as its source advertises it (an MCP {@code Tool} object). This is the
 * untrusted, upstream-controlled half of a tool; the operator-controlled half (class, resources,
 * dry-run mode) lives in {@link GovernedTool}.
 *
 * @param name         tool name as exposed to clients
 * @param title        optional display title
 * @param description  description; this is the text the model reads, so it is covered by the digest
 * @param inputSchema  JSON Schema for arguments (object)
 * @param outputSchema optional JSON Schema for structured output
 * @param annotations  optional MCP annotations (hints only, never trusted to lower authority)
 */
public record ToolDescriptor(
        String name,
        String title,
        String description,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        Map<String, Object> annotations) {

    public ToolDescriptor {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool name must be present");
        }
        description = description == null ? "" : description;
        inputSchema = inputSchema == null ? Map.of("type", "object") : inputSchema;
    }

    /**
     * Digest over every field a model or client can observe. A rug-pull that changes only the
     * description (the classic tool-poisoning move, OWASP MCP Top 10 MCP03) changes this digest.
     */
    public String definitionDigest() {
        return Digests.canonicalSha256(toMcpMap());
    }

    /** MCP wire form of the tool, fields omitted when absent. */
    public Map<String, Object> toMcpMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        if (title != null) {
            m.put("title", title);
        }
        m.put("description", description);
        m.put("inputSchema", inputSchema);
        if (outputSchema != null) {
            m.put("outputSchema", outputSchema);
        }
        if (annotations != null && !annotations.isEmpty()) {
            m.put("annotations", annotations);
        }
        return m;
    }

    public ToolDescriptor withName(String newName) {
        return new ToolDescriptor(newName, title, description, inputSchema, outputSchema, annotations);
    }

    public ToolDescriptor withInputSchema(Map<String, Object> schema) {
        return new ToolDescriptor(name, title, description, schema, outputSchema, annotations);
    }
}
