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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.config.ConfigException;
import io.github.vaquarkhan.aegis.config.ConfigNode;
import io.github.vaquarkhan.aegis.engine.model.Action;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.model.DenyCodes;
import io.github.vaquarkhan.aegis.engine.model.ToolClass;
import io.github.vaquarkhan.aegis.engine.spi.GovernanceHook;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Direct behavioral tests for project-owned evidence and protected-resource guardrails. */
class ProjectGovernanceGuardrailTest {

    private static GovernanceHook.Context context(ToolClass cls, List<String> resources, Map<String, Object> args) {
        return new GovernanceHook.Context("req", "t".repeat(32),
                CallerIdentity.of("maintainer", Set.of("project-maintainers"), "example", "test"),
                GovernanceHook.Operation.TOOL_CALL, "project_publish_release", null, "project-admin",
                new Action("project_publish_release", cls, "project-admin", resources, "sha256:args"), args, false);
    }

    @Test
    void highImpactActionsNeedBoundedEvidenceAndProtectedResourcesAlwaysDeny() {
        ProjectGovernanceGuardrail g = new ProjectGovernanceGuardrail(new ProjectGovernanceGuardrail.Settings(
                Set.of(ToolClass.MUTATE, ToolClass.DESTRUCTIVE), "evidenceId", 8, List.of("project:governance:*")));
        assertFalse(g.before(context(ToolClass.DESTRUCTIVE, List.of("project:release:2.0"), Map.of())).allowed());
        assertEquals(DenyCodes.INVALID_INPUT, g.before(context(ToolClass.MUTATE, List.of(), Map.of("evidenceId", "short"))).code());
        assertFalse(g.before(context(ToolClass.DESTRUCTIVE, List.of(), Map.of("evidenceId", "docs/../secret"))).allowed());
        assertTrue(g.before(context(ToolClass.DESTRUCTIVE, List.of("project:release:2.0"),
                Map.of("evidenceId", "docs/evidence/release-2.0.md"))).allowed());
        assertFalse(g.before(context(ToolClass.READ, List.of("project:governance:rules"),
                Map.of())).allowed(), "protected resources deny regardless of action class");
        assertEquals(DenyCodes.POLICY_DENIED, g.before(context(ToolClass.READ, List.of("project:governance:rules"),
                Map.of())).code());
    }

    @Test
    void factoryParsesOnlyKnownClassesAndBounds() {
        ProjectGovernanceGuardrailFactory factory = new ProjectGovernanceGuardrailFactory();
        assertEquals("project-governance", factory.type());
        ConfigNode valid = new ConfigNode("hook.settings", Map.of(
                "requireEvidenceFor", List.of("MUTATE"), "protectedResources", List.of("project:governance:*")));
        assertTrue(factory.create(valid) instanceof ProjectGovernanceGuardrail);
        ConfigNode unknown = new ConfigNode("hook.settings", Map.of("requireEvidenceFor", List.of("UNKNOWN")));
        assertThrows(ConfigException.class, () -> factory.create(unknown));
        ConfigNode unbounded = new ConfigNode("hook.settings", Map.of("minEvidenceLength", 0));
        assertThrows(ConfigException.class, () -> factory.create(unbounded));
    }
}
