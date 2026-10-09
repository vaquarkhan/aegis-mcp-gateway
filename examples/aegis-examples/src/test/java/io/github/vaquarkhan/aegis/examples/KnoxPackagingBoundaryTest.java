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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/**
 * Executable architecture boundary for the two Aegis hosts.
 *
 * <p>The Knox extension adapts Knox public APIs to Aegis. The own-host executable implements its own
 * perimeter and never carries the extension. Both package the same Aegis governance engine. This is
 * composition through a narrow host boundary, not an implementation copied from Knox.
 */
class KnoxPackagingBoundaryTest {

    private static final Path ROOT = Path.of("..", "..").normalize();
    private static final String ENGINE = "io/github/vaquarkhan/aegis/engine/GovernanceEngine.class";
    private static final String KNOX_ADAPTER =
            "io/github/vaquarkhan/aegis/knox/AegisKnoxServiceContributor.class";
    private static final String OWN_HOST = "io/github/vaquarkhan/aegis/gateway/AegisServer.class";
    private static final String OWN_PERIMETER =
            "io/github/vaquarkhan/aegis/security/perimeter/OwnServerPerimeter.class";

    @Test
    void onlyTheExtensionImportsKnox() throws Exception {
        List<String> violations = new ArrayList<>();
        try (var files = Files.walk(ROOT)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("src" + java.io.File.separator + "main"))
                    .filter(p -> !p.startsWith(ROOT.resolve("aegis-knox3-extension"))).toList()) {
                if (Files.readString(file).contains("org.apache.knox")) {
                    violations.add(ROOT.relativize(file).toString());
                }
            }
        }
        assertTrue(violations.isEmpty(), "Knox imports crossed the host boundary: " + violations);
    }

    @Test
    void knoxDependenciesAreProvidedAndTheOwnHostHasNoExtensionDependency() throws Exception {
        String extensionPom = Files.readString(ROOT.resolve("aegis-knox3-extension/pom.xml"));
        Pattern dependencies = Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);
        Matcher matcher = dependencies.matcher(extensionPom);
        int knoxDependencies = 0;
        while (matcher.find()) {
            String dependency = matcher.group(1);
            if (dependency.contains("<groupId>org.apache.knox</groupId>")) {
                knoxDependencies++;
                assertTrue(dependency.contains("<scope>provided</scope>"),
                        "a Knox dependency must be supplied by Knox, never packaged by Aegis: " + dependency);
            }
        }
        assertTrue(knoxDependencies > 0, "the extension must compile against an explicit Knox public API");
        String ownHostPom = Files.readString(ROOT.resolve("aegis-gateway/pom.xml"));
        assertFalse(ownHostPom.contains("aegis-knox3-extension"),
                "the own-host executable must not package or initialize the Knox adapter");
    }

    @Test
    void builtHostsShareAegisGovernanceButNotHostImplementations() throws Exception {
        Path own = oneJar(ROOT.resolve("aegis-gateway/target"), "-all.jar");
        Path knox = oneJar(ROOT.resolve("aegis-knox3-extension/target"), "-knox-bundle.jar");

        try (ZipFile ownJar = new ZipFile(own.toFile()); ZipFile knoxJar = new ZipFile(knox.toFile())) {
            assertTrue(has(ownJar, ENGINE));
            assertTrue(has(knoxJar, ENGINE));

            assertTrue(has(ownJar, OWN_HOST));
            assertTrue(has(ownJar, OWN_PERIMETER));
            assertFalse(has(ownJar, KNOX_ADAPTER), "the own host must not contain Knox adapter classes");

            assertTrue(has(knoxJar, KNOX_ADAPTER));
            assertFalse(has(knoxJar, OWN_HOST), "the Knox bundle must not carry the Aegis HTTP host");
            assertFalse(has(knoxJar, OWN_PERIMETER), "Knox supplies its perimeter in Knox mode");

            assertTrue(ownJar.stream().noneMatch(e -> e.getName().startsWith("org/apache/knox/")),
                    "the own-host jar must not contain Knox implementation classes");
            assertTrue(knoxJar.stream().noneMatch(e -> e.getName().startsWith("org/apache/knox/")),
                    "the extension must link to Knox-provided APIs, not package Knox classes");
            // Knox supplies the servlet, JAX-RS, Jersey, Jackson and YAML runtime; the bundle must not
            // carry a second copy that could shadow Knox's own classes.
            for (String foreign : List.of("javax/servlet/", "javax/ws/rs/", "jakarta/", "org/glassfish/jersey/",
                    "com/fasterxml/jackson/", "org/yaml/snakeyaml/")) {
                assertTrue(knoxJar.stream().noneMatch(e -> e.getName().startsWith(foreign)),
                        "the Knox bundle must not package " + foreign);
            }
            // What the bundle does carry: the shared Aegis modules, the OTLP exporter, the PostgreSQL
            // provider with its driver, and the extension itself.
            for (String required : List.of(
                    "io/github/vaquarkhan/aegis/mcp/server/McpServerHandler.class",
                    "io/github/vaquarkhan/aegis/config/GatewayFactory.class",
                    "io/github/vaquarkhan/aegis/observability/otlp/OtlpSpanExporter.class",
                    "io/github/vaquarkhan/aegis/state/postgres/PostgresStateStoreFactory.class",
                    "org/postgresql/Driver.class")) {
                assertTrue(has(knoxJar, required), "the Knox bundle must contain " + required);
            }
        }
    }

    private static boolean has(ZipFile jar, String entry) {
        return jar.getEntry(entry) != null;
    }

    private static Path oneJar(Path directory, String suffix) throws Exception {
        try (var files = Files.list(directory)) {
            List<Path> matches = files.filter(p -> p.getFileName().toString().endsWith(suffix)).toList();
            assertTrue(matches.size() == 1, "expected exactly one " + suffix + " in " + directory + ": " + matches);
            return matches.get(0);
        }
    }
}
