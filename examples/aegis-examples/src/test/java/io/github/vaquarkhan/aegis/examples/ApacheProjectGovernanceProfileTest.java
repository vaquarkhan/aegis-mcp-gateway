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
package io.github.vaquarkhan.aegis.examples;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.config.BuildContext;
import io.github.vaquarkhan.aegis.config.Gateway;
import io.github.vaquarkhan.aegis.config.GatewayFactory;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Keeps the illustrative project-governance profile a valid, safe configuration artifact. */
class ApacheProjectGovernanceProfileTest {

    @TempDir
    Path dir;

    @Test
    void exampleIsClearlyLabelledAndBuildsWithDurableStateOutsideTheSourceTree() throws Exception {
        Path source = Path.of("..", "..", "adopters", "profiles", "apache-project-governance.example.yaml");
        String text = Files.readString(source);
        assertTrue(text.contains("ILLUSTRATIVE ONLY"));
        assertTrue(text.contains("not an ASF policy"));
        assertTrue(text.contains("project-admin.invalid"), "the sample must not name a live service");

        Path copy = dir.resolve("apache-project-governance.example.yaml");
        Files.writeString(copy, text);
        Map<String, String> env = Map.of("AEGIS_SIGNING_KEY", "e".repeat(48));
        try (Gateway gateway = GatewayFactory.build(copy, BuildContext.defaults(dir).withEnv(env::get))) {
            CallerIdentity contributor = CallerIdentity.of("c", java.util.Set.of("project-contributors"), "example", "test");
            CallerIdentity maintainer = CallerIdentity.of("m", java.util.Set.of("project-maintainers"), "example", "test");
            List<String> contributorTools = gateway.engine().listTools(contributor).stream()
                    .map(t -> String.valueOf(t.get("name"))).toList();
            List<String> maintainerTools = gateway.engine().listTools(maintainer).stream()
                    .map(t -> String.valueOf(t.get("name"))).toList();
            assertTrue(contributorTools.contains("project_get_evidence"));
            assertFalse(contributorTools.contains("project_update_configuration"));
            assertTrue(maintainerTools.contains("project_update_configuration"));
        }
        assertTrue(Files.isDirectory(dir.resolve("state").resolve("project-governance")), "Derby state belongs beside the copied profile");
    }
}
