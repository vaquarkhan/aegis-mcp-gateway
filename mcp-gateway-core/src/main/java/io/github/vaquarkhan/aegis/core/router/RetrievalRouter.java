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

import io.github.vaquarkhan.aegis.core.spi.ToolDef;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Optional intent based pruning of an already authorized tool manifest, as in LLD section 7.
 *
 * <p>This narrows a manifest, it never widens one. The router is handed the set a caller is already
 * allowed to see and returns a subset that matches the intent hint, so a routing bug can cost a
 * model some recall but can never grant it a tool that exposure and scope withheld.
 *
 * <p>The 0.1.0 implementation is a deliberately simple token overlap over tool names, descriptions
 * and taxonomy classes. TODO: embedding based retrieval with a relevance floor and a ttl cache
 * hint, per LLD section 7.
 *
 * @author Viquar Khan
 */
public final class RetrievalRouter {

    /** Minimum number of tools to return when the hint matches almost nothing. */
    private static final int MIN_RESULTS = 1;

    private final TaxonomyRouter taxonomy;

    public RetrievalRouter(TaxonomyRouter taxonomy) {
        this.taxonomy = taxonomy;
    }

    /**
     * Prunes an authorized manifest to the tools that plausibly serve the intent.
     *
     * @param authorized tools the caller is already permitted to see
     * @param intentHint free text hint from the client, may be {@code null} or blank
     * @return a subset of {@code authorized}, never wider, never {@code null}
     */
    public List<ToolDef> prune(Map<String, ToolDef> authorized, String intentHint) {
        if (authorized == null || authorized.isEmpty()) {
            return List.of();
        }
        List<ToolDef> all = new ArrayList<>(authorized.values());
        if (intentHint == null || intentHint.isBlank()) {
            return Collections.unmodifiableList(all);
        }
        String[] terms = intentHint.toLowerCase(Locale.ROOT).split("[^a-z0-9_]+");
        List<ToolDef> matched = new ArrayList<>();
        for (Map.Entry<String, ToolDef> entry : authorized.entrySet()) {
            if (matches(entry.getKey(), entry.getValue(), terms)) {
                matched.add(entry.getValue());
            }
        }
        return Collections.unmodifiableList(matched.size() < MIN_RESULTS ? all : matched);
    }

    private boolean matches(String toolName, ToolDef def, String[] terms) {
        StringBuilder haystack = new StringBuilder()
                .append(toolName.toLowerCase(Locale.ROOT))
                .append(' ')
                .append(def.description() == null ? "" : def.description().toLowerCase(Locale.ROOT));
        if (taxonomy != null) {
            taxonomy.taxonomyFor(toolName)
                    .ifPresent(t -> haystack.append(' ').append(t.toLowerCase(Locale.ROOT)));
            taxonomy.engineFor(toolName)
                    .ifPresent(e -> haystack.append(' ').append(e.toLowerCase(Locale.ROOT)));
        }
        String text = haystack.toString();
        for (String term : terms) {
            if (term.length() > 2 && text.contains(term)) {
                return true;
            }
        }
        return false;
    }
}
