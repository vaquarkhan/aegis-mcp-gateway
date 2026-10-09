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
package io.github.vaquarkhan.aegis.engine.content;

import java.util.List;
import java.util.Set;

/**
 * Operator settings for the resources, prompts and completion of one source. All are OFF unless
 * configured: an existing MCP server's resources and prompts are never exposed by accident.
 *
 * @param sourceId             source id
 * @param namePrefix           prefix for exposed prompt names (same as the source's tool prefix)
 * @param resourcesEnabled     expose resources and resource templates
 * @param uriPatterns          resource URI globs this source serves (required when resources are enabled);
 *                             a read is routed to the single source whose patterns match
 * @param resourceGroups       groups allowed to see and read resources (empty = any authenticated caller)
 * @param allowBinaryResources pass {@code blob} resource contents (opaque to redaction); default false
 * @param promptsEnabled       expose prompts
 * @param promptPatterns       upstream prompt name globs to expose (empty = all)
 * @param promptGroups         groups allowed to see and get prompts (empty = any authenticated caller)
 * @param allowBinaryPrompts   pass image/audio/blob parts inside prompt messages; default false
 * @param completionsEnabled   forward {@code completion/complete} for this source's prompts and resource templates
 * @param timeoutMillis        backend budget per request
 */
public record ContentSettings(String sourceId, String namePrefix, boolean resourcesEnabled, List<String> uriPatterns,
                              Set<String> resourceGroups, boolean allowBinaryResources, boolean promptsEnabled,
                              List<String> promptPatterns, Set<String> promptGroups, boolean allowBinaryPrompts,
                              boolean completionsEnabled, long timeoutMillis) {

    public ContentSettings {
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("content settings need a source id");
        }
        namePrefix = namePrefix == null ? "" : namePrefix;
        uriPatterns = uriPatterns == null ? List.of() : List.copyOf(uriPatterns);
        resourceGroups = resourceGroups == null ? Set.of() : Set.copyOf(resourceGroups);
        promptPatterns = promptPatterns == null ? List.of() : List.copyOf(promptPatterns);
        promptGroups = promptGroups == null ? Set.of() : Set.copyOf(promptGroups);
        timeoutMillis = timeoutMillis <= 0 ? 30_000 : timeoutMillis;
        if (resourcesEnabled && uriPatterns.isEmpty()) {
            throw new IllegalArgumentException("source " + sourceId + ": resources need at least one uriPatterns entry");
        }
        if (completionsEnabled && !resourcesEnabled && !promptsEnabled) {
            throw new IllegalArgumentException("source " + sourceId + ": completions need resources or prompts enabled");
        }
    }

    /** True when this source offers anything beyond tools. */
    public boolean anyEnabled() {
        return resourcesEnabled || promptsEnabled;
    }
}
