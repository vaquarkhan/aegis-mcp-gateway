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

import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.json.JsonException;
import io.github.vaquarkhan.aegis.engine.json.JsonLimits;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * A tool-definition pin list signed with Ed25519 by whoever reviews tool definitions, so the gateway
 * host can be given pins it cannot alter: an attacker who can write the gateway's config directory but
 * does not hold the signing key cannot add or change a pin. With a signed {@code serial}, an older
 * manifest is refused once a newer one was accepted ({@link SignedSerial}).
 *
 * <p>Format: {@code {"version": 1, "serial": <optional n>, "gateway": "<optional gateway.name>",
 * "pins": {"<tool>": "sha256:<hex>"}}}.
 * The signature file holds the base64 Ed25519 signature over the manifest's exact bytes. Keys are PEM:
 * the public key as X.509 SubjectPublicKeyInfo ({@code BEGIN PUBLIC KEY}), the private key as PKCS#8
 * ({@code BEGIN PRIVATE KEY}); {@code openssl genpkey -algorithm ed25519} produces the same formats.
 */
public final class SignedCatalogManifest {

    /** The name under which the highest accepted manifest serial is kept ({@link SignedSerial}). */
    public static final String SERIAL_KIND = "aegis-catalog-manifest";

    private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final String ALGORITHM = "Ed25519";

    private SignedCatalogManifest() {}

    /**
     * Verifies the signature first, then parses. Nothing in an unverified manifest is read.
     *
     * @param gatewayName the configured gateway name; a manifest naming another gateway is refused
     * @return the pins, tool name to definition digest
     */
    public static Map<String, String> load(Path manifest, Path signature, Path publicKey, String gatewayName)
            throws IOException {
        return loadWithSerial(manifest, signature, publicKey, gatewayName).pins();
    }

    /**
     * A verified manifest.
     *
     * @param pins   tool name to definition digest
     * @param serial the signed {@code "serial"} for rollback protection ({@link SignedSerial}); 0 when absent
     */
    public record Loaded(Map<String, String> pins, long serial) {}

