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
/*
 * Gap L1 (DNS rebinding between the egress check and the connection). PinnedHttp must connect
 * only to the addresses EgressGuard checked in the same step, keep TLS SNI and hostname
 * verification bound to the original name, and stay bounded (bytes, head, total deadline).
 * Verdict lines: THREATMODEL|id|refs|class|verdict|detail
 */
package io.github.vaquarkhan.aegis.mcp.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.github.vaquarkhan.aegis.engine.control.EgressBinding;
import io.github.vaquarkhan.aegis.engine.control.EgressGuard;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PinnedHttpTest {

    /** Not resolvable by any real DNS: success proves the socket used the pinned answer. */
    static final String NAME = "pinned.aegis.test";

    @TempDir
    Path dir;

    static void verdict(String id, String refs, String cls, String verdict, String detail) {
        System.out.println("THREATMODEL|" + id + "|" + refs + "|" + cls + "|" + verdict + "|" + detail);
    }

    private static InetAddress ip(String literal) {
        try {
            return InetAddress.getByAddress(literal, InetAddress.getByName(literal).getAddress());
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static EgressGuard guard(List<String> answers, AtomicInteger lookups) {
        return new EgressGuard(List.of(NAME, "other.aegis.test"), List.of("127.0.0.1/32"), Set.of("http", "https"), host -> {
            int n = lookups.getAndIncrement();
            String a = answers.get(Math.min(n, answers.size() - 1));
            return new InetAddress[] {ip(a)};
        });
    }

    @Test
    void connectsToTheCheckedAddressWithoutResolvingAgain() throws Exception {
        List<String> hosts = new CopyOnWriteArrayList<>();
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        s.createContext("/x", ex -> {
            hosts.add(ex.getRequestHeaders().getFirst("Host"));
            byte[] in = ex.getRequestBody().readAllBytes();
            byte[] out = ("echo:" + new String(in, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/plain");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        s.start();
        try {
            int port = s.getAddress().getPort();
            AtomicInteger lookups = new AtomicInteger();
            PinnedHttp t = new PinnedHttp(guard(List.of("127.0.0.1"), lookups)::pin, Duration.ofSeconds(2), null);
            BoundedHttp.Result r = t.send(HttpRequest.newBuilder(URI.create("http://" + NAME + ":" + port + "/x?q=1"))
                    .POST(HttpRequest.BodyPublishers.ofString("hi")).build(), 1024, 3000);
            assertEquals(200, r.status());
            assertEquals("echo:hi", new String(r.body(), StandardCharsets.UTF_8));
            assertEquals("text/plain", r.headers().firstValue("content-type").orElse(""));
            assertEquals(1, lookups.get(), "exactly one resolution per exchange");
            assertEquals(List.of(NAME + ":" + port), hosts, "the Host header keeps the approved name");

            // GET with no body, chunked response (HttpServer uses chunked when length is 0).
            s.createContext("/chunked", ex -> {
                ex.sendResponseHeaders(200, 0);
                try (OutputStream o = ex.getResponseBody()) {
                    o.write("part1-".getBytes(StandardCharsets.UTF_8));
                    o.flush();
                    o.write("part2".getBytes(StandardCharsets.UTF_8));
                }
            });
            BoundedHttp.Result c = t.send(HttpRequest.newBuilder(URI.create("http://" + NAME + ":" + port + "/chunked"))
                    .GET().build(), 1024, 3000);
            assertEquals("part1-part2", new String(c.body(), StandardCharsets.UTF_8));
            IOException big = assertThrows(IOException.class, () -> t.send(HttpRequest.newBuilder(
                    URI.create("http://" + NAME + ":" + port + "/chunked")).GET().build(), 4, 3000));
            assertTrue(big.getMessage().contains("exceeds"), big.getMessage());
            IOException bigFixed = assertThrows(IOException.class, () -> t.send(HttpRequest.newBuilder(
                    URI.create("http://" + NAME + ":" + port + "/x")).POST(HttpRequest.BodyPublishers.ofString("0123456789")).build(), 4, 3000));
            assertTrue(bigFixed.getMessage().contains("exceeds"), bigFixed.getMessage());
        } finally {
            s.stop(0);
        }
        verdict("GAP-L1a", "DNS TOCTOU class (ContextForge bulletin, CVE-2025-64443 class)", "PinnedHttp.send",
                "FIXED", "one resolution per exchange; socket dials the checked answer; Host header keeps the name");
    }

    @Test
    void aRebindingAnswerAtConnectTimeIsRefusedBeforeAnyByteIsSent() throws Exception {
        AtomicInteger accepted = new AtomicInteger();
        try (ServerSocket ss = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread acceptor = new Thread(() -> {
                try {
                    while (true) {
                        Socket sock = ss.accept();
                        accepted.incrementAndGet();
                        sock.close();
                    }
                } catch (IOException ignored) {
                    // closed
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
            URI target = URI.create("http://" + NAME + ":" + ss.getLocalPort() + "/latest/meta-data/");
            AtomicInteger lookups = new AtomicInteger();
            // First answer (the policy step) is allowed; the second (connect time) is cloud metadata.
            EgressGuard g = guard(List.of("127.0.0.1", "169.254.169.254"), lookups);
            assertEquals(null, g.check(target), "the policy step sees the safe answer");
            PinnedHttp t = new PinnedHttp(g::pin, Duration.ofSeconds(1), null);
            PinnedHttp.EgressDeniedException e = assertThrows(PinnedHttp.EgressDeniedException.class,
                    () -> t.send(HttpRequest.newBuilder(target).GET().build(), 1024, 2000));
            assertTrue(e.getMessage().contains("LINK_LOCAL"), e.getMessage());
            Thread.sleep(100);
            assertEquals(0, accepted.get(), "no connection was opened for the rebound answer");
        }
        // An unbound binding refuses everything.
        EgressBinding unbound = new EgressBinding();
        assertFalse(unbound.bound());
        PinnedHttp none = new PinnedHttp(unbound::pin, null, null);
        assertThrows(PinnedHttp.EgressDeniedException.class,
                () -> none.send(HttpRequest.newBuilder(URI.create("http://" + NAME + "/")).GET().build(), 16, 500));
        EgressGuard g1 = guard(List.of("127.0.0.1"), new AtomicInteger());
        unbound.bind(g1);
        unbound.bind(g1);
        assertThrows(IllegalStateException.class, () -> unbound.bind(guard(List.of("127.0.0.1"), new AtomicInteger())));
        assertThrows(PinnedHttp.EgressDeniedException.class, () -> new PinnedHttp(u -> null, null, null)
                .send(HttpRequest.newBuilder(URI.create("http://" + NAME + "/")).build(), 16, 500));
        assertThrows(IllegalArgumentException.class, () -> new PinnedHttp(null, null, null));
        verdict("GAP-L1b", "DNS rebinding to 169.254.169.254", "PinnedHttp + EgressGuard.pin + EgressBinding",
                "FIXED", "rebound answer refused before connect (0 accepted sockets); unbound binding refuses");
    }

    /** Minimal HTTP CONNECT proxy on loopback; records the authority it was asked to open. */
    private static ServerSocket connectProxy(List<String> authorities, boolean refuse) throws IOException {
        ServerSocket ss = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread t = new Thread(() -> {
            while (!ss.isClosed()) {
                try {
                    Socket client = ss.accept();
                    new Thread(() -> {
                        try (client) {
                            InputStream in = client.getInputStream();
                            StringBuilder head = new StringBuilder();
                            int c;
                            while ((c = in.read()) >= 0) {
                                head.append((char) c);
                                if (head.toString().endsWith("\r\n\r\n")) {
                                    break;
                                }
                            }
                            String first = head.toString().split("\r\n")[0];
                            String[] parts = first.split(" ");
                            authorities.add(parts[1]);
                            OutputStream out = client.getOutputStream();
                            if (refuse || !"CONNECT".equals(parts[0])) {
                                out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                                out.flush();
                                return;
                            }
                            String[] hp = parts[1].split(":");
                            try (Socket target = new Socket(InetAddress.getLoopbackAddress(), Integer.parseInt(hp[hp.length - 1]))) {
                                out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                                out.flush();
                                Thread pump = new Thread(() -> {
                                    try {
                                        target.getInputStream().transferTo(out);
                                    } catch (IOException ignored) {
                                        // closed
                                    }
                                });
                                pump.setDaemon(true);
                                pump.start();
                                in.transferTo(target.getOutputStream());
                                target.shutdownOutput();
                                pump.join(2000);
                            }
                        } catch (Exception ignored) {
                            // test proxy
                        }
                    }).start();
                } catch (IOException e) {
                    return;
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return ss;
    }

    /** A CONNECT proxy that records the Proxy-Authorization value and then tunnels to loopback. */
    private static ServerSocket connectAuthProxy(List<String> proxyAuth) throws IOException {
        ServerSocket ss = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread t = new Thread(() -> {
            try {
                Socket client = ss.accept();
                InputStream in = client.getInputStream();
                StringBuilder head = new StringBuilder();
                int c;
                while ((c = in.read()) >= 0) {
                    head.append((char) c);
                    if (head.toString().endsWith("\r\n\r\n")) {
                        break;
                    }
                }
                for (String line : head.toString().split("\r\n")) {
                    if (line.toLowerCase(java.util.Locale.ROOT).startsWith("proxy-authorization:")) {
                        proxyAuth.add(line.substring(line.indexOf(':') + 1).trim());
                    }
                }
                String[] parts = head.toString().split("\r\n")[0].split(" ");
                String[] hp = parts[1].split(":");
                try (Socket target = new Socket(InetAddress.getLoopbackAddress(), Integer.parseInt(hp[hp.length - 1]))) {
                    OutputStream out = client.getOutputStream();
                    out.write("HTTP/1.1 200 OK\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    Thread pump = new Thread(() -> {
                        try {
                            target.getInputStream().transferTo(out);
                        } catch (IOException ignored) {
                            // closed
                        }
                    });
                    pump.setDaemon(true);
                    pump.start();
                    in.transferTo(target.getOutputStream());
                    target.shutdownOutput();
                    pump.join(2000);
                }
                client.close();
            } catch (Exception ignored) {
                // test proxy
            }
        });
        t.setDaemon(true);
        t.start();
        return ss;
    }

    @Test
    void aForwardProxyIsUsedWithConnectAndTheTargetIsStillChecked() throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        List<String> hosts = new CopyOnWriteArrayList<>();
        s.createContext("/p", ex -> {
            hosts.add(ex.getRequestHeaders().getFirst("Host"));
            byte[] out = "via-proxy".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        s.start();
        List<String> authorities = new CopyOnWriteArrayList<>();
        try (ServerSocket proxy = connectProxy(authorities, false)) {
            int port = s.getAddress().getPort();
            // Both the target name and the proxy name are allowed and pinned to loopback.
            EgressGuard guard = new EgressGuard(List.of(NAME, "proxy.aegis.test"), List.of("127.0.0.1/32"),
                    Set.of("http", "https"), host -> new InetAddress[] {ip("127.0.0.1")});
            PinnedHttp t = new PinnedHttp(guard::pin, Duration.ofSeconds(2), null,
                    new PinnedHttp.Proxy("proxy.aegis.test", proxy.getLocalPort()));
            BoundedHttp.Result r = t.send(HttpRequest.newBuilder(URI.create("http://" + NAME + ":" + port + "/p")).GET().build(), 1024, 3000);
            assertEquals(200, r.status());
            assertEquals("via-proxy", new String(r.body(), StandardCharsets.UTF_8));
            assertEquals(List.of(NAME + ":" + port), authorities, "CONNECT names the approved target");
            assertEquals(List.of(NAME + ":" + port), hosts);

            // The target is still checked by the egress control even though the proxy dials it.
            PinnedHttp denied = new PinnedHttp(new EgressGuard(List.of("proxy.aegis.test"), List.of("127.0.0.1/32"),
                    Set.of("http"), host -> new InetAddress[] {ip("127.0.0.1")})::pin, Duration.ofSeconds(2), null,
                    new PinnedHttp.Proxy("proxy.aegis.test", proxy.getLocalPort()));
            assertThrows(PinnedHttp.EgressDeniedException.class, () -> denied.send(
                    HttpRequest.newBuilder(URI.create("http://" + NAME + ":" + port + "/p")).GET().build(), 1024, 3000));
            assertEquals(1, authorities.size(), "nothing was sent to the proxy for a denied target");

            // A proxy that is not on the allow list is refused before connecting.
            PinnedHttp unlistedProxy = new PinnedHttp(new EgressGuard(List.of(NAME), List.of("127.0.0.1/32"),
                    Set.of("http"), host -> new InetAddress[] {ip("127.0.0.1")})::pin, Duration.ofSeconds(2), null,
                    new PinnedHttp.Proxy("proxy.aegis.test", proxy.getLocalPort()));
            assertThrows(PinnedHttp.EgressDeniedException.class, () -> unlistedProxy.send(
                    HttpRequest.newBuilder(URI.create("http://" + NAME + ":" + port + "/p")).GET().build(), 1024, 3000));
        } finally {
            s.stop(0);
        }
        // P-5: an authenticated proxy receives the Proxy-Authorization header on the CONNECT line.
        List<String> authHeads = new CopyOnWriteArrayList<>();
        try (ServerSocket authProxy = connectAuthProxy(authHeads)) {
            HttpServer s2 = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            s2.createContext("/a", ex -> {
                ex.sendResponseHeaders(204, -1);
                ex.close();
            });
            s2.start();
            try {
                EgressGuard guard = new EgressGuard(List.of(NAME, "proxy.aegis.test"), List.of("127.0.0.1/32"),
                        Set.of("http"), host -> new InetAddress[] {ip("127.0.0.1")});
                PinnedHttp t = new PinnedHttp(guard::pin, Duration.ofSeconds(2), null,
                        new PinnedHttp.Proxy("proxy.aegis.test", authProxy.getLocalPort(), "Basic dXNlcjpwYXNz"));
                assertEquals(204, t.send(HttpRequest.newBuilder(URI.create("http://" + NAME + ":" + s2.getAddress().getPort()
                        + "/a")).GET().build(), 16, 3000).status());
                assertEquals(List.of("Basic dXNlcjpwYXNz"), authHeads);
            } finally {
                s2.stop(0);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> new PinnedHttp.Proxy("p", 8080, "bad\r\nInjected: 1"));

        try (ServerSocket refusing = connectProxy(new CopyOnWriteArrayList<>(), true)) {
            EgressGuard guard = new EgressGuard(List.of(NAME, "proxy.aegis.test"), List.of("127.0.0.1/32"),
                    Set.of("http"), host -> new InetAddress[] {ip("127.0.0.1")});
            PinnedHttp t = new PinnedHttp(guard::pin, Duration.ofSeconds(2), null,
                    new PinnedHttp.Proxy("proxy.aegis.test", refusing.getLocalPort()));
            IOException e = assertThrows(IOException.class, () -> t.send(
                    HttpRequest.newBuilder(URI.create("http://" + NAME + ":1/p")).GET().build(), 1024, 3000));
            assertTrue(e.getMessage().contains("CONNECT"), e.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> new PinnedHttp.Proxy("", 1));
        verdict("SCAN-O8", "AI scanner readiness O-8 (proxy part)", "PinnedHttp.Proxy + tunnel", "FIXED",
                "HTTP CONNECT through an allow-listed, pinned proxy; the target is still egress-checked; refused CONNECT fails");
    }

    @Test
    void aTricklingServerCannotExtendTheTotalDeadline() throws Exception {
        try (ServerSocket ss = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread server = new Thread(() -> {
                try (Socket sock = ss.accept()) {
                    InputStream in = sock.getInputStream();
                    byte[] buf = new byte[4096];
                    in.read(buf);
                    OutputStream o = sock.getOutputStream();
                    o.write("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    for (int i = 0; i < 100; i++) {
                        o.write('a');
                        o.flush();
                        Thread.sleep(100);
                    }
                } catch (Exception ignored) {
                    // client gave up
                }
            });
            server.setDaemon(true);
            server.start();
            PinnedHttp t = new PinnedHttp(guard(List.of("127.0.0.1"), new AtomicInteger())::pin, Duration.ofSeconds(1), null);
            long start = System.nanoTime();
            assertThrows(HttpTimeoutException.class, () -> t.send(HttpRequest.newBuilder(
                    URI.create("http://" + NAME + ":" + ss.getLocalPort() + "/")).GET().build(), 1024, 600));
            long ms = (System.nanoTime() - start) / 1_000_000;
            assertTrue(ms < 3000, "deadline held: " + ms + " ms");
        }
        // Malformed heads are refused.
        for (String head : List.of("NOPE\r\n\r\n", "HTTP/1.1 200 OK\r\n folded\r\n\r\n", "HTTP/1.1 200 OK\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\nx",
                "HTTP/1.1 101 Switching\r\n\r\n", "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n")) {
            try (ServerSocket ss = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
                Thread server = new Thread(() -> {
                    try (Socket sock = ss.accept()) {
                        sock.getInputStream().read(new byte[4096]);
                        sock.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));
                        sock.getOutputStream().flush();
                        Thread.sleep(200);
                    } catch (Exception ignored) {
                        // done
                    }
                });
                server.setDaemon(true);
                server.start();
                PinnedHttp t = new PinnedHttp(guard(List.of("127.0.0.1"), new AtomicInteger())::pin, Duration.ofSeconds(1), null);
                assertThrows(IOException.class, () -> t.send(HttpRequest.newBuilder(
                        URI.create("http://" + NAME + ":" + ss.getLocalPort() + "/")).GET().build(), 1024, 2000), head);
            }
        }
        verdict("GAP-L1c", "slow-body DoS, response smuggling", "PinnedHttp.DeadlineInput / readResponse",
                "FIXED", "total deadline holds against a trickling body; folded, conflicting and malformed heads refused");
    }

    @Test
    void tlsKeepsSniAndHostnameVerificationOnTheApprovedName() throws Exception {
        byte[] seed = new byte[12];
        new SecureRandom().nextBytes(seed);
        char[] pass = HexFormat.of().formatHex(seed).toCharArray(); // throwaway, per-run keystore password
        Path ks = dir.resolve("server.p12");
        String exe = System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool";
        Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", exe).toString(),
                "-genkeypair", "-alias", "s", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=" + NAME,
                "-ext", "SAN=dns:" + NAME, "-validity", "2", "-keystore", ks.toString(), "-storetype", "PKCS12",
                "-storepass", new String(pass), "-keypass", new String(pass)).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), out);
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(ks)) {
            keys.load(in, pass);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keys, pass);
        SSLContext serverCtx = SSLContext.getInstance("TLS");
        serverCtx.init(kmf.getKeyManagers(), null, null);
        Certificate cert = keys.getCertificate("s");
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("ca", cert);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext clientCtx = SSLContext.getInstance("TLS");
        clientCtx.init(null, tmf.getTrustManagers(), null);

        HttpsServer s = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        s.setHttpsConfigurator(new HttpsConfigurator(serverCtx));
        List<String> seen = new ArrayList<>();
        s.createContext("/", ex -> {
            seen.add(ex.getRequestHeaders().getFirst("Host"));
            byte[] b = "secure".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        s.start();
        try {
            int port = s.getAddress().getPort();
            PinnedHttp t = new PinnedHttp(guard(List.of("127.0.0.1"), new AtomicInteger())::pin, Duration.ofSeconds(2), clientCtx);
            BoundedHttp.Result r = t.send(HttpRequest.newBuilder(URI.create("https://" + NAME + ":" + port + "/")).GET().build(),
                    1024, 5000);
            assertEquals(200, r.status());
            assertArrayEquals("secure".getBytes(StandardCharsets.UTF_8), r.body());
            // Same IP, different approved name: the certificate does not match it, so TLS fails.
            assertThrows(SSLHandshakeException.class, () -> t.send(HttpRequest.newBuilder(
                    URI.create("https://other.aegis.test:" + port + "/")).GET().build(), 1024, 5000));
            assertEquals(1, seen.size());
        } finally {
            s.stop(0);
        }
        verdict("GAP-L1d", "TLS with pinned address", "PinnedHttp.tls", "FIXED",
                "certificate verified against the approved name (SNI + endpoint identification HTTPS), not the dialled IP");
    }
}
