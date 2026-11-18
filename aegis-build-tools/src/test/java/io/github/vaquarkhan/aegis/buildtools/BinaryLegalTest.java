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
package io.github.vaquarkhan.aegis.buildtools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BinaryLegalTest {
    @TempDir Path root;

    @BeforeEach
    void project() throws IOException {
        Files.writeString(root.resolve("LICENSE"), "Project license\n");
        Files.writeString(root.resolve("NOTICE"), "Project attribution\n");
        Files.createDirectories(root.resolve("tools/binary-legal"));
        review("example:one:1|MIT|\nexample:two:1|Apache-2.0|\n");
    }

    @Test
    void preservesCollidingVariantAndNestedTextsAndActualPackages() throws IOException {
        String upstreamNotice = "Upstream attribution\r\n\r\nVerbatim formatting.\r\n";
        Path one = jar("one.jar", Map.of("META-INF/LICENSE.txt", "MIT text",
                "META-INF/licenses/nested/LICENSE", "Nested BSD text",
                "NOTICE", upstreamNotice, "relocated/example/One.class", "class"));
        Path two = jar("two.jar", Map.of("META-INF/LICENSE.txt", "Another license",
                "META-INF/NOTICE.txt", "Another notice", "example/License.class", "not a license"));
        var components = List.of(new BinaryLegal.Component("example:one:1", one),
                new BinaryLegal.Component("example:two:1", two));
        var docs = BinaryLegal.assemble(root, components);
        assertTrue(docs.license().contains("MIT text"));
        assertTrue(docs.license().contains("Nested BSD text"));
        assertTrue(docs.license().contains("Another license"));
        assertTrue(docs.license().contains("relocated.example"));
        assertFalse(docs.license().contains("not a license"));
        assertTrue(docs.notice().startsWith("Project attribution\n"));
        assertTrue(docs.notice().contains(upstreamNotice));
        assertTrue(docs.notice().contains("Another notice"));
        assertFalse(docs.notice().contains("MIT text"));
        assertEquals(docs, BinaryLegal.assemble(root, List.of(components.get(1), components.get(0))));
    }

    @Test
    void rejectsUnknownVersionsAndMissingNonApacheLicense() throws IOException {
        Path dependency = jar("empty.jar", Map.of("example/One.class", "class"));
        assertThrows(IOException.class, () -> BinaryLegal.assemble(root,
                List.of(new BinaryLegal.Component("example:one:2", dependency))));
        assertThrows(IOException.class, () -> BinaryLegal.assemble(root,
                List.of(new BinaryLegal.Component("example:one:1", dependency))));
    }

    @Test
    void usesReviewedSupplementAndRejectsMissingOrEscapingFiles() throws IOException {
        Path dependency = jar("empty.jar", Map.of());
        var components = List.of(new BinaryLegal.Component("example:one:1", dependency));
        review("example:one:1|MIT|upstream/LICENSE-one\n");
        assertThrows(IOException.class, () -> BinaryLegal.assemble(root, components));
        Files.createDirectories(root.resolve("tools/binary-legal/upstream"));
        Files.writeString(root.resolve("tools/binary-legal/upstream/LICENSE-one"), "Full MIT terms");
        assertTrue(BinaryLegal.assemble(root, components).license().contains("Full MIT terms"));
        review("example:one:1|MIT|../../LICENSE\n");
        assertThrows(IOException.class, () -> BinaryLegal.assemble(root, components));
    }

    @Test
    void rejectsMissingOverwrittenLegalFilesSignaturesAndIncorrectInventory() throws IOException {
        Path dependency = jar("one.jar", Map.of("META-INF/LICENSE", "MIT text",
                "example/One.class", "class"));
        var components = List.of(new BinaryLegal.Component("example:one:1", dependency));
        var docs = BinaryLegal.assemble(root, components);
        Map<String, String> contents = new LinkedHashMap<>();
        contents.put("META-INF/LICENSE", docs.license());
        contents.put("META-INF/NOTICE", docs.notice());
        contents.put("example/One.class", "class");
        BinaryLegal.verify(jar("valid.jar", contents), docs, components);
        contents.remove("META-INF/NOTICE");
        assertThrows(IOException.class, () -> BinaryLegal.verify(jar("missing.jar", contents), docs, components));
        contents.put("META-INF/NOTICE", "only a dependency notice");
        assertThrows(IOException.class, () -> BinaryLegal.verify(jar("overwritten.jar", contents), docs, components));
        contents.put("META-INF/NOTICE", docs.notice());
        contents.put("META-INF/UPSTREAM.SF", "signature");
        assertThrows(IOException.class, () -> BinaryLegal.verify(jar("signed.jar", contents), docs, components));
        contents.remove("META-INF/UPSTREAM.SF");
        contents.remove("example/One.class");
        assertThrows(IOException.class, () -> BinaryLegal.verify(jar("wrong-inventory.jar", contents), docs, components));
    }

    @Test
    void rejectsTopLevelLegalVariantsButAllowsNestedComponentLicenses() throws IOException {
        var docs = new BinaryLegal.Documents("Project license", "Project notice");
        Map<String, String> contents = new LinkedHashMap<>();
        contents.put("META-INF/LICENSE", docs.license());
        contents.put("META-INF/NOTICE", docs.notice());
        contents.put("META-INF/licenses/example/LICENSE.txt", "Nested license");
        contents.put("example/NOTICE.md", "Nested notice");
        contents.put("META-INF/License.class", "class");
        BinaryLegal.verify(jar("nested.jar", contents), docs, List.of());

        for (String variant : List.of("LICENSE", "LICENSE.txt", "notice-md", "META-INF/NOTICE.md",
                "META-INF/license", "meta-inf/NOTICE", "META-INF/LICENCE.txt")) {
            contents.put(variant, "Stale legal text");
            IOException error = assertThrows(IOException.class,
                    () -> BinaryLegal.verify(jar("variant.jar", contents), docs, List.of()));
            assertTrue(error.getMessage().contains(variant));
            contents.remove(variant);
        }
    }

    @Test
    void followsKnoxShadeIncludesRatherThanProvidedDependencyMetadata() throws IOException {
        Xpp3Dom set = new Xpp3Dom("artifactSet");
        Xpp3Dom includes = new Xpp3Dom("includes");
        Xpp3Dom include = new Xpp3Dom("include");
        include.setValue("org.postgresql:postgresql");
        includes.addChild(include);
        set.addChild(includes);
        assertTrue(BinaryLegalMojo.selected(set, "org.postgresql:postgresql"));
        assertFalse(BinaryLegalMojo.selected(set, "org.apache.knox:gateway-server"));
        include.setValue("*:*");
        assertThrows(IOException.class, () -> BinaryLegalMojo.selected(set, "org.postgresql:postgresql"));
    }

    @Test
    void locatesInventoryFromExamplesReactorAndFailsWhenMissing() throws IOException {
        Path examples = Files.createDirectories(root.resolve("examples"));
        assertEquals(root, BinaryLegalMojo.findLegalRoot(root));
        assertEquals(root, BinaryLegalMojo.findLegalRoot(examples));
        Files.delete(root.resolve("tools/binary-legal/components.txt"));
        IOException error = assertThrows(IOException.class, () -> BinaryLegalMojo.findLegalRoot(examples));
        assertTrue(error.getMessage().contains("tools/binary-legal/components.txt"));
    }

    private void review(String text) throws IOException {
        Files.writeString(root.resolve("tools/binary-legal/components.txt"), text);
    }

    private Path jar(String name, Map<String, String> entries) throws IOException {
        Path path = root.resolve(name);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return path;
    }
}
