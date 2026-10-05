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
package io.github.vaquarkhan.aegis.validation.frontdoor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * One adopter configuration, prepared for a run: the canonical YAML with only its target address and
 * egress network replaced, the groups each persona holds in that configuration, and the environment the
 * configuration reads its secrets from. The same scenario is opened behind each front door.
 *
 * @param adopter     folder name under {@code examples/adopters}, used for result files
 * @param configYaml  the adopter YAML after address substitution; no {@code host:} section
 * @param groups      persona to adopter groups (personas absent here hold no group)
 * @param env         environment variables the configuration reads
 */
public record AdopterScenario(String adopter, String configYaml, Map<Persona, List<String>> groups, Map<String, String> env,
        List<Path> knoxPlugins, List<String> knoxJvmArgs) {

    public AdopterScenario(String adopter, String configYaml, Map<Persona, List<String>> groups, Map<String, String> env) {
        this(adopter, configYaml, groups, env, List.of(), List.of());
    }

    /**
     * A source plugin the Knox front door must install in {@code ext/}, as an operator would: the plugin
     * jar and its runtime dependencies. Jars Knox already ships are not duplicated (Knox's version wins),
     * and logging bindings are never added, so Knox's own logging is unchanged.
     */
    public AdopterScenario withKnoxPlugin(List<Path> jars, List<String> jvmArgs) {
        return new AdopterScenario(adopter, configYaml, groups, env, List.copyOf(jars), List.copyOf(jvmArgs));
    }

    /**
     * A source plugin as its module publishes it under {@code -Preal-engines}: the module jar (system
     * property {@code jarProperty}) and its {@code target/plugin.classpath} (system property {@code classpathProperty}).
     */
    public static List<Path> plugin(String jarProperty, String classpathProperty) throws IOException {
        List<Path> jars = new java.util.ArrayList<>();
        Path jar = Path.of(System.getProperty(jarProperty, ""));
        Path cp = Path.of(System.getProperty(classpathProperty, ""));
        if (!Files.isRegularFile(jar) || !Files.isRegularFile(cp)) {
            throw new IllegalStateException("plugin not built (mvn -Preal-engines install): " + jar + ", " + cp);
        }
        jars.add(jar);
        for (String entry : Files.readString(cp).trim().split(java.io.File.pathSeparator)) {
            if (!entry.isBlank()) {
                jars.add(Path.of(entry));
            }
        }
        return jars;
    }

    /** Path of a canonical adopter configuration, relative to the repository root. */
    public static Path canonical(String... segments) {
        return Path.of("..", "..", "adopters", "configurations").resolve(Path.of("", segments)).normalize();
    }

    public static AdopterScenario of(String adopter, Path canonicalYaml, UnaryOperator<String> substitute,
            Map<Persona, List<String>> groups, Map<String, String> env) throws IOException {
        String yaml = substitute.apply(Files.readString(canonicalYaml));
        if (yaml.lines().anyMatch(l -> l.startsWith("host:"))) {
            throw new IllegalArgumentException(canonicalYaml + ": adopter profiles must not carry a host section");
        }
        Map<String, String> e = new LinkedHashMap<>(env);
        e.putIfAbsent("AEGIS_SIGNING_KEY", "aegis-validation-signing-key-" + "0123456789abcdef");
        return new AdopterScenario(adopter, yaml, Map.copyOf(groups), Map.copyOf(e));
    }

    List<String> groupsOf(Persona p) {
        return groups.getOrDefault(p, List.of());
    }
}
