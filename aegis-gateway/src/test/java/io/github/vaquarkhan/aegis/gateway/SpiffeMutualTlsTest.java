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
package io.github.vaquarkhan.aegis.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.vaquarkhan.aegis.config.BuildContext;
import io.github.vaquarkhan.aegis.config.ConfigException;
import io.github.vaquarkhan.aegis.config.Gateway;
import io.github.vaquarkhan.aegis.config.GatewayFactory;
import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.security.perimeter.OwnServerSecuritySettings;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code identity.type: spiffe} on the real own host over real mutual TLS. Certificates are issued by
 * the JDK's {@code keytool} from a test CA standing in for a SPIFFE trust bundle (they are not SPIRE
 * issued): an X.509-SVID with one {@code spiffe://example.org/...} URI SAN authenticates as its mapped
 * actor; no certificate or an untrusted one fails the TLS handshake; a CA certificate, two URI SANs,
 * another trust domain or an unmapped ID get 401.
 */
class SpiffeMutualTlsTest {

    static final String PW = "changeit-test-only";
    static final Map<String, String> ENV = Map.of("AEGIS_SIGNING_KEY", "k".repeat(48), "KS_PASSWORD", PW);

    @TempDir
    Path dir;

    private void keytool(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "keytool").toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out;
        try (InputStream in = p.getInputStream()) {
            out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0, String.join(" ", cmd) + "\n" + out);
    }

    private void newKey(String store, String alias, String dname) throws Exception {
        keytool("-genkeypair", "-alias", alias, "-keyalg", "EC", "-groupname", "secp256r1", "-dname", dname,
                "-validity", "2", "-keystore", store, "-storetype", "PKCS12", "-storepass", PW, "-keypass", PW);
    }

    /** A key pair in {@code store}, its certificate issued by the test CA with the given extensions. */
    private void issued(String store, String alias, String dname, String... ext) throws Exception {
        newKey(store, alias, dname);
        keytool("-certreq", "-alias", alias, "-keystore", store, "-storepass", PW, "-file", alias + ".csr");
        List<String> gen = new ArrayList<>(List.of("-gencert", "-alias", "ca", "-keystore", "ca.p12", "-storepass", PW,
                "-infile", alias + ".csr", "-outfile", alias + ".pem", "-rfc", "-validity", "2"));
        for (String e : ext) {
            gen.add("-ext");
            gen.add(e);
        }
        keytool(gen.toArray(String[]::new));
        keytool("-importcert", "-alias", "ca", "-keystore", store, "-storepass", PW, "-file", "ca.pem", "-noprompt");
        keytool("-importcert", "-alias", alias, "-keystore", store, "-storepass", PW, "-file", alias + ".pem");
    }

    HttpServer backend;

    @BeforeEach
    void pki() throws Exception {
        // keytool's -genkeypair self-signs; -ext bc:c marks it a CA so it can sign the others.
        keytool("-genkeypair", "-alias", "ca", "-keyalg", "EC", "-groupname", "secp256r1",
                "-dname", "CN=Aegis test SPIFFE bundle (not SPIRE issued)", "-ext", "bc:c", "-validity", "2",
                "-keystore", "ca.p12", "-storetype", "PKCS12", "-storepass", PW, "-keypass", PW);
        keytool("-exportcert", "-rfc", "-alias", "ca", "-keystore", "ca.p12", "-storepass", PW, "-file", "ca.pem");
        issued("server.p12", "server", "CN=127.0.0.1", "san=ip:127.0.0.1", "eku=serverAuth");
        issued("etl.p12", "etl", "CN=etl", "san=uri:spiffe://example.org/ns/data/sa/etl", "eku=clientAuth", "ku=digitalSignature");
        issued("other.p12", "other", "CN=other", "san=uri:spiffe://other.org/ns/data/sa/etl", "eku=clientAuth", "ku=digitalSignature");
        issued("unmapped.p12", "unmapped", "CN=unmapped", "san=uri:spiffe://example.org/ns/data/sa/unknown", "eku=clientAuth",
                "ku=digitalSignature");
        issued("two.p12", "two", "CN=two", "san=uri:spiffe://example.org/ns/data/sa/etl,uri:spiffe://example.org/ns/x",
                "eku=clientAuth", "ku=digitalSignature");
        newKey("selfsigned.p12", "selfsigned", "CN=selfsigned");

        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", ex -> {
            byte[] b = "{\"pong\":true}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        backend.start();
    }

    private Path config(String tlsExtra, String identity) throws Exception {
        String json = """
                {"approval": {"keyEnv": "AEGIS_SIGNING_KEY"},
                 "policy": {"rules": [{"id": "read", "groups": ["analysts"], "classes": ["READ"]}]},
                 "audit": {"file": "audit.jsonl"},
                 "egress": {"allowCidrs": ["127.0.0.0/8"]},
                 "sources": [{"id": "s", "type": "http", "baseUrl": "http://127.0.0.1:%d",
                   "tools": [{"name": "ping", "description": "Ping.", "class": "READ", "request": {"path": "/ping"}}]}],
                 "host": {"listen": {"host": "127.0.0.1", "port": 0},
                   "tls": {"keystore": "server.p12", "keystorePasswordEnv": "KS_PASSWORD"%s},
                   "identity": %s}}""".formatted(backend.getAddress().getPort(), tlsExtra, identity);
        Path p = dir.resolve("aegis.json");
        Files.writeString(p, json);
        return p;
    }

    static final String SPIFFE = """
            {"type": "spiffe", "spiffe": {"trustDomain": "example.org", "ids": [
              {"id": "spiffe://example.org/ns/data/sa/etl", "actor": "etl-agent", "groups": ["analysts"]}]}}""";
    static final String MTLS = ", \"clientAuth\": \"require\", \"clientCaFile\": \"ca.pem\"";

    private HttpClient client(String keystore) throws Exception {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        try (InputStream in = Files.newInputStream(dir.resolve("ca.pem"))) {
            trust.setCertificateEntry("ca", java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(in));
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        KeyManagerFactory kmf = null;
        if (keystore != null) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(dir.resolve(keystore))) {
                ks.load(in, PW.toCharArray());
            }
            kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, PW.toCharArray());
        }
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(kmf == null ? null : kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return HttpClient.newBuilder().sslContext(ssl).build();
    }

    private static HttpResponse<String> ping(HttpClient http, int port) throws Exception {
        Map<String, Object> body = Map.of("jsonrpc", "2.0", "id", 1L, "method", "tools/call",
                "params", Map.of("name", "ping", "arguments", Map.of(),
                        "_meta", Map.of("io.modelcontextprotocol/protocolVersion", "2026-07-28",
                                "io.modelcontextprotocol/clientCapabilities", Map.of())));
        return http.send(HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", "application/json").header("MCP-Protocol-Version", "2026-07-28")
                .header("Mcp-Method", "tools/call").header("Mcp-Name", "ping")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void anX509SvidAuthenticatesItsMappedWorkloadAndNothingElseGetsIn() throws Exception {
        Path cfg = config(MTLS, SPIFFE);
        try (Gateway gw = GatewayFactory.build(cfg, BuildContext.defaults(dir).withEnv(ENV::get))) {
            OwnServerSecuritySettings st = OwnServerSecuritySettings.parse(gw.hostSection("host"), dir, ENV::get, Clock.systemUTC());
            try (AegisServer server = new AegisServer(gw, st)) {
                server.start();
                int port = server.port();

                HttpResponse<String> ok = ping(client("etl.p12"), port);
                assertEquals(200, ok.statusCode(), ok.body());
                assertTrue(ok.body().contains("pong"), ok.body());

                for (String rejected : List.of("other.p12", "unmapped.p12", "two.p12", "ca.p12")) {
                    HttpResponse<String> r = ping(client(rejected), port);
                    assertEquals(401, r.statusCode(), rejected + ": " + r.body());
                }
                // No client certificate, or one that does not chain to the bundle: the handshake fails.
                assertThrows(IOException.class, () -> ping(client(null), port), "no client certificate");
                assertThrows(IOException.class, () -> ping(client("selfsigned.p12"), port), "untrusted client certificate");
            }
        }
        // The journal names the workload that called, with the SPIFFE trust domain as issuer.
        String journal = Files.readString(dir.resolve("audit.jsonl"));
        assertTrue(journal.contains("\"actor\":\"etl-agent\""), journal);
        assertTrue(journal.contains("spiffe://example.org"), journal);
    }

    @Test
    void spiffeIdentityRequiresMutualTlsAndWellFormedIds() throws Exception {
        Path noMtls = config("", SPIFFE);
        try (Gateway gw = GatewayFactory.build(noMtls, BuildContext.defaults(dir).withEnv(ENV::get))) {
            ConfigException e = assertThrows(ConfigException.class,
                    () -> OwnServerSecuritySettings.parse(gw.hostSection("host"), dir, ENV::get, Clock.systemUTC()));
            assertTrue(e.getMessage().contains("needs tls.clientAuth: require"), e.getMessage());
        }
        for (String bad : List.of(
                "{\"type\": \"spiffe\", \"spiffe\": {\"trustDomain\": \"example.org\", \"ids\": []}}",
                "{\"type\": \"spiffe\", \"spiffe\": {\"trustDomain\": \"example.org\", \"ids\": [{\"id\": \"spiffe://other.org/a\", \"actor\": \"a\"}]}}",
                "{\"type\": \"spiffe\", \"spiffe\": {\"trustDomain\": \"example.org\", \"ids\": [{\"id\": \"spiffe://example.org/a/../b\", \"actor\": \"a\"}]}}",
                "{\"type\": \"spiffe\", \"spiffe\": {\"trustDomain\": \"example.org\", \"ids\": [{\"id\": \"https://example.org/a\", \"actor\": \"a\"}]}}",
                "{\"type\": \"spiffe\", \"spiffe\": {\"trustDomain\": \"Example.org\", \"ids\": [{\"id\": \"spiffe://example.org/a\", \"actor\": \"a\"}]}}")) {
            Path cfg = config(MTLS, bad);
            try (Gateway gw = GatewayFactory.build(cfg, BuildContext.defaults(dir).withEnv(ENV::get))) {
                assertThrows(ConfigException.class,
                        () -> OwnServerSecuritySettings.parse(gw.hostSection("host"), dir, ENV::get, Clock.systemUTC()), bad);
            }
        }
        Path wrongMode = config(", \"clientAuth\": \"want\"", SPIFFE);
        try (Gateway gw = GatewayFactory.build(wrongMode, BuildContext.defaults(dir).withEnv(ENV::get))) {
            assertThrows(ConfigException.class,
                    () -> OwnServerSecuritySettings.parse(gw.hostSection("host"), dir, ENV::get, Clock.systemUTC()));
        }
    }
}
