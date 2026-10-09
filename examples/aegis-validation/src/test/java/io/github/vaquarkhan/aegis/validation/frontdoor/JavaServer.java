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
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A real Apache server started in its own JVM from the runtime classpath Maven resolved for it (a
 * {@code target/server.classpath} file), the way its own launcher script would start it. Keeping the
 * engine out of the test JVM avoids classpath clashes and matches a deployment, where the gateway and
 * the engine are separate processes.
 */
public final class JavaServer implements AutoCloseable {

    private final Process process;
    private final Path log;

    public JavaServer(String classpathProperty, List<String> jvmArgs, String mainClass, List<String> args, Path log)
            throws IOException {
        this(classpathProperty, jvmArgs, mainClass, args, java.util.Map.of(), log);
    }

    /** As above, with environment variables for servers configured that way (for example CATALOG_* for Iceberg). */
    public JavaServer(String classpathProperty, List<String> jvmArgs, String mainClass, List<String> args,
            java.util.Map<String, String> env, Path log) throws IOException {
        this(readClasspath(classpathProperty), jvmArgs, mainClass, args, env, log, System.getProperty(classpathProperty));
    }

    private JavaServer(String classpath, List<String> jvmArgs, String mainClass, List<String> args,
            java.util.Map<String, String> env, Path log, String classpathFile) throws IOException {
        List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString()));
        cmd.addAll(jvmArgs);
        cmd.add("-cp");
        cmd.add(classpath);
        cmd.add(mainClass);
        cmd.addAll(args);
        Files.createDirectories(log.getParent());
        this.log = log;
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile());
        pb.environment().putAll(env);
        this.process = pb.start();
        RunRecord.engineStarted(cmd, null, env, classpathFile);
    }

    /**
     * A server from an unpacked Apache binary distribution, started the way its own launcher script
     * starts it: {@code classpath} is the launcher jar (or jars) inside the distribution, and the JVM
     * arguments are the system properties the script sets.
     */
    public static JavaServer fromDistribution(String classpath, List<String> jvmArgs, String mainClass, List<String> args,
            java.util.Map<String, String> env, Path log) throws IOException {
        return new JavaServer(classpath, jvmArgs, mainClass, args, env, log, (String) null);
    }

    /** The unpacked distribution directory a {@code -Preal-engines} server module wrote, from a system property. */
    public static Path distribution(String homeProperty) {
        String home = System.getProperty(homeProperty, "");
        if (home.isBlank() || !Files.isDirectory(Path.of(home))) {
            throw new IllegalStateException(homeProperty + " is not set or missing (build with -Preal-engines): " + home);
        }
        return Path.of(home).toAbsolutePath().normalize();
    }

    private static String readClasspath(String classpathProperty) throws IOException {
        String cpFile = System.getProperty(classpathProperty, "");
        if (cpFile.isBlank() || !Files.isRegularFile(Path.of(cpFile))) {
            throw new IllegalStateException(classpathProperty + " is not set or missing (build with -Preal-engines): " + cpFile);
        }
        return Files.readString(Path.of(cpFile), StandardCharsets.UTF_8).trim();
    }

    private JavaServer(List<String> command, java.util.Map<String, String> env, Path log, Path workDir) throws IOException {
        Files.createDirectories(log.getParent());
        this.log = log;
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        if (workDir != null) {
            pb.directory(workDir.toFile());
        }
        pb.environment().putAll(env);
        this.process = pb.start();
        RunRecord.engineStarted(command, workDir, env, null);
    }

    /** As {@link #process(List, java.util.Map, Path)}, from a working directory, for launchers that depend on it. */
    public static JavaServer process(List<String> command, java.util.Map<String, String> env, Path log, Path workDir)
            throws IOException {
        return new JavaServer(List.copyOf(command), env, log, workDir);
    }

    /** The {@code java} executable of the JVM running the test, for servers launched with their own options. */
    public static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /**
     * A server that is not a JVM (for example a Python MCP server), started from an explicit command line.
     * Same lifecycle as the JVM servers: own process, own log, readiness check, stopped after the test.
     */
    public static JavaServer process(List<String> command, java.util.Map<String, String> env, Path log) throws IOException {
        return new JavaServer(List.copyOf(command), env, log, null);
    }

    /** Waits until {@code ready} succeeds, failing fast if the server JVM exits. */
    public void awaitReady(KnoxRuntime.Check ready, Duration limit, String what) throws Exception {
        long deadline = System.nanoTime() + limit.toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException(what + " exited; log: " + Files.readString(log));
            }
            try {
                if (ready.ok()) {
                    return;
                }
            } catch (Exception e) {
                last = e;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("timed out waiting for " + what + "; log " + log, last);
    }

    /** For a one-shot tool (for example a storage format step): waits for it to exit and returns its exit code. */
    public int awaitExit(Duration limit) throws Exception {
        if (!process.waitFor(limit.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("did not finish within " + limit + "; log " + log);
        }
        return process.exitValue();
    }

    public Path log() {
        return log;
    }

    public static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    public static boolean listening(int port) {
        return KnoxRuntime.listening(port);
    }

    @Override
    public void close() throws Exception {
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
        }
    }
}
