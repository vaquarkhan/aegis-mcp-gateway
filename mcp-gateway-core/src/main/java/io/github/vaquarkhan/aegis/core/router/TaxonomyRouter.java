/*
 * Licensed to the Aegis MCP Gateway project under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vaquarkhan.aegis.core.router;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps a registered tool name back to the engine that contributed it and to that engine's coarse
 * taxonomy class.
 *
 * <p>The gateway never dispatches on engine identity for governance decisions. This mapping exists
 * for routing, audit enrichment and dashboards, which is why it is a plain lookup rather than part
 * of the interceptor chain.
 *
 * @author Viquar Khan
 */
public final class TaxonomyRouter {

    private final Map<String, String> engineByTool = new ConcurrentHashMap<>();
    private final Map<String, String> taxonomyByEngine = new ConcurrentHashMap<>();

    public void register(String toolName, String engineId, String taxonomyClass) {
        if (toolName == null || toolName.isBlank() || engineId == null || engineId.isBlank()) {
            throw new IllegalArgumentException("toolName and engineId required");
        }
        engineByTool.put(toolName, engineId);
        taxonomyByEngine.put(engineId, taxonomyClass == null ? "unknown" : taxonomyClass);
    }

    public Optional<String> engineFor(String toolName) {
        return Optional.ofNullable(engineByTool.get(toolName));
    }

    public Optional<String> taxonomyFor(String toolName) {
        String engine = engineByTool.get(toolName);
        return engine == null ? Optional.empty() : Optional.ofNullable(taxonomyByEngine.get(engine));
    }

    public Set<String> toolsFor(String engineId) {
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : engineByTool.entrySet()) {
            if (e.getValue().equals(engineId)) {
                out.add(e.getKey());
            }
        }
        return Collections.unmodifiableSet(out);
    }

    public Set<String> engines() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(taxonomyByEngine.keySet()));
    }

    public Map<String, String> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(engineByTool));
    }

    public int size() {
        return engineByTool.size();
    }
}
