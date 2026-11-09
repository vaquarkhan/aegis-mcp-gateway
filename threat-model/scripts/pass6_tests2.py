from pathlib import Path
_REPO = Path(__file__).resolve().parents[2]
_TM = Path(__file__).resolve().parents[1]
root = str(_REPO)

def edit(path, pairs):
    p = root + path
    raw = open(p, encoding='utf-8', newline='').read()
    crlf = '\r\n' in raw
    s = raw.replace('\r\n', '\n')
    for old, new in pairs:
        if new in s and old not in s:
            continue
        assert old in s, (path, old[:100])
        s = s.replace(old, new, 1)
    if crlf:
        s = s.replace('\n', '\r\n')
    open(p, 'w', encoding='utf-8', newline='').write(s)

# O-11 C: signed checkpoints, end to end through GatewayFactory.
edit(r'\aegis-config\src\test\java\io\github\vaquarkhan\aegis\config\ThreatModelGapConfigTest.java', [
    ('''    @Test
    void auditSinkSpiReceivesEveryRecordAndFailsClosed() throws Exception {''',
     '''    @Test
    void signedCheckpointsDetectARegeneratedCheckpointFile() throws Exception {
        String[] keys = io.github.vaquarkhan.aegis.engine.catalog.SignedCatalogManifest.generateKeyPair();
        java.nio.file.Files.writeString(dir.resolve("cp.key"), keys[0]);
        java.nio.file.Files.writeString(dir.resolve("cp.pub"), keys[1]);
        try (TestMcpServer mcp = new TestMcpServer(TestMcpServer.Mode.MODERN)) {
            mcp.tool("ping", "Ping.", Map.of("type", "object"), a -> TestMcpServer.textResult("pong"));
            Map<String, Object> cfg = Json.parseObject("""
                    {"policy":{"rules":[{"id":"r","groups":["*"],"classes":["READ"]}]},
                     "audit":{"file":"c.jsonl","checkpoint":{"file":"c.ckpt","everyRecords":1,"signingKeyFile":"cp.key"}},
                     "egress":{"allowCidrs":["127.0.0.0/8"]},
                     "sources":[{"id":"m","type":"mcp","url":"%s","defaultClass":"READ"}]}""".formatted(mcp.uri()));
            CallerIdentity alice = CallerIdentity.of("alice", Set.of("analysts"), "idp", "jwt");
            try (Gateway g = GatewayFactory.build(cfg, ctx())) {
                assertTrue(g.engine().call(alice, call("ping")).allowed());
            }
            java.security.PublicKey pub = io.github.vaquarkhan.aegis.engine.catalog.SignedCatalogManifest.readPublicKey(dir.resolve("cp.pub"));
            var ok = io.github.vaquarkhan.aegis.engine.audit.AuditCheckpointVerifier.verify(dir.resolve("c.jsonl"), dir.resolve("c.ckpt"), pub);
            assertTrue(ok.ok(), String.valueOf(ok.error()));
            assertTrue(java.nio.file.Files.readString(dir.resolve("c.ckpt")).contains("\\"signature\\""));
            // An attacker who rewrites the journal must also regenerate the checkpoint file; without the
            // signing key the regenerated lines carry no valid signature.
            java.util.List<String> lines = java.nio.file.Files.readAllLines(dir.resolve("c.ckpt"));
            String forged = lines.get(lines.size() - 1).replaceAll("\\"signature\\":\\"[^\\"]*\\"", "\\"signature\\":\\"AAAA\\"");
            java.nio.file.Files.writeString(dir.resolve("c.ckpt"), String.join("\\n", lines.subList(0, lines.size() - 1)) + "\\n" + forged + "\\n");
            var broken = io.github.vaquarkhan.aegis.engine.audit.AuditCheckpointVerifier.verify(dir.resolve("c.jsonl"), dir.resolve("c.ckpt"), pub);
            assertFalse(broken.ok());
            assertTrue(broken.error().contains("signature"), broken.error());
            assertThrows(ConfigException.class, () -> GatewayFactory.build(Json.parseObject("""
                    {"policy":{"rules":[{"id":"r","groups":["*"]}]},
                     "audit":{"file":"c2.jsonl","checkpoint":{"file":"c2.ckpt","signingKeyFile":"missing.key"}},
                     "sources":[{"id":"m","type":"mcp","url":"%s"}]}""".formatted(mcp.uri())), ctx()));
        }
        System.out.println("THREATMODEL|SCAN-O11|AI scanner readiness O-11|AuditCheckpointPolicy.signingKey + AuditCheckpointVerifier|FIXED|"
                + "every checkpoint line is Ed25519-signed; a regenerated checkpoint file without the key fails verification");
    }

    @Test
    void auditSinkSpiReceivesEveryRecordAndFailsClosed() throws Exception {'''),
])

# O-8 A: CONNECT proxy in PinnedHttpTest.
edit(r'\aegis-mcp\src\test\java\io\github\vaquarkhan\aegis\mcp\http\PinnedHttpTest.java', [
    ('''    @Test
    void aTricklingServerCannotExtendTheTotalDeadline() throws Exception {''',
     '''    /** Minimal HTTP CONNECT proxy on loopback; records the authority it was asked to open. */
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
                                if (head.toString().endsWith("\\r\\n\\r\\n")) {
                                    break;
                                }
                            }
                            String first = head.toString().split("\\r\\n")[0];
                            String[] parts = first.split(" ");
                            authorities.add(parts[1]);
                            OutputStream out = client.getOutputStream();
                            if (refuse || !"CONNECT".equals(parts[0])) {
                                out.write("HTTP/1.1 403 Forbidden\\r\\nContent-Length: 0\\r\\n\\r\\n".getBytes(StandardCharsets.US_ASCII));
                                out.flush();
                                return;
                            }
                            String[] hp = parts[1].split(":");
                            try (Socket target = new Socket(InetAddress.getLoopbackAddress(), Integer.parseInt(hp[hp.length - 1]))) {
                                out.write("HTTP/1.1 200 Connection established\\r\\n\\r\\n".getBytes(StandardCharsets.US_ASCII));
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
    void aTricklingServerCannotExtendTheTotalDeadline() throws Exception {'''),
])
print('ok')
