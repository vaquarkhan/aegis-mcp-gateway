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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Keeps public inventory claims bound to the configurations that actually ship.
 *
 * <p>Adapter configuration is product code. A manual count in a README is therefore a compatibility
 * claim, not decorative prose. This test derives the count from the same YAML reader that drives every
 * adapter through governance. Five product-named files were then removed because their target APIs
 * did not exist; honest removal reduced the deployable inventory to 25 configurations and 116 tools.
 */
class DocumentationClaimsTest {

    private static final int ADAPTER_CONFIGS = 25;
    private static final int ADAPTER_TOOLS = 116;

    private static String document(String relative) throws Exception {
        Path file = Path.of("..", "..", relative).normalize();
        assertTrue(Files.isRegularFile(file), "document exists: " + file.toAbsolutePath());
        return Files.readString(file);
    }

    @Test
    void publishedAdapterInventoryEqualsTheConfigsTheGatewayLoads() throws Exception {
        List<AdapterConfigsTest.Adapter> adapters = AdapterConfigsTest.adapters();
        int tools = adapters.stream().mapToInt(a -> a.tools().size()).sum();
        assertEquals(ADAPTER_CONFIGS, adapters.size(), "update this contract only with a reviewed config addition/removal");
        assertEquals(ADAPTER_TOOLS, tools, "update this contract only with a reviewed tool addition/removal");

        for (String relative : List.of(
                "README.md",
                "docs/ADOPTION-GUIDE.md",
                "docs/FEATURES.md",
                "docs/VALIDATION.md",
                "docs/COVERAGE-0.1-VS-2.0.md",
                "adopters/configurations/adapters/README.md")) {
            String text = document(relative);
            assertTrue(text.contains("25 deployable adapter configurations") || text.contains("25 configurations"),
                    relative + ": must state the current deployable adapter-config count");
            assertTrue(text.contains("116 tools"), relative + ": must state the current adapter-tool count");
            assertFalse(text.contains("29 config-driven adapters"), relative + ": stale adapter count");
            assertFalse(text.contains("29 engines"), relative + ": stale adapter count");
        }
    }

    @Test
    void everyAdopterProfileHasALinkedDeveloperReadme() throws Exception {
        String catalog = document("adopters/configurations/adapters/README.md");
        List<Path> profiles = AdopterConfigurations.adapters();
        assertEquals(ADAPTER_CONFIGS, profiles.size(), "developer layout must cover every deployable adopter");
        for (Path profile : profiles) {
            String adopter = profile.getParent().getFileName().toString();
            Path readme = profile.getParent().resolve("README.md");
            assertTrue(Files.isRegularFile(readme), adopter + ": developer README must exist beside the profile");
            String text = Files.readString(readme);
            assertTrue(text.startsWith("<!--") && text.contains("# "), adopter + ": README needs license and title");
            assertTrue(catalog.contains("(" + adopter + "/README.md)"), adopter + ": root catalog must link its README");
            assertTrue(catalog.contains("(" + adopter + "/" + adopter + ".yaml)"),
                    adopter + ": root catalog must link its executable YAML");
        }
    }

    @Test
    void everySharedReferenceProfileHasALinkedDeveloperReadme() throws Exception {
        String catalog = document("adopters/configurations/README.md");
        List<Path> profiles = AdopterConfigurations.profiles();
        assertEquals(7, profiles.size(), "shared-profile layout must retain the seven documented references");
        for (Path profile : profiles) {
            String name = profile.getParent().getFileName().toString();
            Path readme = profile.getParent().resolve("README.md");
            assertTrue(Files.isRegularFile(readme), name + ": developer README must exist beside the profile");
            String text = Files.readString(readme);
            assertTrue(text.startsWith("<!--") && text.contains("# "), name + ": README needs license and title");
            assertTrue(catalog.contains("(" + name + "/README.md)"),
                    name + ": shared-profile catalog must link its README");
            assertTrue(catalog.contains("(" + name + "/" + name + ".yaml)"),
                    name + ": shared-profile catalog must link its executable YAML");
        }

        Path template = AdopterConfigurations.template();
        Path templateReadme = template.getParent().resolve("README.md");
        assertTrue(Files.isRegularFile(templateReadme), "template: developer README must exist beside the template");
        assertTrue(catalog.contains("(template/README.md)"), "shared-profile catalog must link the template README");
        assertTrue(catalog.contains("(template/TEMPLATE.yaml)"), "shared-profile catalog must link the template YAML");
    }

    @Test
    void publicDocsDoNotCallContractOnlyAdaptersCertifiedOrReadyToRun() throws Exception {
        String readme = document("README.md");
        String adoption = document("docs/ADOPTION-GUIDE.md");
        String catalog = document("adopters/configurations/adapters/README.md");
        for (String text : List.of(readme, adoption, catalog)) {
            assertFalse(text.contains("ready-to-run configurations"),
                    "a contract-harness run is not a release compatibility certification");
            assertFalse(text.contains("certified against a specific release"),
                    "no adapter may claim a certification this build did not perform");
        }
    }

    /** Old public totals must never return in a roadmap, a defect history, or a feature page. */
    @Test
    void noPublishedMarkdownRetainsTheSupersededAdapterTotals() throws Exception {
        Map<String, String> obsolete = Map.ofEntries(
                Map.entry("29 config-driven adapters", "use 25 deployable adapter configurations"),
                Map.entry("29 configuration files", "use 25 deployable adapter configurations"),
                Map.entry("29 engines", "do not confuse config count with the 33-engine 0.1 baseline"),
                Map.entry("30 adapter configurations", "five fictional configurations were removed; use 25 deployable adapter configurations"),
                Map.entry("134 tools", "five fictional surfaces were removed; use 115 adapter tools"),
                Map.entry("171 comparable", "use the executable 152 implemented-successor total"),
                Map.entry("181 full", "use the executable 162 full comparison total"));
        try (var files = Files.walk(Path.of("..", ".."))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".md")).toList()) {
                String text = Files.readString(file);
                for (Map.Entry<String, String> e : obsolete.entrySet()) {
                    assertFalse(text.contains(e.getKey()), file + ": obsolete '" + e.getKey() + "', " + e.getValue());
                }
            }
        }
    }
}
