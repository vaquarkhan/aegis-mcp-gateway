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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Writes down what a run actually used, so an adopter example folder explains itself: the Aegis YAML each
 * front door loaded, the Knox topology, the command line each engine was started with, and a generated
 * {@code HOW-TO-RUN.md} with the same steps for a person to repeat by hand. Nothing here is written from
 * memory: every file is produced from the objects the run used, with secrets replaced by their names.
 */
public final class RunRecord {

    static final String HEADER_HASH = """
            # Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
            #
            # Licensed under the Apache License, Version 2.0 (the "License");
            # you may not use this file except in compliance with the License.
            # You may obtain a copy of the License at
            #
            #     http://www.apache.org/licenses/LICENSE-2.0
            #
            # Unless required by applicable law or agreed to in writing, software
            # distributed under the License is distributed on an "AS IS" BASIS,
            # WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
            # See the License for the specific language governing permissions and
            # limitations under the License.
            """;

    static final String HEADER_XML = """
            <!--
              Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors

              Licensed under the Apache License, Version 2.0 (the "License");
              you may not use this file except in compliance with the License.
              You may obtain a copy of the License at

                  http://www.apache.org/licenses/LICENSE-2.0

              Unless required by applicable law or agreed to in writing, software
              distributed under the License is distributed on an "AS IS" BASIS,
              WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
              See the License for the specific language governing permissions and
              limitations under the License.
            -->
            """;

    private static final Pattern SECRET_NAME = Pattern.compile("(?i).*(TOKEN|PASSWORD|SECRET|KEY|CREDENTIAL|DIGEST).*");
    private static final String[] HOME_VARS = {"AEGIS_KNOX_HOME", "AEGIS_SOLR_HOME", "AEGIS_MCP_FILESYSTEM_JS",
        "AEGIS_MCP_PYTHON", "AEGIS_BURR_PYTHON", "AEGIS_NODE"};

    /** One engine process a test started: its command line, working directory and environment. */
    record Engine(String testClass, List<String> command, Path workDir, Map<String, String> env, String classpathFile) {}

    private static final List<Engine> ENGINES = Collections.synchronizedList(new ArrayList<>());

    private RunRecord() {}

    static void engineStarted(List<String> command, Path workDir, Map<String, String> env, String classpathFile) {
        ENGINES.add(new Engine(callerTestClass(), List.copyOf(command), workDir, Map.copyOf(env), classpathFile));
    }

    /** The adopter test class on the stack (package ...validation, not ...validation.frontdoor). */
    static String callerTestClass() {
        return StackWalker.getInstance().walk(s -> s.map(StackWalker.StackFrame::getClassName)
                .map(c -> c.replaceFirst("\\$.*$", ""))
                .filter(c -> c.startsWith("io.github.vaquarkhan.aegis.validation.") && c.endsWith("FrontDoorsTest"))
                .findFirst().orElse("unknown"));
    }

    /** For an engine a test starts itself in a separate process (outside {@link JavaServer}). */
    public static void engineProcess(List<String> command, Path workDir, Map<String, String> env, String classpathFile) {
        engineStarted(command, workDir, env, classpathFile);
    }

    /** For an engine the test runs inside its own JVM: a plain description of how, instead of a command line. */
    public static void engineInProcess(String markdown) {
        NOTES.add(new String[] {callerTestClass(), markdown});
    }

    private static final List<String[]> NOTES = Collections.synchronizedList(new ArrayList<>());
    private static final List<String[]> FILES = Collections.synchronizedList(new ArrayList<>());

    /**
     * Keeps an engine configuration file the test wrote or edited (not on the command line), with a sentence on why.
     * Never pass a file holding a credential.
     */
    public static void engineFile(Path file, String why) {
        FILES.add(new String[] {callerTestClass(), file.toAbsolutePath().toString(), why});
    }

    static Path repoRoot() {
        return Path.of("..", "..").toAbsolutePath().normalize();
    }

    /** Makes a machine path portable and secret-free for a document. */
    static String portable(String s) {
        String out = s;
        for (String v : HOME_VARS) {
            String val = System.getenv(v);
            if (val != null && !val.isBlank()) {
                out = replacePath(out, Path.of(val).toAbsolutePath().normalize().toString(), "$" + v);
            }
        }
        out = replacePath(out, repoRoot().toString(), "$REPO");
        out = replacePath(out, Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize().toString(), "$TMPDIR");
        out = replacePath(out, Path.of(System.getProperty("java.home"), "bin", "java").toString(), "java");
        out = replacePath(out, Path.of(System.getProperty("user.home")).toString(), "$HOME");
        return out;
    }