    /** As {@link #load}, also returning the signed serial. */
    public static Loaded loadWithSerial(Path manifest, Path signature, Path publicKey, String gatewayName)
            throws IOException {
        Map<String, Object> m = verifiedObject("catalog manifest", manifest, signature, publicKey);
        for (String k : m.keySet()) {
            if (!List.of("version", "serial", "gateway", "pins").contains(k)) {
                throw new IOException("catalog manifest " + manifest + " has an unknown key '" + k + "'");
            }
        }
        if (!Long.valueOf(1).equals(m.get("version"))) {
            throw new IOException("catalog manifest " + manifest + " must have \"version\": 1");
        }
        Object gw = m.get("gateway");
        if (gw != null && !(gw instanceof String g && g.equals(gatewayName))) {
            throw new IOException("catalog manifest " + manifest + " is for gateway '" + gw + "', not '" + gatewayName + "'");
        }
        if (!(m.get("pins") instanceof Map<?, ?> pins) || pins.isEmpty()) {
            throw new IOException("catalog manifest " + manifest + " needs a non-empty \"pins\" object");
        }
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<?, ?> e : pins.entrySet()) {
            if (!(e.getValue() instanceof String d) || !DIGEST.matcher(d).matches()) {
                throw new IOException("catalog manifest " + manifest + " pin for '" + e.getKey() + "' is not a sha256 digest");
            }
            out.put((String) e.getKey(), d);
        }
        return new Loaded(out, SignedSerial.parse("catalog manifest " + manifest, m.get("serial")));
    }

    /**
     * Verifies the Ed25519 signature over the file's exact bytes, then parses it as a JSON object.
     * Shared by every signed operator document (tool pins, server catalog, break-glass containment)
     * so none of them reads unverified bytes.
     */
    static Map<String, Object> verifiedObject(String what, Path manifest, Path signature, Path publicKey)
            throws IOException {
        return verifiedObject(what, manifest, signature, readPublicKey(publicKey), publicKey.toString());
    }

    /** Verifies against immutable key material already read by the caller. */
    static Map<String, Object> verifiedObject(String what, Path manifest, Path signature, PublicKey publicKey,
                                              String publicKeyDescription) throws IOException {
        byte[] body = Files.readAllBytes(manifest);
        byte[] sig;
        try {
            sig = Base64.getMimeDecoder().decode(Files.readString(signature, StandardCharsets.US_ASCII).trim());
        } catch (IllegalArgumentException e) {
            throw new IOException(what + " signature " + signature + " is not base64");
        }
        if (!verify(body, sig, publicKey)) {
            throw new IOException(what + " " + manifest + " does not verify against " + publicKeyDescription);
        }
        try {
            return Json.parseObject(body, JsonLimits.CONFIG);
        } catch (JsonException e) {
            throw new IOException(what + " " + manifest + " is not a JSON object");
        }
    }

    /** Builds manifest bytes from tool name to digest pins (sorted, stable), ready to sign. */
    public static byte[] create(Map<String, String> pins, String gatewayName) {
        return create(pins, gatewayName, 0);
    }

    /** As {@link #create(Map, String)} with a signed {@code serial} (0 omits it). */
    public static byte[] create(Map<String, String> pins, String gatewayName, long serial) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("version", 1L);
        if (serial > 0) {
            m.put("serial", serial);
        }
        if (gatewayName != null && !gatewayName.isBlank()) {
            m.put("gateway", gatewayName);
        }
        m.put("pins", new TreeMap<>(pins));
        return (Json.write(m) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /** @return the base64 signature over {@code body} */
    public static String sign(byte[] body, PrivateKey key) throws IOException {
        try {
            Signature s = Signature.getInstance(ALGORITHM);
            s.initSign(key);
            s.update(body);
            return Base64.getEncoder().encodeToString(s.sign());
        } catch (GeneralSecurityException e) {
            throw new IOException("could not sign the catalog manifest: " + e.getMessage(), e);
        }
    }

    static boolean verify(byte[] body, byte[] sig, PublicKey key) throws IOException {
        try {
            Signature s = Signature.getInstance(ALGORITHM);
            s.initVerify(key);
            s.update(body);
            return s.verify(sig);
        } catch (java.security.SignatureException malformed) {
            return false;
        } catch (GeneralSecurityException e) {
            throw new IOException("could not verify the catalog manifest: " + e.getMessage(), e);
        }
    }

    /** @return {privatePem, publicPem} of a new Ed25519 key pair */
    public static String[] generateKeyPair() throws IOException {
        try {
            KeyPair kp = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
            return new String[] {pem("PRIVATE KEY", kp.getPrivate().getEncoded()), pem("PUBLIC KEY", kp.getPublic().getEncoded())};
        } catch (GeneralSecurityException e) {
            throw new IOException("could not generate an Ed25519 key pair: " + e.getMessage(), e);
        }
    }

    public static PublicKey readPublicKey(Path file) throws IOException {
        byte[] der = unpem(file, "PUBLIC KEY");
        try {
            PublicKey k = KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(der));
            requireEd25519(k.getAlgorithm(), file);
            return k;
        } catch (GeneralSecurityException e) {
            throw new IOException("catalog manifest public key " + file + " is not an Ed25519 public key");
        }
    }

    public static PrivateKey readPrivateKey(Path file) throws IOException {
        byte[] der = unpem(file, "PRIVATE KEY");
        try {
            PrivateKey k = KeyFactory.getInstance(ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(der));
            requireEd25519(k.getAlgorithm(), file);
            return k;
        } catch (GeneralSecurityException e) {
            throw new IOException("catalog manifest private key " + file + " is not an Ed25519 private key");
        }
    }

    private static void requireEd25519(String algorithm, Path file) throws IOException {
        if (!"Ed25519".equalsIgnoreCase(algorithm) && !"EdDSA".equalsIgnoreCase(algorithm)) {
            throw new IOException(file + " holds a " + algorithm + " key; catalog manifests use Ed25519");
        }
    }

    private static byte[] unpem(Path file, String type) throws IOException {
        String text = Files.readString(file, StandardCharsets.US_ASCII);
        String begin = "-----BEGIN " + type + "-----";
        String end = "-----END " + type + "-----";
        int b = text.indexOf(begin);
        int e = text.indexOf(end);
        if (b < 0 || e < b) {
            throw new IOException(file + " is not a PEM '" + type + "' file");
        }
        try {
            return Base64.getMimeDecoder().decode(text.substring(b + begin.length(), e).trim());
        } catch (IllegalArgumentException bad) {
            throw new IOException(file + " has invalid PEM content");
        }
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }
}
