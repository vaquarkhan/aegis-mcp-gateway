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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class BinaryLegal {
    record Component(String coordinate, Path jar) { }
    record Review(String license, List<String> supplements, String notes) { }
    record Documents(String license, String notice) { }

    private BinaryLegal() { }

    static Map<String, Review> reviews(Path file) throws IOException {
        Map<String, Review> result = new HashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] fields = line.split("\\|", -1);
            if ((fields.length != 3 && fields.length != 4) || fields[0].isBlank() || fields[1].isBlank()) {
                throw new IOException("Invalid legal review row: " + line);
            }
            Review review = new Review(fields[1], fields[2].isBlank()
                    ? List.of() : List.of(fields[2].split(",")), fields.length == 4 ? fields[3] : "");
            if (result.putIfAbsent(fields[0], review) != null) {
                throw new IOException("Duplicate legal review: " + fields[0]);
            }
        }
        return result;
    }

    static Documents assemble(Path root, List<Component> components) throws IOException {
        Path legal = root.resolve("tools/binary-legal");
        Map<String, Review> reviews = reviews(legal.resolve("components.txt"));
        StringBuilder license = new StringBuilder(Files.readString(root.resolve("LICENSE")));
        StringBuilder notice = new StringBuilder(Files.readString(root.resolve("NOTICE")));
        for (Component component : components.stream().sorted(
                Comparator.comparing(Component::coordinate)).toList()) {
            Review review = reviews.get(component.coordinate());
            if (review == null) {
                throw new IOException("Unreviewed bundled component: " + component.coordinate()
                        + "; update tools/binary-legal/components.txt");
            }
            try (ZipFile jar = new ZipFile(component.jar().toFile())) {
                TreeSet<String> packages = new TreeSet<>();
                List<? extends ZipEntry> entries = jar.stream()
                        .filter(entry -> !entry.isDirectory()).sorted(
                                Comparator.comparing(ZipEntry::getName)).toList();
                for (ZipEntry entry : entries) {
                    String name = entry.getName();
                    if (name.endsWith(".class") && !name.endsWith("module-info.class")) {
                        name = name.replaceFirst("^META-INF/versions/[0-9]+/", "");
                        int slash = name.lastIndexOf('/');
                        if (slash > 0) packages.add(name.substring(0, slash).replace('/', '.'));
                    }
                }
                license.append("\n\n=== ").append(component.coordinate()).append(" ===\n")
                        .append("License: ").append(review.license()).append('\n')
                        .append(review.notes()).append('\n')
                        .append("Packages in this archive (including upstream relocations):\n")
                        .append(String.join("\n", packages)).append('\n');
                boolean foundLicense = false;
                for (ZipEntry entry : entries) {
                    int kind = legalKind(entry.getName());
                    if (kind == 0) continue;
                    String text;
                    try (var input = jar.getInputStream(entry)) {
                        text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                    }
                    if (text.isBlank()) {
                        throw new IOException("Empty legal resource: " + component.coordinate()
                                + "!" + entry.getName());
                    }
                    append(kind == 2 ? notice : license,
                            kind == 2 ? component.coordinate() + "!" + entry.getName() : entry.getName(), text);
                    foundLicense |= kind == 1;
                }
                for (String supplement : review.supplements()) {
                    Path path = legal.resolve(supplement).normalize();
                    if (!path.startsWith(legal) || !Files.isRegularFile(path)) {
                        throw new IOException("Missing or invalid legal supplement: " + supplement);
                    }
                    String text = Files.readString(path);
                    if (text.isBlank()) throw new IOException("Empty legal supplement: " + supplement);
                    Path filename = path.getFileName();
                    if (filename == null) throw new IOException("Invalid legal supplement: " + supplement);
                    boolean isNotice = filename.toString().startsWith("NOTICE");
                    append(isNotice ? notice : license,
                            isNotice ? component.coordinate() + " / " + supplement : supplement, text);
                    foundLicense |= !isNotice;
                }
                if (!foundLicense && !"Apache-2.0".equals(review.license())) {
                    throw new IOException("No license text for " + component.coordinate());
                }
                if (!foundLicense) license.append("Covered by the Apache-2.0 text above.\n");
            }
        }
        return new Documents(license.toString(), notice.toString());
    }

    private static void append(StringBuilder target, String source, String text) {
        target.append("\n\n--- ").append(source).append(" ---\n").append(text).append('\n');
    }

    static int legalKind(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (name.matches("notice(?:[._-].*)?") && !name.endsWith(".class")) return 2;
        if (name.matches("(?:licen[sc]e|copying|copyright)(?:[._-].*)?")
                && !name.endsWith(".class")) return 1;
        return 0;
    }

    static void verify(Path archive, Documents expected, List<Component> components) throws IOException {
        try (ZipFile jar = new ZipFile(archive.toFile())) {
            checkDocument(jar, "META-INF/LICENSE", expected.license());
            checkDocument(jar, "META-INF/NOTICE", expected.notice());
            for (ZipEntry entry : jar.stream().toList()) {
                String name = entry.getName();
                int slash = name.lastIndexOf('/');
                String filename = name.substring(slash + 1).toLowerCase(Locale.ROOT);
                if (!entry.isDirectory() && !name.equals("META-INF/LICENSE") && !name.equals("META-INF/NOTICE")
                        && (slash < 0 || name.substring(0, slash).equalsIgnoreCase("META-INF"))
                        && (filename.startsWith("licen") || filename.startsWith("notice"))
                        && legalKind(name) != 0) {
                    throw new IOException("Unexpected top-level legal resource: " + name);
                }
                if (name.matches("(?i)META-INF/[^/]+\\.(SF|DSA|RSA)")) {
                    throw new IOException("Invalid shaded signature: " + name);
                }
            }
            // A dependency tree is not an archive inventory: ensure the selected dependencies really shipped.
            for (Component component : components) {
                try (ZipFile input = new ZipFile(component.jar().toFile())) {
                    List<String> missing = new ArrayList<>();
                    for (ZipEntry entry : input.stream().toList()) {
                        String name = entry.getName();
                        if (name.endsWith(".class") && !name.endsWith("module-info.class")
                                && jar.getEntry(name) == null) missing.add(name);
                    }
                    if (!missing.isEmpty()) {
                        throw new IOException("Legal inventory does not match shaded content: "
                                + component.coordinate() + " is missing " + missing.get(0));
                    }
                }
            }
        }
    }

    private static void checkDocument(ZipFile jar, String name, String expected) throws IOException {
        if (jar.stream().filter(entry -> entry.getName().equals(name)).count() != 1) {
            throw new IOException("Expected exactly one " + name + " in " + jar.getName());
        }
        try (var input = jar.getInputStream(jar.getEntry(name))) {
            if (!Arrays.equals(input.readAllBytes(), expected.getBytes(StandardCharsets.UTF_8))) {
                throw new IOException("Incorrect " + name + " in " + jar.getName());
            }
        }
    }
}
