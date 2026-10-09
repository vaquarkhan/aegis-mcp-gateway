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
package io.github.vaquarkhan.aegis.engine.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SignedCatalogManifestTest {

    static final String D1 = "sha256:" + "a".repeat(64);
    static final String D2 = "sha256:" + "b".repeat(64);

    @TempDir
    Path dir;
    Path priv;
    Path pub;
    Path manifest;
    Path sig;

    @BeforeEach
    void keys() throws Exception {
        String[] kp = SignedCatalogManifest.generateKeyPair();
        priv = Files.writeString(dir.resolve("signing.key"), kp[0]);
        pub = Files.writeString(dir.resolve("signing.pub"), kp[1]);
        manifest = dir.resolve("manifest.json");
        sig = dir.resolve("manifest.json.sig");
    }

    private void signed(byte[] body) throws IOException {
        Files.write(manifest, body);
        Files.writeString(sig, SignedCatalogManifest.sign(body, SignedCatalogManifest.readPrivateKey(priv)));
    }

    private IOException refused() {
        return assertThrows(IOException.class, () -> SignedCatalogManifest.load(manifest, sig, pub, "gw"));
    }

    @Test
    void aSignedManifestLoadsItsPins() throws Exception {
        signed(SignedCatalogManifest.create(Map.of("b_tool", D2, "a_tool", D1), "gw"));
        assertEquals(Map.of("a_tool", D1, "b_tool", D2), SignedCatalogManifest.load(manifest, sig, pub, "gw"));
        assertTrue(Files.readString(pub).startsWith("-----BEGIN PUBLIC KEY-----"));
        assertTrue(Files.readString(priv).startsWith("-----BEGIN " + "PRIVATE KEY-----"));
    }

    @Test
    void anyChangeToTheSignedBytesIsRefused() throws Exception {
        signed(SignedCatalogManifest.create(Map.of("a_tool", D1), "gw"));
        Files.writeString(manifest, Files.readString(manifest).replace(D1, D2));
        assertTrue(refused().getMessage().contains("does not verify"));

        signed(SignedCatalogManifest.create(Map.of("a_tool", D1), "gw"));
        Files.writeString(manifest, Files.readString(manifest) + " ");
        assertTrue(refused().getMessage().contains("does not verify"), "even whitespace is signed");
    }

    @Test
    void aSignatureFromAnotherKeyOrAGarbledSignatureIsRefused() throws Exception {
        signed(SignedCatalogManifest.create(Map.of("a_tool", D1), "gw"));
        String[] other = SignedCatalogManifest.generateKeyPair();
        Files.writeString(pub, other[1]);
        assertTrue(refused().getMessage().contains("does not verify"));

        Files.writeString(sig, Base64.getEncoder().encodeToString(new byte[10]));
        assertTrue(refused().getMessage().contains("does not verify"));
        Files.writeString(sig, "not base64 !!!");
        assertTrue(refused().getMessage().contains("not base64"));
    }

    @Test
    void onlyEd25519KeysAreAccepted() throws Exception {
        byte[] rsa = KeyPairGenerator.getInstance("RSA").generateKeyPair().getPublic().getEncoded();
        Files.writeString(pub, "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder().encodeToString(rsa)
                + "\n-----END PUBLIC KEY-----\n");
        Files.writeString(manifest, "{}");
        Files.writeString(sig, "AAAA");
        assertTrue(refused().getMessage().contains("not an Ed25519 public key"), refused().getMessage());
        Files.writeString(pub, "hello");
        assertTrue(refused().getMessage().contains("not a PEM"));
    }

    @Test
    void aVerifiedManifestMustStillBeWellFormedAndForThisGateway() throws Exception {
        signed(SignedCatalogManifest.create(Map.of("a_tool", D1), "other-gateway"));
        assertTrue(refused().getMessage().contains("is for gateway 'other-gateway'"));

        signed(SignedCatalogManifest.create(Map.of("a_tool", D1), null));
        assertEquals(Map.of("a_tool", D1), SignedCatalogManifest.load(manifest, sig, pub, "gw"), "the gateway field is optional");

        signed("{\"version\":2,\"pins\":{\"a\":\"%s\"}}".formatted(D1).getBytes(StandardCharsets.UTF_8));
        assertTrue(refused().getMessage().contains("version"));
        signed("{\"version\":1,\"pins\":{}}".getBytes(StandardCharsets.UTF_8));
        assertTrue(refused().getMessage().contains("non-empty"));
        signed("{\"version\":1,\"pins\":{\"a\":\"md5:00\"}}".getBytes(StandardCharsets.UTF_8));
        assertTrue(refused().getMessage().contains("not a sha256 digest"));
        signed("{\"version\":1,\"pins\":{\"a\":\"%s\"},\"extra\":true}".formatted(D1).getBytes(StandardCharsets.UTF_8));
        assertTrue(refused().getMessage().contains("unknown key 'extra'"));
        signed("[1]".getBytes(StandardCharsets.UTF_8));
        assertTrue(refused().getMessage().contains("not a JSON object"));
    }

    @Test
    void manifestPinsAreAuthoritativeInRequirePinsMode() throws Exception {
        CatalogIntegrity integrity = new CatalogIntegrity(CatalogIntegrity.Mode.REQUIRE_PINS, Map.of("a_tool", D1), null);
        assertEquals(CatalogIntegrity.Verdict.MATCH, integrity.verify("a_tool", D1, true));
        assertEquals(CatalogIntegrity.Verdict.MISMATCH, integrity.verify("a_tool", D2, true));
        assertEquals(CatalogIntegrity.Verdict.UNPINNED, integrity.verify("b_tool", D2, true));
        assertEquals(false, integrity.accept("a_tool", D2), "an operator cannot override a signed pin at run time");
    }
}