    /** {@link #portable} for a single path or command-line word: also uses forward slashes. */
    static String portablePath(String s) {
        String p = portable(s);
        return p.startsWith("$") || p.contains("/$REPO") || p.contains("=$") ? p.replace('\\', '/') : p;
    }

    private static String replacePath(String s, String path, String with) {
        return s.replace(path, with).replace(path.replace('\\', '/'), with);
    }

    /** Writes a file under {@code <results>/<adopter>/<relative>}. */
    static void write(String adopter, String relative, String content) throws IOException {
        Path p = results(adopter).resolve(relative);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    static Path results(String adopter) {
        return Path.of(System.getProperty("aegis.results.dir", "target/adopter-results"), adopter);
    }

    /** The engines the calling test class started, as Markdown. */
    static String enginesSection(String testClass) {
        StringBuilder md = new StringBuilder();
        List<Engine> mine;
        synchronized (ENGINES) {
            mine = ENGINES.stream().filter(e -> e.testClass().equals(testClass)).toList();
        }
        synchronized (NOTES) {
            NOTES.stream().filter(n -> n[0].equals(testClass)).forEach(n -> md.append(n[1]).append("\n\n"));
        }
        synchronized (FILES) {
            for (String[] f : FILES.stream().filter(x -> x[0].equals(testClass)).toList()) {
                String kept = keepConfigFile(adopterOf(testClass), f[1]);
                if (kept != null) {
                    md.append("[`engine/").append(kept).append("`](engine/").append(kept).append("): ").append(f[2]).append("\n\n");
                }
            }
        }
        if (mine.isEmpty()) {
            return md.length() > 0 ? md.toString() : "The test started no separate engine process for this example.\n";
        }
        md.append(mine.size() == 1 ? "The test started one engine process, the way the engine's own launcher script starts it:\n\n"
                : "The test started these processes, in this order, the way the engine's own launcher scripts start them:\n\n");
        int n = 0;
        for (Engine e : mine) {
            n++;
            md.append("Process ").append(n).append(e.workDir() == null ? "" : ", started in `" + portablePath(e.workDir().toString()) + "`")
                    .append(":\n\n```text\n");
            List<String> cmd = new ArrayList<>(e.command());
            int cp = cmd.indexOf("-cp");
            String cpNote = null;
            if (cp >= 0 && cp + 1 < cmd.size() && e.classpathFile() != null) {
                String file = portablePath(Path.of(e.classpathFile()).toAbsolutePath().normalize().toString());
                cmd.set(cp + 1, "<the jars listed in " + file + ">");
                cpNote = "`" + file + "` is written by the `-Preal-engines` build of that module: the engine's jars, at the "
                        + "version its `pom.xml` names, as Maven resolved them. With a binary distribution, use its own `lib/` instead.";
            }
            List<String> copied = new ArrayList<>();
            for (int i = 0; i < cmd.size(); i++) {
                String kept = keepConfigFile(adopterOf(testClass), cmd.get(i));
                if (kept != null) {
                    copied.add(kept);
                    cmd.set(i, "engine/" + kept);
                }
            }
            for (int i = 0; i < cmd.size(); i++) {
                md.append(i == 0 ? "" : "  ").append(portablePath(cmd.get(i))).append(i == cmd.size() - 1 ? "\n" : " \\\n");
            }
            md.append("```\n\n");
            if (cpNote != null) {
                md.append(cpNote).append("\n\n");
            }
            for (String c : copied) {
                md.append("The engine's own configuration file, as the test wrote it: [`engine/").append(c)
                        .append("`](engine/").append(c).append(").\n\n");
            }
            if (!e.env().isEmpty()) {
                md.append("Environment:\n\n");
                e.env().forEach((k, v) -> md.append("- `").append(k).append("` = ")
                        .append(SECRET_NAME.matcher(k).matches() ? "(a value of your choice; not recorded)" : "`" + portable(v) + "`")
                        .append('\n'));
                md.append('\n');
            }
        }
        return md.toString();
    }

    private static final java.util.Map<String, String> ADOPTER_OF_TEST = new java.util.concurrent.ConcurrentHashMap<>();

    /** Remembers which adopter folder a test class writes to (set when its first front door opens). */
    static void adopterFor(String testClass, String adopter) {
        ADOPTER_OF_TEST.putIfAbsent(testClass, adopter);
    }

    private static String adopterOf(String testClass) {
        return ADOPTER_OF_TEST.getOrDefault(testClass, "unknown");
    }

    /**
     * If {@code arg} is a small text configuration file the test wrote for the engine (under a {@code target/}
     * directory), copies it to {@code <results>/<adopter>/engine/} with a license header and returns its name.
     */
    private static String keepConfigFile(String adopter, String arg) {
        try {
            if (arg.startsWith("-") || arg.length() > 400) {
                return null;
            }
            Path p = Path.of(arg);
            String name = p.getFileName() == null ? "" : p.getFileName().toString();
            boolean config = name.matches(".*\\.(xml|cfg|properties|conf|yaml|yml)$");
            if (!config || !Files.isRegularFile(p) || Files.size(p) > 64 * 1024
                    || !p.toAbsolutePath().normalize().toString().contains("target")) {
                return null;
            }
            String text = Files.readString(p, StandardCharsets.UTF_8);
            String withHeader;
            if (name.endsWith(".xml")) {
                int decl = text.startsWith("<?xml") ? text.indexOf("?>") + 2 : 0;
                withHeader = text.substring(0, decl) + (decl > 0 ? "\n" : "") + HEADER_XML + text.substring(decl).stripLeading();
            } else {
                withHeader = HEADER_HASH + text;
            }
            write(adopter, "engine/" + name, portable(withHeader));
            return name;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Lists environment variable names a door needs, with their purpose, never their value. */
    static String envList(Map<String, String> env) {
        StringBuilder md = new StringBuilder();
        Map<String, String> sorted = new LinkedHashMap<>();
        env.keySet().stream().sorted().forEach(k -> sorted.put(k, env.get(k)));
        sorted.forEach((k, v) -> {
            String what = k.startsWith("AEGIS_VALIDATION_TOKEN_") ? "bearer token for that persona, at least 16 characters"
                    : "AEGIS_SIGNING_KEY".equals(k) ? "approval signing key, at least 32 bytes"
                    : SECRET_NAME.matcher(k).matches() ? "credential the adopter YAML reads"
                    : "value `" + portable(v) + "`";
            md.append("- `").append(k).append("`: ").append(what).append('\n');
        });
        return md.toString();
    }

    /** Rebuilds {@code HOW-TO-RUN.md} from the section files the front doors have written so far. */
    static void assemble(String adopter, String testClass) throws IOException {
        Path dir = results(adopter);
        String simple = testClass.substring(testClass.lastIndexOf('.') + 1);
        String testPath = Files.exists(repoRoot().resolve("examples/aegis-validation/src/test/java/io/github/vaquarkhan/aegis/validation/" + simple + ".java"))
                ? "examples/aegis-validation/src/test/java/io/github/vaquarkhan/aegis/validation/" + simple + ".java"
                : "examples/aegis-validation-spark-connect/src/test/java/io/github/vaquarkhan/aegis/validation/" + simple + ".java";
        StringBuilder md = new StringBuilder(HEADER_XML);
        md.append("# ").append(adopter).append(": how this example ran, and how to run it by hand\n\n")
                .append("Generated by the test harness during the run of `").append(simple).append("` on ")
                .append(LocalDate.now()).append(". Every command, file and step below is what that run used; ")
                .append("machine paths are replaced by `$REPO` (the repository root), `$TMPDIR`, `$HOME` or the install ")
                .append("variables (`$AEGIS_KNOX_HOME` and so on), and secrets are shown by name only. Ports are the free ")
                .append("ports that run picked.\n\n")
                .append("The test that drives it: [`").append(simple).append(".java`](../../../").append(testPath).append(").\n\n")
                .append("## 1. The engine\n\n");
        Path engine = dir.resolve("how-to-run").resolve("engine.md");
        md.append(Files.exists(engine) ? Files.readString(engine) : "(not recorded)\n");
        md.append("\nThe data the scenario works on (topics, tables, queues, jobs) is created by the test's `@BeforeAll` ")
                .append("method against the engine's own API, outside Aegis; see the test source.\n\n");
        for (String door : List.of(FrontDoor.OWN_HOST, FrontDoor.KNOX)) {
            Path section = dir.resolve("how-to-run").resolve(door + ".md");
            if (Files.exists(section)) {
                md.append(Files.readString(section)).append('\n');
            } else {
                md.append("## ").append(FrontDoor.OWN_HOST.equals(door) ? "2. Without Knox" : "3. With Knox")
                        .append("\n\nNot run in the recorded run.\n\n");
            }
        }
        Files.writeString(dir.resolve("HOW-TO-RUN.md"), md.toString(), StandardCharsets.UTF_8);
    }
}
