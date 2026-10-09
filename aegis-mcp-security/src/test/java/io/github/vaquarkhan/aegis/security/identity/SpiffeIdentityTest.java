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
package io.github.vaquarkhan.aegis.security.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.config.ConfigException;
import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.mcp.http.McpHttpRequest;
import io.github.vaquarkhan.aegis.security.perimeter.OwnServerSecuritySettings;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The X.509-SVID rules applied to certificates issued by the JDK's {@code keytool} (a test CA, not
 * SPIRE). The TLS handshake that verifies the chain is exercised end to end in the gateway module's
 * {@code SpiffeMutualTlsTest}; here the resolver sees certificates exactly as the handshake hands them over.
 */
class SpiffeIdentityTest {

    static final String PW = "changeit-test-only";

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

    private X509Certificate pem(String file) throws Exception {
        try (InputStream in = Files.newInputStream(dir.resolve(file))) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

    private void ca() throws Exception {
        keytool("-genkeypair", "-alias", "ca", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=test bundle",
                "-ext", "bc:c", "-validity", "2", "-keystore", "ca.p12", "-storetype", "PKCS12", "-storepass", PW, "-keypass", PW);
        keytool("-exportcert", "-rfc", "-alias", "ca", "-keystore", "ca.p12", "-storepass", PW, "-file", "ca.pem");
    }

    private X509Certificate leaf(String name, String... ext) throws Exception {
        keytool("-genkeypair", "-alias", name, "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=" + name,
                "-validity", "2", "-keystore", name + ".p12", "-storetype", "PKCS12", "-storepass", PW, "-keypass", PW);
        keytool("-certreq", "-alias", name, "-keystore", name + ".p12", "-storepass", PW, "-file", name + ".csr");
        List<String> gen = new ArrayList<>(List.of("-gencert", "-alias", "ca", "-keystore", "ca.p12", "-storepass", PW,
                "-infile", name + ".csr", "-outfile", name + ".pem", "-rfc", "-validity", "2"));
        for (String e : ext) {
            gen.add("-ext");
            gen.add(e);
        }
        keytool(gen.toArray(String[]::new));
        return pem(name + ".pem");
    }

    private static McpHttpRequest with(X509Certificate... chain) {
        return new McpHttpRequest("POST", "", Map.of(), new byte[0], List.of(chain));
    }

    @Test
    void onlyAMappedSvidInTheTrustDomainAuthenticates() throws Exception {
        ca();
        X509Certificate etl = leaf("etl", "san=uri:spiffe://example.org/ns/data/sa/etl", "ku=digitalSignature");
        X509Certificate other = leaf("other", "san=uri:spiffe://other.org/ns/data/sa/etl");
        X509Certificate unmapped = leaf("unmapped", "san=uri:spiffe://example.org/ns/data/sa/unknown");
        X509Certificate two = leaf("two", "san=uri:spiffe://example.org/a,uri:spiffe://example.org/b");
        X509Certificate none = leaf("none", "san=dns:etl.example.org");
        X509Certificate signer = leaf("signer", "san=uri:spiffe://example.org/ns/data/sa/etl", "ku=digitalSignature,keyCertSign");
        X509Certificate noDigitalSignature = leaf("nods", "san=uri:spiffe://example.org/ns/data/sa/etl", "ku=keyEncipherment");
        X509Certificate caCert = pem("ca.pem");

        CallerIdentity etlCaller = CallerIdentity.of("etl-agent", Set.of("analysts"), "spiffe://example.org", "x509-svid");
        SpiffeIdentity resolver = new SpiffeIdentity("example.org", Map.of("spiffe://example.org/ns/data/sa/etl", etlCaller));

        assertEquals(etlCaller, resolver.resolve(with(etl, caCert)));
        assertEquals("spiffe://example.org/ns/data/sa/etl", SpiffeIdentity.spiffeId(etl));
        for (X509Certificate refused : List.of(other, unmapped, two, none, signer, noDigitalSignature, caCert)) {
            assertNull(resolver.resolve(with(refused)), refused.getSubjectX500Principal().getName());
        }
        assertNull(resolver.resolve(new McpHttpRequest("POST", "", Map.of(), new byte[0])), "no certificate on the connection");
        assertEquals(List.of(), new McpHttpRequest("GET", "", Map.of(), null, null).peerCertificates());
    }

    @Test
    void spiffeIdsAreCheckedForShape() {
        assertNull(SpiffeIdentity.problem("spiffe://example.org/ns/a", "example.org"));
        for (String bad : List.of("spiffe://example.org", "spiffe://example.org/", "spiffe://example.org/a//b",
                "spiffe://example.org/a/./b", "spiffe://example.org/a/..", "spiffe://example.org:8443/a",
                "spiffe://u@example.org/a", "spiffe://example.org/a?x=1", "spiffe://example.org/a#f",
                "spiffe://EXAMPLE.org/a", "spiffe://other.org/a", "https://example.org/a", "spiffe://example.org/a b", "::")) {
            assertTrue(SpiffeIdentity.problem(bad, "example.org") != null, bad);
        }
        assertThrows(IllegalArgumentException.class, () -> new SpiffeIdentity("Example.org", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new SpiffeIdentity("example.org",
                Map.of("spiffe://other.org/a", CallerIdentity.of("a", Set.of(), "i", "m"))));
    }

    private Map<String, Object> host(String tls, String identity) {
        return Json.parseObject("{\"listen\": {\"port\": 0}, \"tls\": " + tls + ", \"identity\": " + identity + "}");
    }

    @Test
    void hostSettingsRequireMutualTlsForSpiffeAndAKnownTrustBundle() throws Exception {
        ca();
        Files.writeString(dir.resolve("server.p12"), "placeholder, not opened by the parser");
        Map<String, String> env = Map.of("KS", PW);
        String spiffe = "{\"type\": \"spiffe\", \"spiffe\": {\"trustDomain\": \"example.org\", \"ids\": ["
                + "{\"id\": \"spiffe://example.org/a\", \"actor\": \"a\", \"groups\": [\"g\"]}]}}";
        String mtls = "{\"keystore\": \"server.p12\", \"keystorePasswordEnv\": \"KS\", \"clientAuth\": \"require\", \"clientCaFile\": \"ca.pem\"}";

        OwnServerSecuritySettings ok = OwnServerSecuritySettings.parse(host(mtls, spiffe), dir, env::get, Clock.systemUTC());
        assertTrue(ok.requireClientCert());
        assertEquals(dir.resolve("ca.pem"), ok.clientCaFile());
        assertTrue(ok.identity() instanceof SpiffeIdentity);

        List<String[]> bad = List.of(
                new String[] {"{\"keystore\": \"server.p12\", \"keystorePasswordEnv\": \"KS\"}", spiffe, "needs tls.clientAuth: require"},
                new String[] {"{\"clientAuth\": \"require\", \"clientCaFile\": \"ca.pem\"}", spiffe, "needs tls.keystore"},
                new String[] {"{\"keystore\": \"server.p12\", \"keystorePasswordEnv\": \"KS\", \"clientAuth\": \"want\"}", spiffe, "none or require"},
                new String[] {"{\"keystore\": \"server.p12\", \"keystorePasswordEnv\": \"KS\", \"clientAuth\": \"require\", \"clientCaFile\": \"absent.pem\"}",
                        spiffe, "does not exist"},
                new String[] {"{\"keystore\": \"server.p12\", \"keystorePasswordEnv\": \"KS\", \"clientCaFile\": \"ca.pem\"}", spiffe,
                        "only used with clientAuth: require"},
                new String[] {mtls, "{\"type\": \"spiffe\", \"spiffe\": {\"trustDomain\": \"example.org\", \"ids\": []}}", "at least one"},
                new String[] {mtls, "{\"type\": \"spiffe\", \"spiffe\": {\"trustDomain\": \"example.org\", \"ids\": ["
                        + "{\"id\": \"spiffe://example.org/a\", \"actor\": \"a\"}, {\"id\": \"spiffe://example.org/a\", \"actor\": \"b\"}]}}", "duplicate"},
                new String[] {mtls, "{\"type\": \"spiffe\", \"spiffe\": {\"trustDomain\": \"example.org\", \"ids\": ["
                        + "{\"id\": \"spiffe://other.org/a\", \"actor\": \"a\"}]}}", "trust domain"},
                new String[] {mtls, "{\"type\": \"spiffe\", \"jwt\": {}, \"spiffe\": {\"trustDomain\": \"example.org\", \"ids\": ["
                        + "{\"id\": \"spiffe://example.org/a\", \"actor\": \"a\"}]}}", "unknown key"},
                new String[] {mtls, "{\"type\": \"kerberos\"}", "jwt, static or spiffe"});
        for (String[] b : bad) {
            ConfigException e = assertThrows(ConfigException.class,
                    () -> OwnServerSecuritySettings.parse(host(b[0], b[1]), dir, env::get, Clock.systemUTC()), b[0] + " " + b[1]);
            assertTrue(e.getMessage().contains(b[2]), b[2] + " <- " + e.getMessage());
        }
    }
}
