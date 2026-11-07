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
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * A stock Apache Knox 3.0.0 installation, run the way {@code bin/gateway.sh start} runs it
 * ({@code java -jar bin/gateway.jar}), plus the bundled demo LDAP ({@code bin/ldap.jar}). Nothing in
 * Knox is patched: the harness only drops the Aegis {@code knox-bundle} jar into {@code ext/}, sets
 * the listener port in {@code conf/gateway-site.xml}, issues a {@code localhost} TLS certificate with
 * {@code knoxcli}, and writes one topology. Both processes are stopped on close.
 */
final class KnoxRuntime implements AutoCloseable {

    /** Master secret for the throwaway validation keystore; never a production value. */
    static final String MASTER = "aegis-validation-master-secret";
    static final int LDAP_PORT = 33389;
    private static final String PORT_MARKER = "<!-- aegis-validation -->";

    private final Path home;
    private final int port;
    private final List<Process> processes = new ArrayList<>();
    private final Path topology;
    private final SSLContext tls;

    static Path home() {
        String h = System.getenv("AEGIS_KNOX_HOME");
        return h == null || h.isBlank() ? null : Path.of(h).toAbsolutePath().normalize();
    }

    KnoxRuntime(Path home, String topologyName, String topologyXml, Map<String, String> env, Path logDir) throws Exception {
        this(home, topologyName, topologyXml, env, logDir, List.of(), List.of());
    }

    KnoxRuntime(Path home, String topologyName, String topologyXml, Map<String, String> env, Path logDir,
            List<Path> plugins, List<String> jvmArgs) throws Exception {
        this.home = home;
        if (!Files.isRegularFile(home.resolve("bin/gateway.jar"))) {
            throw new IllegalStateException("not a Knox distribution: " + home);
        }
        Files.createDirectories(logDir);
        installBundle();
        installPlugins(plugins, logDir);
        if (!Files.isRegularFile(home.resolve("data/security/master"))) {
            knoxcli(logDir, "create-master", "--master", MASTER, "--force");
        }
        // A certificate whose CN is localhost, so the client verifies the hostname as well as the chain.
        knoxcli(logDir, "create-cert", "--hostname", "localhost", "--force");
        port = freePort();
        patchSite(port);
        try (Stream<Path> old = Files.list(home.resolve("conf/topologies"))) {
            for (Path p : old.filter(p -> p.getFileName().toString().startsWith("aegis-")).toList()) {
                Files.delete(p);
            }
        }
        topology = home.resolve("conf/topologies/" + topologyName + ".xml");
        Files.writeString(topology, topologyXml, StandardCharsets.UTF_8);

        if (!listening(LDAP_PORT)) {
            processes.add(start(logDir.resolve("knox-ldap.log"), Map.of(), List.of(), "bin/ldap.jar", "conf"));
            waitUntil(() -> listening(LDAP_PORT), Duration.ofSeconds(60), "demo LDAP on " + LDAP_PORT);
        }
        processes.add(start(logDir.resolve("knox-gateway.out"), env, jvmArgs, "bin/gateway.jar"));
        waitUntil(() -> listening(port), Duration.ofSeconds(120), "Knox gateway on " + port);
        tls = trustKnoxIdentity();
    }

    int port() {
        return port;
    }

    SSLContext tls() {
        return tls;
    }

    Path gatewayLog() {
        return home.resolve("logs/gateway.log");
    }

    private void installBundle() throws IOException {
        Path bundle = Path.of(System.getProperty("aegis.knox.bundle", "")).toAbsolutePath().normalize();
        if (!Files.isRegularFile(bundle)) {
            throw new IllegalStateException("build aegis-knox3-extension first (mvn install); missing " + bundle);
        }
        try (Stream<Path> ext = Files.list(home.resolve("ext"))) {
            for (Path p : ext.filter(p -> p.getFileName().toString().startsWith("aegis-")).toList()) {
                Files.delete(p);
            }
        }
        Files.copy(bundle, home.resolve("ext/" + bundle.getFileName()), StandardCopyOption.REPLACE_EXISTING);
    }

