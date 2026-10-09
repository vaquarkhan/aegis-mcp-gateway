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
package io.github.vaquarkhan.aegis.knox;

import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.security.auth.Subject;
import org.apache.knox.gateway.security.ActorChainPrincipal;
import org.apache.knox.gateway.security.GroupPrincipal;
import org.apache.knox.gateway.security.SubjectUtils;

/**
 * Knox mode uses Knox security: authentication (Shiro/LDAP, KnoxSSO, JWT/JWKS federation...) and
 * identity assertion run in Knox's provider chain BEFORE this service, and the result is the
 * current {@link Subject}. Aegis only reads it; it never re-authenticates and never sees the
 * credential.
 *
 * <p>Mapping (Knox 3.0.0 gateway-spi types):
 * <ul>
 *   <li>actor = {@code PrimaryPrincipal} (who authenticated)</li>
 *   <li>effectiveSubject = {@link SubjectUtils#getEffectivePrincipalName} (the {@code ImpersonatedPrincipal}
 *       set by identity assertion, else the primary)</li>
 *   <li>groups = every {@link GroupPrincipal}</li>
 *   <li>actorChain = {@code sub} of each entry of {@link ActorChainPrincipal#getActorChain()} when a
 *       token-exchange provider set it. This mapping is our reading of the 3.0.0 interface and is
 *       listed for confirmation by Knox maintainers in docs/KNOX-EXTENSION.md.</li>
 * </ul>
 */
public final class KnoxIdentityBridge {

    private KnoxIdentityBridge() {}

    /** @return the caller, or null when Knox established no authenticated subject */
    public static CallerIdentity fromCurrentSubject(String topology) {
        return fromSubject(SubjectUtils.getCurrentSubject(), topology);
    }

    public static CallerIdentity fromSubject(Subject subject, String topology) {
        if (subject == null) {
            return null;
        }
        String primary = SubjectUtils.getPrimaryPrincipalName(subject);
        if (primary == null || primary.isBlank()) {
            return null;
        }
        String effective = SubjectUtils.getEffectivePrincipalName(subject);
        Set<String> groups = new LinkedHashSet<>();
        for (GroupPrincipal g : SubjectUtils.getGroupPrincipals(subject)) {
            if (g.getName() != null) {
                groups.add(g.getName());
            }
        }
        List<String> chain = new ArrayList<>();
        for (ActorChainPrincipal acp : subject.getPrincipals(ActorChainPrincipal.class)) {
            List<Map<String, Object>> entries = acp.getActorChain();
            if (entries != null) {
                for (Map<String, Object> e : entries) {
                    Object sub = e == null ? null : e.get("sub");
                    if (sub instanceof String s && !s.isBlank()) {
                        chain.add(s);
                    }
                }
            }
        }
        return new CallerIdentity(primary, effective, groups, "knox:" + topology, "knox", chain);
    }
}
