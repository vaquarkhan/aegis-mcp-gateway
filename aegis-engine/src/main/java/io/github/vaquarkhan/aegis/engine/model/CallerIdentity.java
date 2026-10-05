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
import java.util.Objects;
import java.util.Set;

/**
 * Who is calling, as established by the host (Knox provider chain, the gateway's own JWT validation, or a
 * configured stdio identity). Deliberately carries NO credential material: Aegis 0.1 carried the
 * caller's outbound Authorization header here, which made token passthrough (the MCP "confused
 * deputy" anti-pattern) one mistake away. Backend credentials are resolved separately, per target,
 * only after the call has passed every governance control.
 *
 * @param actor            authenticated principal (who presented the credential)
 * @param effectiveSubject subject the call acts for; equals {@code actor} unless the host asserted
 *                         impersonation (for example a Knox identity-assertion provider)
 * @param groups           group memberships established by the host
 * @param issuer           identity source, for example a JWT {@code iss} or {@code knox:<topology>}
 * @param authMethod       how the host authenticated the caller (for audit), e.g. {@code jwt}
 * @param actorChain       delegation chain, outermost first (e.g. RFC 8693 {@code act} claims); may be empty
 */
public record CallerIdentity(
        String actor,
        String effectiveSubject,
        Set<String> groups,
        String issuer,
        String authMethod,
        List<String> actorChain) {

    public CallerIdentity {
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("caller actor must be present; anonymous calls are rejected by the host");
        }
        effectiveSubject = (effectiveSubject == null || effectiveSubject.isBlank()) ? actor : effectiveSubject;
        groups = groups == null ? Set.of() : Set.copyOf(groups);
        issuer = issuer == null ? "" : issuer;
        authMethod = authMethod == null ? "" : authMethod;
        actorChain = actorChain == null ? List.of() : List.copyOf(actorChain);
    }

    public static CallerIdentity of(String actor, Set<String> groups, String issuer, String authMethod) {
        return new CallerIdentity(actor, actor, groups, issuer, authMethod, List.of());
    }

    public boolean inAnyGroup(Set<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return false;
        }
        for (String g : candidates) {
            if (groups.contains(g)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Stable, unambiguous key for per-caller state (rate limits, sessions, approvals). Encoded as a
     * JSON array so a separator character inside a name cannot make two callers collide.
     */
    public String principalKey() {
        return io.github.vaquarkhan.aegis.engine.json.Json.write(List.of(issuer, actor, effectiveSubject));
    }

    public boolean sameCaller(CallerIdentity other) {
        return other != null && Objects.equals(principalKey(), other.principalKey());
    }
}
