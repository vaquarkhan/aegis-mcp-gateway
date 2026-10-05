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
package io.github.vaquarkhan.aegis.guardrails;

import io.github.vaquarkhan.aegis.engine.control.Glob;
import io.github.vaquarkhan.aegis.engine.model.Decision;
import io.github.vaquarkhan.aegis.engine.model.DenyCodes;
import io.github.vaquarkhan.aegis.engine.model.ToolClass;
import io.github.vaquarkhan.aegis.engine.spi.GovernanceHook;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A project-owned governance guardrail for high-impact MCP actions.
 *
 * <p>This guardrail does two narrow things. It keeps configured protected resources off limits to all
 * agent actions, and it requires a bounded evidence reference in the action arguments for configured
 * high-impact classes. It does not claim to implement an ASF policy, determine regulatory applicability,
 * fetch evidence or approve a release. The existing Aegis policy, independent approval, destructive
 * receipt and audit controls remain authoritative and all still have to permit the action.
 */
public final class ProjectGovernanceGuardrail implements GovernanceHook {

    /** Immutable settings, parsed by {@link ProjectGovernanceGuardrailFactory}. */
    public record Settings(Set<ToolClass> evidenceRequiredFor, String evidenceArgument, int minEvidenceLength,
                           List<String> protectedResourcePatterns) {
        public Settings {
            evidenceRequiredFor = evidenceRequiredFor == null ? Set.of() : Set.copyOf(evidenceRequiredFor);
            evidenceArgument = evidenceArgument == null || evidenceArgument.isBlank() ? "evidenceId" : evidenceArgument;
            if (!evidenceArgument.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) {
                throw new IllegalArgumentException("evidenceArgument must be an identifier");
            }
            if (minEvidenceLength < 1 || minEvidenceLength > 512) {
                throw new IllegalArgumentException("minEvidenceLength must be between 1 and 512");
            }
            protectedResourcePatterns = protectedResourcePatterns == null ? List.of() : List.copyOf(protectedResourcePatterns);
        }
    }

    private final Settings settings;

    public ProjectGovernanceGuardrail(Settings settings) {
        this.settings = settings;
    }

    @Override
    public String name() {
        return "project-governance";
    }

    @Override
    public Decision before(Context context) {
        for (String resource : context.action().resources()) {
            if (Glob.anyMatches(settings.protectedResourcePatterns(), resource)) {
                return Decision.deny(DenyCodes.POLICY_DENIED, 8,
                        "project-governance guardrail protects resource " + resource);
            }
        }
        if (!settings.evidenceRequiredFor().contains(context.action().toolClass())) {
            return Decision.allow();
        }
        Object value = context.arguments().get(settings.evidenceArgument());
        if (!(value instanceof String evidence) || !validEvidence(evidence)) {
            return Decision.deny(DenyCodes.INVALID_INPUT, 8,
                    "project-governance guardrail requires a valid " + settings.evidenceArgument()
                            + " for " + context.action().toolClass() + " actions");
        }
        return Decision.allow();
    }

    private boolean validEvidence(String evidence) {
        if (evidence.length() < settings.minEvidenceLength() || evidence.length() > 512 || evidence.contains("..")) {
            return false;
        }
        for (int i = 0; i < evidence.length(); i++) {
            if (Character.isISOControl(evidence.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
