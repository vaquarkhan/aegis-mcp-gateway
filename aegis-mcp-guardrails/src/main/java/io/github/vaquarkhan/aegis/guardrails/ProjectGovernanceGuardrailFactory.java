/*
 * Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at

 *     http://www.apache.org/licenses/LICENSE-2.0

 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vaquarkhan.aegis.guardrails;

import io.github.vaquarkhan.aegis.config.ConfigException;
import io.github.vaquarkhan.aegis.config.ConfigNode;
import io.github.vaquarkhan.aegis.config.spi.GovernanceHookFactory;
import io.github.vaquarkhan.aegis.engine.model.ToolClass;
import io.github.vaquarkhan.aegis.engine.spi.GovernanceHook;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** ServiceLoader factory for {@code hooks[].type: project-governance}. */
public final class ProjectGovernanceGuardrailFactory implements GovernanceHookFactory {

    @Override
    public String type() {
        return "project-governance";
    }

    @Override
    public GovernanceHook create(ConfigNode settings) {
        Set<ToolClass> classes = EnumSet.noneOf(ToolClass.class);
        for (String value : settings.strings("requireEvidenceFor")) {
            try {
                classes.add(ToolClass.valueOf(value.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new ConfigException(settings.path() + ".requireEvidenceFor must name READ, MUTATE or DESTRUCTIVE");
            }
        }
        try {
            return new ProjectGovernanceGuardrail(new ProjectGovernanceGuardrail.Settings(
                    classes, settings.string("evidenceArgument", "evidenceId"),
                    (int) settings.integer("minEvidenceLength", 8), settings.strings("protectedResources")));
        } catch (IllegalArgumentException e) {
            throw new ConfigException(settings.path() + ": " + e.getMessage(), e);
        }
    }
}
