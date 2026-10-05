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
package io.github.vaquarkhan.aegis.engine.control;

import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import java.util.List;
import java.util.Set;

/**
 * Step 2 resource scope: which resources a group may touch at all, independent of tool policy.
 * When scopes are configured, every resource of an action must be covered by a scope rule of one
 * of the caller's groups; nothing is widened to {@code *} (0.1 widened a missing resource to *).
 *
 * @param groups    groups this scope applies to ({@code *} = any caller)
 * @param resources resource globs, e.g. {@code iceberg:table:sales.*}
 */
public record ScopeRule(Set<String> groups, List<String> resources) {

    public ScopeRule {
        groups = Set.copyOf(groups);
        resources = List.copyOf(resources);
    }

    /** @return null when every resource is in scope, else the first out-of-scope resource */
    public static String firstOutOfScope(List<ScopeRule> scopes, CallerIdentity caller, List<String> resources) {
        for (String r : resources) {
            boolean covered = false;
            for (ScopeRule s : scopes) {
                if ((s.groups.contains("*") || caller.inAnyGroup(s.groups)) && Glob.anyMatches(s.resources, r)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                return r;
            }
        }
        return null;
    }
}