    private void patchSite(int port) throws IOException {
        Path site = home.resolve("conf/gateway-site.xml");
        String xml = Files.readString(site);
        xml = xml.replaceAll("(?s)<name>gateway\\.port</name>\\s*<value>\\d+</value>",
                "<name>gateway.port</name>\n        <value>" + port + "</value>");
        if (!xml.contains(PORT_MARKER)) {
            xml = xml.replaceFirst("<configuration>", "<configuration>\n    " + PORT_MARKER
                    + "\n    <property><name>gateway.host</name><value>127.0.0.1</value></property>");
        }
        Files.writeString(site, xml);
    }

    private void knoxcli(Path logDir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(java(), "-jar", "bin/knoxcli.jar"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(home.toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logDir.resolve("knoxcli.log").toFile())).start();
        if (!p.waitFor(120, TimeUnit.SECONDS) || p.exitValue() != 0) {
            p.destroyForcibly();
            throw new IllegalStateException("knoxcli " + String.join(" ", args) + " failed; see " + logDir.resolve("knoxcli.log"));
        }
    }

    /**
     * Copies a source plugin and its runtime dependencies into {@code ext/}. A jar whose artifact Knox
     * already ships in {@code lib/} or {@code dep/} is skipped, and so is every logging binding or bridge,
     * so Knox keeps its own versions and its own logging. The decision for each jar is logged.
     */
    private void installPlugins(List<Path> jars, Path logDir) throws IOException {
        if (jars.isEmpty()) {
            return;
        }
        java.util.Set<String> shipped = new java.util.HashSet<>();
        for (String d : List.of("lib", "dep")) {
            try (Stream<Path> s = Files.list(home.resolve(d))) {
                s.forEach(p -> shipped.add(artifact(p.getFileName().toString())));
            }
        }
        StringBuilder report = new StringBuilder("# ext/ plugin installation (ADD = copied, KEEP = Knox already ships it, SKIP = logging)\n");
        for (Path jar : jars) {
            String name = jar.getFileName().toString();
            String a = artifact(name);
            if (a.startsWith("log4j") || a.startsWith("slf4j") || a.endsWith("-to-slf4j") || a.endsWith("-over-slf4j")
                    || a.startsWith("logback")) {
                report.append("SKIP ").append(name).append('\n');
            } else if (shipped.contains(a)) {
                report.append("KEEP ").append(name).append('\n');
            } else {
                Files.copy(jar, home.resolve("ext/aegis-plugin-" + name), StandardCopyOption.REPLACE_EXISTING);
                report.append("ADD  ").append(name).append('\n');
            }
        }
        Files.writeString(logDir.resolve("knox-ext-plugins.log"), report);
    }

    /** {@code jackson-core-2.18.2.jar} to {@code jackson-core}. */
    static String artifact(String jar) {
        return jar.replaceFirst("\\.jar$", "").replaceFirst("-\\d[\\w.\\-]*$", "");
    }

    private Process start(Path log, Map<String, String> env, List<String> jvmArgs, String jar, String... args) throws IOException {
        List<String> cmd = new ArrayList<>(List.of(java()));
        cmd.addAll(jvmArgs);
        cmd.addAll(List.of("-jar", jar));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(home.toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(log.toFile()));
        pb.environment().putAll(env);
        return pb.start();
    }

    private SSLContext trustKnoxIdentity() throws Exception {
        KeyStore ks = KeyStore.getInstance("JKS");
        try (InputStream in = Files.newInputStream(home.resolve("data/security/keystores/gateway.jks"))) {
            ks.load(in, MASTER.toCharArray());
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    static boolean listening(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** A readiness or state check used while waiting for a real server. */
    public interface Check {
        boolean ok() throws Exception;
    }

    static void waitUntil(Check check, Duration limit, String what) throws Exception {
        long deadline = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < deadline) {
            if (check.ok()) {
                return;
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("timed out waiting for " + what);
    }

    @Override
    public void close() throws Exception {
        Exception failure = null;
        for (int i = processes.size() - 1; i >= 0; i--) {
            Process p = processes.get(i);
            List<ProcessHandle> descendants = p.descendants().toList();
            descendants.forEach(ProcessHandle::destroy);
            p.destroy();
            try {
                if (!p.waitFor(30, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    p.waitFor(30, TimeUnit.SECONDS);
                }
                for (ProcessHandle child : descendants) {
                    if (child.isAlive()) {
                        child.destroyForcibly();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (failure == null) {
                    failure = e;
                }
            }
        }
        try {
            Files.deleteIfExists(topology);
        } catch (IOException e) {
            if (failure == null) {
                failure = e;
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
