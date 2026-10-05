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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.xml.sax.InputSource;

/**
 * Documentation visuals and navigation are release assets, not decoration. These checks use only
 * the JDK parser plus an optional, operator-supplied Mermaid CLI; they never download renderers.
 */
class DocumentationAssetsTest {

    private static final Path ROOT = Path.of("..", "..").normalize();
    private static final Pattern LINK = Pattern.compile(
            "!?\\[[^\\]]*\\]\\(([^)\\s]+)\\)|(?:href|src)=\"([^\"]+)\"");
    private static final Pattern FENCE = Pattern.compile("^```.*?^```", Pattern.MULTILINE | Pattern.DOTALL);
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.*?)\\s*#*\\s*$", Pattern.MULTILINE);
    private static final Pattern MERMAID_BLOCK = Pattern.compile(
            "^```mermaid\\R(.*?)^```", Pattern.MULTILINE | Pattern.DOTALL);

    private static String text(String relative) throws Exception {
        Path file = ROOT.resolve(relative);
        assertTrue(Files.isRegularFile(file), "required documentation file: " + file.toAbsolutePath());
        return Files.readString(file);
    }

    @Test
    void staticArchitectureSvgsAreWellFormedAndSelfContained() throws Exception {
        for (String relative : new String[] {
                "docs/assets/aegis-architecture.svg",
                "docs/assets/aegis-governance-flow.svg",
                "docs/assets/knox-aegis-module-comparison.svg",
                "docs/assets/mcp-governance-problem-solution.svg",
                "docs/assets/owasp-mcp-top10-coverage.svg",
                "docs/assets/aegis-mcp-first-host-boundaries.svg",
                "docs/assets/aegis-threat-model.svg"}) {
            String svg = text(relative);
            assertTrue(svg.contains("Licensed under the Apache License, Version 2.0"), relative + ": Apache header");
            assertTrue(svg.contains("role=\"img\""), relative + ": accessible image role");
            assertTrue(svg.contains("aria-labelledby"), relative + ": accessible title and description reference");

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            var document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(svg)));
            assertEquals("svg", document.getDocumentElement().getLocalName(), relative + ": SVG root");
        }
    }

    @Test
    void staticPngsAreValidTwoTimesRendersOfTheSvgCanvas() throws Exception {
        assertPng("docs/assets/aegis-architecture.png", 3200, 1960);
        assertPng("docs/assets/aegis-governance-flow.png", 3200, 1520);
        assertPng("docs/assets/knox-aegis-module-comparison.png", 3400, 2120);
    }

    private static void assertPng(String relative, int expectedWidth, int expectedHeight) throws Exception {
        byte[] png = Files.readAllBytes(ROOT.resolve(relative));
        assertTrue(png.length > 100_000, relative + ": rendered image is not empty or a thumbnail");
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        for (int i = 0; i < signature.length; i++) {
            assertEquals(signature[i], png[i], relative + ": PNG signature byte " + i);
        }
        assertEquals(expectedWidth, ByteBuffer.wrap(png, 16, 4).getInt(), relative + ": pixel width");
        assertEquals(expectedHeight, ByteBuffer.wrap(png, 20, 4).getInt(), relative + ": pixel height");
    }

    @Test
    void landingPageAndHandbookUseTheStaticVisualsAndPublishedEvidence() throws Exception {
        String readme = text("README.md");
        String architecture = text("docs/ARCHITECTURE.md");
        String comparison = text("docs/KNOX-AND-AEGIS-MODULE-COMPARISON.md");
        String index = text("docs/index.html");
        String handbook = text("docs/HANDBOOK.md");
        String webHandbook = text("docs/guide/handbook.html");
        String pages = text("docs/_config.yml");
        assertTrue(Files.isRegularFile(ROOT.resolve("docs/assets/site.css")), "local Pages stylesheet");
        assertTrue(Files.isRegularFile(ROOT.resolve("docs/guide/handbook.css")), "local handbook stylesheet");
        assertTrue(readme.contains("docs/assets/aegis-architecture.svg"), "README architecture visual");
        assertTrue(readme.contains("docs/assets/aegis-governance-flow.png"), "README governance visual");
        assertTrue(readme.contains("docs/HANDBOOK.md"), "README handbook entry point");
        assertTrue(architecture.contains("assets/aegis-architecture.svg"), "architecture document visual");
        assertTrue(architecture.contains("assets/aegis-governance-flow.png"), "architecture document flow visual");
        assertTrue(architecture.contains("assets/aegis-architecture.svg"), "architecture SVG source link");
        assertTrue(architecture.contains("assets/aegis-governance-flow.svg"), "governance SVG source link");
        assertTrue(comparison.contains("assets/knox-aegis-module-comparison.svg"), "Knox composition SVG");
        assertTrue(comparison.contains("assets/knox-aegis-module-comparison.svg"), "Knox comparison SVG source link");
        assertTrue(index.contains("guide/handbook.html"), "Pages handbook entry point");
        assertTrue(index.contains("guide/modules.html") && webHandbook.contains("modules.html"), "Pages module reference entry point");
        assertTrue(index.contains("TUTORIAL-SPARK-ICEBERG.html") && index.contains("TUTORIALS.html"), "Pages tutorial entry points");
        assertTrue(text("docs/TUTORIAL-SPARK-ICEBERG.md").contains("assets/tutorial-spark-iceberg.svg"), "tutorial layout visual");
        for (String tutorialFile : List.of("aegis-spark-iceberg.yaml", "host-own.yaml", "start-catalog.ps1", "start-catalog.sh",
                "start-spark.ps1", "start-spark.sh", "seed-data.ps1", "seed-data.sh", "SeedLake.java", "start-gateway.ps1",
                "start-gateway.sh", "mcp.ps1", "mcp.sh", "agent_client.py", "knox/aegis-spark-iceberg.xml",
                "knox/install-into-knox.ps1", "knox/install-into-knox.sh", "knox/start-knox.ps1", "knox/start-knox.sh",
                "ide/vscode/mcp.json", "ide/vscode/mcp.knox.json", "ide/kiro/mcp.json", "ide/cursor/mcp.json",
                "ide/claude-desktop/claude_desktop_config.json")) {
            assertTrue(Files.isRegularFile(ROOT.resolve("adopters/tutorials/spark-iceberg/" + tutorialFile)),
                    "Spark + Iceberg tutorial file " + tutorialFile);
        }
        // The IDE configurations are valid JSON and point at the two tutorial endpoints.
        for (String ide : List.of("vscode/mcp.json", "vscode/mcp.knox.json", "kiro/mcp.json", "cursor/mcp.json",
                "claude-desktop/claude_desktop_config.json")) {
            String json = text("adopters/tutorials/spark-iceberg/ide/" + ide);
            io.github.vaquarkhan.aegis.engine.json.Json.parseObject(json);
            assertTrue(json.contains("http://127.0.0.1:8080/mcp") || json.contains("/gateway/aegis-spark-iceberg/aegis-mcp/v1/mcp")
                    || json.contains("--stdio"), ide + " targets a tutorial endpoint");
        }
        assertTrue(text("docs/guide/modules.html").contains("../assets/aegis-module-layers.svg"), "module reference layer visual");
        assertTrue(index.contains("assets/aegis-architecture.svg"), "Pages architecture visual");
        assertTrue(index.contains("assets/aegis-governance-flow.png"), "Pages governance visual");
        assertTrue(index.contains("evidence/CLAIMS-MATRIX.html"), "Pages rendered evidence link");
        assertTrue(handbook.contains("# Aegis Gateway handbook"), "Markdown handbook title");
        assertTrue(webHandbook.contains("Aegis Gateway handbook"), "web handbook title");
        assertTrue(webHandbook.contains("../assets/aegis-architecture.svg"), "web handbook architecture visual");
        assertTrue(handbook.contains("assets/mcp-governance-problem-solution.svg"), "handbook MCP governance visual");
        assertTrue(handbook.contains("assets/owasp-mcp-top10-coverage.svg"), "handbook OWASP visual");
        assertTrue(handbook.contains("assets/aegis-mcp-first-host-boundaries.svg"), "handbook MCP-first host boundary visual");
        assertTrue(text("docs/THREAT-MODEL.md").contains("assets/aegis-threat-model.svg"), "threat model boundary visual");
        assertTrue(text("docs/THREAT-MODEL.md").contains("assets/owasp-mcp-top10-coverage.svg"), "threat model OWASP visual");
        assertTrue(webHandbook.contains("../assets/mcp-governance-problem-solution.svg"), "web handbook MCP governance visual");
        assertTrue(webHandbook.contains("../assets/owasp-mcp-top10-coverage.svg"), "web handbook OWASP visual");
        assertTrue(webHandbook.contains("../assets/aegis-mcp-first-host-boundaries.svg"), "web handbook MCP-first host boundary visual");
        assertFalse(pages.contains("- evidence"), "Pages must publish the linked evidence directory");
    }

    /**
     * Every generated project tutorial names a kept two-door run that passed cleanly, and ships IDE files
     * that parse and point at that tutorial's own-host endpoint and Knox route. The developer guide page
     * links the tutorial index.
     */
    @Test
    void projectTutorialsComeFromCleanKeptRunsAndShipWorkingIdeFiles() throws Exception {
        List<String> projects;
        try (var files = Files.list(ROOT.resolve("docs/tutorials"))) {
            projects = files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".md") && !n.equals("README.md"))
                    .map(n -> n.substring(0, n.length() - 3)).sorted().toList();
        }
        assertTrue(projects.size() >= 15, "expected the verified project tutorials, found " + projects);
        String index = text("docs/TUTORIALS.md");
        for (String project : projects) {
            String tutorial = text("docs/tutorials/" + project + ".md");
            assertTrue(index.contains("tutorials/" + project + ".md"), project + " is listed in docs/TUTORIALS.md");
            String report = text("examples/adopters/" + project + "/results/test-report.txt");
            assertTrue(report.contains("Failures: 0, Errors: 0, Skipped: 0"), project + " kept run is clean: " + report);
            java.util.regex.Matcher route = Pattern.compile("https://localhost:8443(/gateway/[^`\\s]+/mcp)").matcher(tutorial);
            assertTrue(route.find(), project + " tutorial names its Knox route");
            for (String ide : List.of("vscode/mcp.json", "vscode/mcp.knox.json", "kiro/mcp.json", "cursor/mcp.json")) {
                String json = text("adopters/tutorials/" + project + "/ide/" + ide);
                io.github.vaquarkhan.aegis.engine.json.Json.parseObject(json);
                assertTrue(json.contains("http://127.0.0.1:8080/mcp") || json.contains(route.group(1)),
                        project + "/" + ide + " targets the tutorial's endpoint");
            }
        }
        assertTrue(text("docs/guide/developer.html").contains("../TUTORIALS.html"), "developer guide links the tutorial index");
        assertTrue(text("docs/index.html").contains("guide/developer.html"), "Pages entry point to the developer guide");
    }

    @Test
    void canonicalOnlyAdopterProfilesMatchTheirSourceConfigurations() throws Exception {
        Map<String, String> canonicalToSource = Map.ofEntries(
                Map.entry("airflow", "airflow"),
                Map.entry("atlas", "atlas"),
                Map.entry("cassandra", "cassandra"),
                Map.entry("couchdb", "couchdb"),
                Map.entry("doris", "doris"),
                Map.entry("druid", "druid"),
                Map.entry("hadoop", "hadoop"),
                Map.entry("hbase", "hbase"),
                Map.entry("hive", "hive"),
                Map.entry("impala", "impala"),
                Map.entry("nifi", "nifi"),
                Map.entry("paimon", "paimon"),
                Map.entry("pinot", "pinot"),
                Map.entry("ranger", "ranger"),
                Map.entry("storm", "storm"),
                Map.entry("superset", "superset"));
        assertEquals(16, canonicalToSource.size(), "update this list only when adopter evidence status changes");
        for (Map.Entry<String, String> profile : canonicalToSource.entrySet()) {
            String adopter = profile.getKey();
            String canonical = "examples/adopters/" + adopter + "/aegis/canonical.yaml";
            String source = "adopters/configurations/adapters/" + profile.getValue() + "/" + profile.getValue() + ".yaml";
            assertEquals(text(source), text(canonical), adopter + ": canonical profile copy drifted from the shipped configuration");
        }
    }

    /** Build output, downloaded engine distributions and dependencies are not project documentation. */
    private static boolean isSource(Path p) {
        for (Path segment : ROOT.relativize(p.normalize())) {
            String s = segment.toString();
            if (s.equals("target") || s.equals("work") || s.equals("node_modules") || s.equals(".git")) {
                return false;
            }
        }
        return true;
    }

    /** GitHub's heading anchor: lower case, punctuation other than '-' and '_' dropped, spaces to '-'. */
    static String anchor(String heading) {
        String h = heading.replace("`", "").strip().toLowerCase(Locale.ROOT);
        h = h.replaceAll("[^\\p{L}\\p{N}_\\- ]", "");
        return h.replace(' ', '-');
    }

    private static Set<String> anchors(Path markdown) throws Exception {
        String body = FENCE.matcher(Files.readString(markdown)).replaceAll("");
        Set<String> out = new HashSet<>();
        Map<String, Integer> seen = new HashMap<>();
        Matcher m = HEADING.matcher(body);
        while (m.find()) {
            String a = anchor(m.group(1));
            int n = seen.merge(a, 1, Integer::sum) - 1;
            out.add(n == 0 ? a : a + "-" + n);
        }
        return out;
    }

    /**
     * Every relative link and image in the 2.0 Markdown and HTML resolves to a file, and every
     * {@code #anchor} into a Markdown file names a heading that exists. Moving a document or renaming
     * a heading therefore fails the build instead of leaving a dead link for a reviewer to find.
     */
    @Test
    void publishedPagesUseRenderedMarkdownRoutes() throws Exception {
        for (String relative : List.of("docs/index.html", "docs/guide/handbook.html", "docs/guide/modules.html",
                "docs/guide/developer.html")) {
            String body = FENCE.matcher(text(relative)).replaceAll("");
            Matcher matcher = LINK.matcher(body);
            while (matcher.find()) {
                String target = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
                if (!target.matches("^(https?:|mailto:|data:|#).*") && target.substring(0, target.indexOf('#') < 0 ? target.length() : target.indexOf('#')).endsWith(".md")) {
                    throw new AssertionError(relative + ": public Pages link must use Jekyll's rendered .html route: " + target);
                }
            }
        }
    }

    @Test
    void everyRelativeLinkAndAnchorResolves() throws Exception {
        List<String> broken = new ArrayList<>();
        List<Path> documents;
        try (var files = Files.walk(ROOT)) {
            documents = files.filter(DocumentationAssetsTest::isSource)
                    .filter(p -> p.toString().endsWith(".md") || p.toString().endsWith(".html")).toList();
        }
        for (Path doc : documents) {
            String body = FENCE.matcher(Files.readString(doc)).replaceAll("");
            Matcher m = LINK.matcher(body);
            while (m.find()) {
                String target = m.group(1) != null ? m.group(1) : m.group(2);
                if (target.matches("^(https?:|mailto:|data:).*")) {
                    continue;
                }
                int hash = target.indexOf('#');
                String path = hash < 0 ? target : target.substring(0, hash);
                String fragment = hash < 0 ? "" : target.substring(hash + 1);
                Path dest = path.isEmpty() ? doc : doc.getParent().resolve(path).normalize();
                if (!Files.exists(dest) && isPublishedPage(doc) && path.endsWith(".html")) {
                    Path markdownSource = dest.resolveSibling(dest.getFileName().toString().replaceFirst("\\.html$", ".md"));
                    if (Files.isRegularFile(markdownSource)) {
                        dest = markdownSource;
                    }
                }
                if (!Files.exists(dest)) {
                    broken.add(ROOT.relativize(doc) + " -> " + target + " (missing file)");
                } else if (!fragment.isEmpty() && dest.toString().endsWith(".md") && !anchors(dest).contains(fragment)) {
                    broken.add(ROOT.relativize(doc) + " -> " + target + " (missing heading)");
                }
            }
        }
        assertTrue(broken.isEmpty(), "broken documentation links:\n  " + String.join("\n  ", broken));
    }

    private static boolean isPublishedPage(Path document) {
        return document.normalize().startsWith(ROOT.resolve("docs").normalize())
                && document.toString().endsWith(".html");
    }

    /**
     * Verifies every Mermaid block is present in the source tree and renders every block when a local
     * CLI is explicitly supplied. The default build never downloads a renderer:
     * {@code -Daegis.mmdc=C:\\tools\\mmdc.ps1} (or {@code AEGIS_MMDC}).
     */
    @Test
    void mermaidBlocksRenderWhenALocalCliIsConfigured() throws Exception {
        List<MermaidBlock> blocks = mermaidBlocks();
        assertFalse(blocks.isEmpty(), "expected at least one Mermaid block");
        String renderer = System.getProperty("aegis.mmdc", System.getenv("AEGIS_MMDC"));
        if (renderer == null || renderer.isBlank()) {
            return;
        }
        Path executable = Path.of(renderer);
        assertTrue(Files.isRegularFile(executable), "configured Mermaid CLI must be a local file: " + executable);

        Path work = Files.createTempDirectory("aegis-mermaid-");
        try {
            for (int i = 0; i < blocks.size(); i++) {
                MermaidBlock block = blocks.get(i);
                Path input = work.resolve("diagram-" + i + ".mmd");
                Path output = work.resolve("diagram-" + i + ".png");
                Path log = work.resolve("diagram-" + i + ".log");
                Files.writeString(input, block.source(), StandardCharsets.UTF_8);
                Process process = new ProcessBuilder(rendererCommand(executable, input, output))
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile())
                        .start();
                boolean completed = process.waitFor(90, TimeUnit.SECONDS);
                if (!completed) {
                    process.destroyForcibly();
                }
                String detail = Files.exists(log) ? Files.readString(log) : "";
                assertTrue(completed, block.document() + " Mermaid block " + (i + 1) + ": renderer timed out");
                assertEquals(0, process.exitValue(), block.document() + " Mermaid block " + (i + 1)
                        + ": renderer failed:\n" + detail);
                assertTrue(Files.size(output) > 100, block.document() + " Mermaid block " + (i + 1)
                        + ": renderer produced no PNG");
            }
        } finally {
            try (var files = Files.walk(work)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                        // The test result already reflects render success; OS cleanup may occur after the JVM exits.
                    }
                });
            }
        }
    }

    private static List<String> rendererCommand(Path executable, Path input, Path output) {
        Path node = executable.getParent().resolve("node.exe");
        Path cli = executable.getParent().resolve("node_modules/@mermaid-js/mermaid-cli/src/cli.js");
        List<String> command = new ArrayList<>();
        if (Files.isRegularFile(node) && Files.isRegularFile(cli)) {
            command.add(node.toString());
            command.add(cli.toString());
        } else if (executable.toString().toLowerCase(Locale.ROOT).endsWith(".ps1")) {
            command.add("powershell");
            command.add("-NoProfile");
            command.add("-ExecutionPolicy");
            command.add("Bypass");
            command.add("-File");
            command.add(executable.toString());
        } else {
            command.add(executable.toString());
        }
        command.add("-i");
        command.add(input.toString());
        command.add("-o");
        command.add(output.toString());
        command.add("--quiet");
        return command;
    }

    private static List<MermaidBlock> mermaidBlocks() throws Exception {
        List<MermaidBlock> blocks = new ArrayList<>();
        try (var files = Files.walk(ROOT.resolve("docs"))) {
            for (Path document : files.filter(DocumentationAssetsTest::isSource)
                    .filter(path -> path.toString().endsWith(".md")).toList()) {
                Matcher matcher = MERMAID_BLOCK.matcher(Files.readString(document));
                while (matcher.find()) {
                    String source = matcher.group(1).strip();
                    assertFalse(source.isEmpty(), ROOT.relativize(document) + ": empty Mermaid block");
                    blocks.add(new MermaidBlock(ROOT.relativize(document).toString(), source));
                }
            }
        }
        return blocks;
    }

    private record MermaidBlock(String document, String source) {}

    /** UTF-8 text that was once decoded as cp1252 (for example "Â§" for "§") must not ship. */
    @Test
    void documentationHasNoDoubleEncodedCharacters() throws Exception {
        List<String> bad = new ArrayList<>();
        try (var files = Files.walk(ROOT)) {
            for (Path p : files.filter(DocumentationAssetsTest::isSource)
                    .filter(p -> p.toString().matches(".*\\.(md|html|svg|yaml|yml)$")).toList()) {
                String text = Files.readString(p);
                if (text.contains("\u00c2\u00a7") || text.contains("\u00e2\u20ac") || text.contains("\u00e2\u2020")
                        || text.contains("\u00c2\u00b7") || text.indexOf('\ufffd') >= 0) {
                    bad.add(ROOT.relativize(p).toString());
                }
            }
        }
        assertTrue(bad.isEmpty(), "double-encoded characters in: " + bad);
    }
}
