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
package io.github.vaquarkhan.aegis.examples;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Assertions;

/** Canonical paths for product-facing adoption configurations, independent of the Maven test module. */
final class AdopterConfigurations {

    private static final Path ROOT = Path.of("..", "..", "adopters", "configurations").normalize();
    private static final List<String> PROFILE_NAMES = List.of(
            "a2a", "existing-mcp-server", "flink", "iceberg", "kafka", "spark", "spark-connect");

    private AdopterConfigurations() {}

    static Path root() {
        Assertions.assertTrue(Files.isDirectory(ROOT), "adopters/configurations must exist: " + ROOT.toAbsolutePath());
        return ROOT;
    }

    /**
     * Canonical shared-profile layout: each named reference profile has one same-named YAML and a
     * README. The root is intentionally free of YAML files, including the template, so users do not
     * need to know which flat YAML is a runnable profile versus a teaching artifact.
     */
    static List<Path> profiles() throws IOException {
        List<String> directories;
        try (var entries = Files.list(root())) {
            List<Path> flatYaml = entries.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".yaml")).toList();
            Assertions.assertTrue(flatYaml.isEmpty(), "shared profiles must live in named directories: " + flatYaml);
        }
        try (var entries = Files.list(root())) {
            directories = entries.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> !n.equals("adapters") && !n.equals("template"))
                    .sorted().toList();
        }
        Assertions.assertEquals(PROFILE_NAMES, directories,
                "shared profile directories must be the documented reference profiles");
        List<Path> profiles = new ArrayList<>();
        for (String name : PROFILE_NAMES) {
            profiles.add(profile(name));
        }
        return List.copyOf(profiles);
    }

    static Path profile(String name) {
        Assertions.assertTrue(PROFILE_NAMES.contains(name), "unknown shared profile: " + name);
        Path folder = root().resolve(name);
        Path yaml = folder.resolve(name + ".yaml");
        Path readme = folder.resolve("README.md");
        Assertions.assertTrue(Files.isRegularFile(yaml), "shared profile must exist: " + yaml.toAbsolutePath());
        Assertions.assertTrue(Files.isRegularFile(readme), "shared profile needs developer README: " + readme.toAbsolutePath());
        return yaml;
    }

    static Path template() {
        Path folder = root().resolve("template");
        Path yaml = folder.resolve("TEMPLATE.yaml");
        Path readme = folder.resolve("README.md");
        Assertions.assertTrue(Files.isRegularFile(yaml), "template YAML must exist: " + yaml.toAbsolutePath());
        Assertions.assertTrue(Files.isRegularFile(readme), "template needs developer README: " + readme.toAbsolutePath());
        return yaml;
    }

    static Path adaptersRoot() {
        Path path = root().resolve("adapters");
        Assertions.assertTrue(Files.isDirectory(path), "adopters/configurations/adapters must exist: " + path.toAbsolutePath());
        return path;
    }

    /**
     * Canonical 2.0 adopter layout: one immediate adopter directory containing a same-named YAML
     * profile and a developer README. Root-level YAML files are rejected so a new adopter cannot
     * silently recreate the 0.1 flat adapter-module layout.
     */
    static List<Path> adapters() throws IOException {
        Path root = adaptersRoot();
        List<Path> folders;
        try (var entries = Files.list(root)) {
            List<Path> flatYaml = entries.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".yaml")).toList();
            Assertions.assertTrue(flatYaml.isEmpty(), "adapter YAML must live in its named adopter directory: " + flatYaml);
        }
        try (var entries = Files.list(root)) {
            folders = entries.filter(Files::isDirectory).sorted().toList();
        }
        List<Path> profiles = new ArrayList<>();
        for (Path folder : folders) {
            String adopter = folder.getFileName().toString();
            Assertions.assertTrue(adopter.matches("[a-z][a-z0-9-]*"),
                    "adopter directory name must be lowercase and stable: " + folder);
            Path profile = folder.resolve(adopter + ".yaml");
            Path readme = folder.resolve("README.md");
            Assertions.assertTrue(Files.isRegularFile(profile),
                    "adopter directory must contain same-named executable YAML: " + profile);
            Assertions.assertTrue(Files.isRegularFile(readme),
                    "adopter directory must contain developer README: " + readme);
            profiles.add(profile);
        }
        return List.copyOf(profiles);
    }

    static Path adapter(String adopter) {
        Assertions.assertTrue(adopter.matches("[a-z][a-z0-9-]*"), "adopter name must be a directory name: " + adopter);
        Path path = adaptersRoot().resolve(adopter).resolve(adopter + ".yaml");
        Assertions.assertTrue(Files.isRegularFile(path), "adopter profile must exist: " + path.toAbsolutePath());
        return path;
    }
}
