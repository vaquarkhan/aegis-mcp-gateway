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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;

/**
 * A real product container started with the host Docker CLI. Used when the adopter's engine is
 * published as an official image and has no lightweight in-JVM launcher. Skipped (never passed) when
 * {@code docker} is missing or the daemon is not reachable.
 */
public final class DockerContainer implements AutoCloseable {

    private final String name;
    private final Path log;

    private DockerContainer(String name, Path log) {
        this.name = name;
        this.log = log;
    }

    /** Fails the assumption (test skipped) when Docker is not usable on this host. */
    public static void requireDocker() {
        try {
            Process p = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            Assumptions.assumeTrue(p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0 && !out.isBlank(),
                    "docker is not available (SKIPPED, not passed): " + out);
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "docker is not available (SKIPPED, not passed): " + e);
        }
    }

    /**
     * Starts {@code image} with {@code docker run} options (before the image) and an optional
     * container command (after the image). Removed on close. Callers pass {@code -p}/{@code -e} in
     * {@code runArgs}.
     */
    public static DockerContainer start(String name, String image, List<String> runArgs, Path log)
            throws Exception {
        return start(name, image, runArgs, List.of(), log);
    }

    /** As {@link #start(String, String, List, Path)} with a container entrypoint command after the image. */
    public static DockerContainer start(String name, String image, List<String> runArgs, List<String> command,
            Path log) throws Exception {
        requireDocker();
        Files.createDirectories(log.getParent());
        // Force-remove any prior container with this name and wait until the name is free.
        // A slow concurrent `docker rm` from a previous JVM can otherwise leave Status=removing
        // under the new run and fail awaitReady within seconds.
        exec(List.of("docker", "rm", "-f", name), Duration.ofMinutes(2), false);
        awaitNameGone(name, Duration.ofMinutes(2));
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("run");
        cmd.add("-d");
        cmd.add("--name");
        cmd.add(name);
        cmd.addAll(runArgs);
        cmd.add(image);
        cmd.addAll(command);
        RunRecord.engineStarted(cmd, null, java.util.Map.of(), null);
        exec(cmd, Duration.ofMinutes(5), true);
        return new DockerContainer(name, log);
    }

    public void awaitReady(KnoxRuntime.Check ready, Duration limit, String what) throws Exception {
        long deadline = System.nanoTime() + limit.toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            String state = execCapture(List.of("docker", "inspect", "-f", "{{.State.Status}}", name),
                    Duration.ofSeconds(10)).trim();
            if ("removing".equals(state) || "created".equals(state) || "restarting".equals(state)) {
                Thread.sleep(500);
                continue;
            }
            if (!"running".equals(state)) {
                dumpLogs();
                throw new IllegalStateException(what + " container is " + state);
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
        dumpLogs();
        throw new IllegalStateException("timed out waiting for " + what, last);
    }

    private static void awaitNameGone(String name, Duration limit) throws Exception {
        long deadline = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < deadline) {
            Process p = new ProcessBuilder("docker", "inspect", name).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("timed out inspecting " + name + " during pre-start cleanup");
            }
            if (p.exitValue() != 0) {
                return;
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("container name still present after rm: " + name);
    }

    public String name() {
        return name;
    }

    private void dumpLogs() throws IOException, InterruptedException {
        String body = execCapture(List.of("docker", "logs", "--tail", "200", name), Duration.ofSeconds(30));
        Files.writeString(log, body);
    }

    @Override
    public void close() throws Exception {
        try {
            dumpLogs();
        } catch (Exception ignored) {
            // best effort
        }
        exec(List.of("docker", "rm", "-f", name), Duration.ofMinutes(2), false);
        awaitNameGone(name, Duration.ofMinutes(2));
    }

    private static void exec(List<String> cmd, Duration limit, boolean failOnError)
            throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(limit.toMillis(), TimeUnit.MILLISECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("timed out: " + cmd + "\n" + out);
        }
        if (failOnError && p.exitValue() != 0) {
            throw new IllegalStateException("failed (" + p.exitValue() + "): " + cmd + "\n" + out);
        }
    }

    private static String execCapture(List<String> cmd, Duration limit)
            throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(limit.toMillis(), TimeUnit.MILLISECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("timed out: " + cmd);
        }
        return out;
    }
}
