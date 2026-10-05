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
package io.github.vaquarkhan.aegis.engine.catalog;

import io.github.vaquarkhan.aegis.engine.json.JsonValues;
import io.github.vaquarkhan.aegis.engine.model.ControlTokens;
import io.github.vaquarkhan.aegis.engine.model.ToolClass;
import io.github.vaquarkhan.aegis.engine.model.ToolDescriptor;
import io.github.vaquarkhan.aegis.engine.spi.ToolExecutor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A tool as the gateway governs it: the upstream definition (untrusted) joined with the
 * operator's governance settings (trusted). Built by a {@link ToolBinder} from configuration.
 *
 * @param exposedName   name clients call (upstream name, optionally prefixed by the source)
 * @param sourceId      configured source id
 * @param descriptor    upstream definition; its digest is pinned
 * @param toolClass     effective class; never lower than any hint (see {@link ToolBinder})
 * @param resources     resource extraction rules
 * @param dryRun        dry-run mode used for the validate phase of VRP
 * @param allowedGroups when non-empty, only these groups may see or call the tool (a per-tool
 *                      allow list, like the per-topology restriction common in Knox deployments)
 * @param executor      capability that performs the call
 * @param timeoutMillis backend budget for one call
 */
public record GovernedTool(
        String exposedName,
        String sourceId,
        ToolDescriptor descriptor,
        ToolClass toolClass,
        List<ResourceRule> resources,
        DryRunSpec dryRun,
        Set<String> allowedGroups,
        ToolExecutor executor,
        long timeoutMillis) {

    public GovernedTool {
        if (exposedName == null || exposedName.isBlank()) {
            throw new IllegalArgumentException("exposed tool name must be set");
        }
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("source id must be set");
        }
        if (descriptor == null || toolClass == null || executor == null) {
            throw new IllegalArgumentException("descriptor, class and executor must be set for " + exposedName);
        }
        resources = resources == null ? List.of() : List.copyOf(resources);
        dryRun = dryRun == null ? DryRunSpec.GATEWAY : dryRun;
        allowedGroups = allowedGroups == null ? Set.of() : Set.copyOf(allowedGroups);
        timeoutMillis = timeoutMillis <= 0 ? 30_000 : timeoutMillis;
    }

    public String upstreamName() {
        return descriptor.name();
    }

    /** Property names declared by the upstream input schema. */
    public Set<String> declaredProperties() {
        Map<String, Object> props = JsonValues.object(descriptor.inputSchema(), "properties");
        return props == null ? Set.of() : props.keySet();
    }

    /**
     * Wire form for {@code tools/list}. The authority hints are rewritten from the operator's class
     * (the gateway is now the server, and its hints must be truthful); other upstream annotations
     * are kept. For write tools, the 0.1 control arguments are advertised as optional properties so
     * clients that cannot set {@code _meta} can still pass them.
     */
    public Map<String, Object> advertise(boolean advertiseControlArguments) {
        Map<String, Object> m = new LinkedHashMap<>(descriptor.toMcpMap());
        m.put("name", exposedName);
        Map<String, Object> ann = new LinkedHashMap<>();
        if (descriptor.annotations() != null) {
            ann.putAll(descriptor.annotations());
        }
        ann.put("readOnlyHint", toolClass == ToolClass.READ);
        ann.put("destructiveHint", toolClass == ToolClass.DESTRUCTIVE);
        m.put("annotations", ann);
        if (advertiseControlArguments && toolClass.isWrite()) {
            m.put("inputSchema", withControlProperties(descriptor.inputSchema()));
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put(ControlTokens.META_PREFIX + "toolClass", toolClass.name());
        meta.put(ControlTokens.META_PREFIX + "source", sourceId);
        m.put("_meta", meta);
        return m;
    }

    private Map<String, Object> withControlProperties(Map<String, Object> schema) {
        Map<String, Object> s = new LinkedHashMap<>(schema);
        Map<String, Object> props = new LinkedHashMap<>();
        Map<String, Object> existing = JsonValues.object(schema, "properties");
        if (existing != null) {
            props.putAll(existing);
        }
        props.putIfAbsent(ControlTokens.ARG_APPROVAL, Map.of("type", "string",
                "description", "Aegis approval token for this exact call (single use)."));
        if (toolClass == ToolClass.DESTRUCTIVE) {
            props.putIfAbsent(ControlTokens.ARG_DRY_RUN, Map.of("type", "boolean",
                    "description", "Run the Aegis validate phase only and return a dry-run receipt."));
            props.putIfAbsent(ControlTokens.ARG_RECEIPT, Map.of("type", "string",
                    "description", "Aegis dry-run receipt from the validate phase (single use)."));
        }
        s.put("properties", props);
        return s;
    }
}
