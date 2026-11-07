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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.engine.catalog.SignedServerCatalog.Server;
import io.github.vaquarkhan.aegis.engine.catalog.SignedServerCatalog.State;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The offline signed server catalog and the containment-only break-glass order. */
class SignedServerCatalogTest {

    static final String D1 = "sha256:" + "a".repeat(64);
    static final String D2 = "sha256:" + "b".repeat(64);
    static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    @TempDir
    Path dir;
    Path priv;
    Path pub;
    Path file;
    Path sig;

    @BeforeEach
    void keys() throws Exception {
        String[] kp = SignedCatalogManifest.generateKeyPair();
        priv = Files.writeString(dir.resolve("review.key"), kp[0]);
        pub = Files.writeString(dir.resolve("review.pub"), kp[1]);
        file = dir.resolve("servers.json");
        sig = dir.resolve("servers.json.sig");
    }

    private void signed(byte[] body) throws IOException {
        Files.write(file, body);
        Files.writeString(sig, SignedCatalogManifest.sign(body, SignedCatalogManifest.readPrivateKey(priv)));
    }

    private void signedJson(String json) throws IOException {
        signed(json.getBytes(StandardCharsets.UTF_8));
    }

    private IOException catalogRefused() {
        return assertThrows(IOException.class, () -> SignedServerCatalog.load(file, sig, pub, "gw"));
    }

    private IOException orderRefused(Instant now) {
        return assertThrows(IOException.class, () -> BreakGlassContainment.load(file, sig, pub, "gw", now));
    }

    private static Map<String, Server> two() {
        Map<String, Server> m = new TreeMap<>();
        m.put("kafka", new Server("kafka", State.ACTIVE, D1, Map.of("list_topics", D2, "prompt:triage", D1), null));
        m.put("legacy", new Server("legacy", State.REVOKED, null, Map.of(), "INC-42: exfiltration risk"));
        return m;
    }

    @Test
    void aSignedCatalogRoundTripsActiveAndNonActiveServers() throws Exception {
        signed(SignedServerCatalog.create(two(), "gw"));
        Map<String, Server> loaded = SignedServerCatalog.load(file, sig, pub, "gw");
        assertEquals(two(), loaded);
        assertTrue(loaded.get("kafka").active());
        assertFalse(loaded.get("legacy").active());
        assertTrue(Files.readString(file).contains("\"kind\":\"aegis-server-catalog\""));
    }

    @Test
    void anyChangeToTheSignedBytesOrAnotherKeyIsRefusedBeforeParsing() throws Exception {
        signed(SignedServerCatalog.create(two(), "gw"));
        Files.writeString(file, Files.readString(file).replace("REVOKED", "ACTIVE"));
        assertTrue(catalogRefused().getMessage().contains("does not verify"));

        signed(SignedServerCatalog.create(two(), "gw"));
        Files.writeString(pub, SignedCatalogManifest.generateKeyPair()[1]);
        assertTrue(catalogRefused().getMessage().contains("does not verify"));

        // Unverified garbage is never parsed: the signature check fails first.
        Files.writeString(file, "not json");
        assertTrue(catalogRefused().getMessage().contains("does not verify"));
    }

    @Test
    void anAlreadyLoadedReviewKeyCannotBeSwappedBeforeVerification() throws Exception {
        signed(SignedServerCatalog.create(two(), "gw"));
        java.security.PublicKey anchored = SignedCatalogManifest.readPublicKey(pub);
        // Simulate a configuration-directory attacker replacing the configured PEM after the host
        // anchored it. Verification with immutable key material remains bound to the host's key.
        Files.writeString(pub, SignedCatalogManifest.generateKeyPair()[1]);
        assertEquals(two(), SignedServerCatalog.loadWithSerial(file, sig, anchored, "gw").servers());
        assertTrue(catalogRefused().getMessage().contains("does not verify"));
    }

    @Test
    void documentsSignedByTheSameKeyCannotBeReplayedAsEachOther() throws Exception {
        // A tool-pin manifest is not a server catalog...
        signed(SignedCatalogManifest.create(Map.of("list_topics", D1), "gw"));
        assertTrue(catalogRefused().getMessage().contains("unknown key 'pins'"));
        // ...a server catalog is not a tool-pin manifest...
        signed(SignedServerCatalog.create(two(), "gw"));
        assertThrows(IOException.class, () -> SignedCatalogManifest.load(file, sig, pub, "gw"));
        // ...and neither is a break-glass order.
        assertTrue(orderRefused(NOW).getMessage().contains("\"kind\": \"aegis-break-glass\""));
        signedJson("{\"version\":1,\"servers\":{\"kafka\":{\"state\":\"REVOKED\",\"evidence\":\"x\"}}}");
        assertTrue(catalogRefused().getMessage().contains("\"kind\""), "the kind is required");
    }

    @Test
    void recordsMustBeWellFormedAndNeverVouchForMoreThanTheySay() throws Exception {
        String head = "{\"version\":1,\"kind\":\"aegis-server-catalog\",\"servers\":";
        for (Map.Entry<String, String> c : Map.of(
                "{\"kafka\":{\"state\":\"ACTIVE\",\"pins\":{\"t\":\"" + D1 + "\"}}}", "sourceDigest",
                "{\"kafka\":{\"state\":\"ACTIVE\",\"sourceDigest\":\"" + D1 + "\"}}", "non-empty \"pins\"",
                "{\"kafka\":{\"state\":\"ACTIVE\",\"sourceDigest\":\"" + D1 + "\",\"pins\":{\"t\":\"md5:x\"}}}", "not a sha256",
                "{\"kafka\":{\"state\":\"REVOKED\"}}", "needs \"evidence\"",
                "{\"kafka\":{\"state\":\"QUARANTINED\",\"evidence\":\"x\",\"pins\":{\"t\":\"" + D1 + "\"}}}", "must not carry",
                "{\"kafka\":{\"state\":\"active\",\"sourceDigest\":\"" + D1 + "\",\"pins\":{\"t\":\"" + D1 + "\"}}}", "must be ACTIVE",
                "{\"Kafka\":{\"state\":\"REVOKED\",\"evidence\":\"x\"}}", "id must match",
                "{\"kafka\":{\"state\":\"REVOKED\",\"evidence\":\"x\",\"url\":\"https://x\"}}", "unknown key 'url'",
                "{\"a\":{\"state\":\"ACTIVE\",\"sourceDigest\":\"" + D1 + "\",\"pins\":{\"t\":\"" + D1 + "\"}},"
                        + "\"b\":{\"state\":\"ACTIVE\",\"sourceDigest\":\"" + D2 + "\",\"pins\":{\"t\":\"" + D1 + "\"}}}",
                "already pinned by server 'a'",
                "{}", "non-empty \"servers\"").entrySet()) {
            signedJson(head + c.getKey() + "}");
            IOException e = catalogRefused();
            assertTrue(e.getMessage().contains(c.getValue()), c.getKey() + " -> " + e.getMessage());
        }
        signed(SignedServerCatalog.create(two(), "other-gw"));
        assertTrue(catalogRefused().getMessage().contains("is for gateway 'other-gw'"));
    }

    @Test
    void signedOnlyPinningNeverLearnsOrAcceptsADefinition() throws Exception {
        CatalogIntegrity locked = new CatalogIntegrity(CatalogIntegrity.Mode.REQUIRE_PINS, Map.of("a", D1), null, true);
        assertTrue(locked.configPinsOnly());
        assertEquals(CatalogIntegrity.Verdict.MATCH, locked.verify("a", D1, true));
        assertEquals(CatalogIntegrity.Verdict.MISMATCH, locked.verify("a", D2, true));
        assertEquals(CatalogIntegrity.Verdict.UNPINNED, locked.verify("b", D2, true), "no first-sight trust");
        assertFalse(locked.accept("b", D2), "an unpinned definition cannot be accepted at run time");
        assertFalse(locked.accept("a", D2), "a changed definition cannot be accepted at run time");
        // Prompt acceptance (POST /aegis/v1/prompts/{p}/accept) goes through the same integrity with a
        // "prompt:" key, so it is refused the same way.
        assertFalse(locked.accept("prompt:triage", D2), "a prompt cannot be accepted at run time either");
        assertEquals(CatalogIntegrity.Verdict.UNPINNED, locked.verify("b", D2, true));

        // The same calls succeed without the lock, so the assertions above are the lock's doing.
        CatalogIntegrity open = new CatalogIntegrity(CatalogIntegrity.Mode.REQUIRE_PINS, Map.of("a", D1), null);
        assertTrue(open.accept("b", D2));
        assertEquals(CatalogIntegrity.Verdict.MATCH, open.verify("b", D2, true));

        assertThrows(IllegalArgumentException.class,
                () -> new CatalogIntegrity(CatalogIntegrity.Mode.TOFU, Map.of(), null, true));
        assertThrows(IllegalArgumentException.class,
                () -> new CatalogIntegrity(CatalogIntegrity.Mode.REQUIRE_PINS, Map.of(), dir.resolve("pins.json"), true));
    }

    @Test
    void aBreakGlassOrderContainsInsideItsWindowOnly() throws Exception {
        Instant from = NOW.minus(Duration.ofHours(1));
        Instant to = NOW.plus(Duration.ofHours(1));
        signed(BreakGlassContainment.create("INC-7", from, to, Map.of("kafka", State.QUARANTINED), "gw"));
        BreakGlassContainment.Order o = BreakGlassContainment.load(file, sig, pub, "gw", NOW);
        assertEquals("INC-7", o.incident());
        assertEquals(Map.of("kafka", State.QUARANTINED), o.servers());

        assertTrue(orderRefused(from.minusSeconds(1)).getMessage().contains("is not valid before"));
        assertTrue(orderRefused(to).getMessage().contains("expired"), "expiry refuses startup instead of silently re-activating");

        Files.writeString(file, Files.readString(file).replace("QUARANTINED", "REVOKED"));
        assertTrue(orderRefused(NOW).getMessage().contains("does not verify"));
    }

    @Test
    void aBreakGlassOrderCanOnlyTakeCapabilityAway() throws Exception {
        Instant from = NOW.minus(Duration.ofHours(1));
        signed(BreakGlassContainment.create("INC-7", from, NOW.plus(Duration.ofHours(1)), Map.of("kafka", State.ACTIVE), "gw"));
        assertTrue(orderRefused(NOW).getMessage().contains("can only contain, never activate"));

        signed(BreakGlassContainment.create("INC-7", from, from.plus(Duration.ofHours(25)), Map.of("kafka", State.REVOKED), "gw"));
        assertTrue(orderRefused(NOW).getMessage().contains("must not exceed 24 hours"));

        signed(BreakGlassContainment.create("INC-7", NOW, from, Map.of("kafka", State.REVOKED), "gw"));
        assertTrue(orderRefused(NOW).getMessage().contains("notAfter must be after notBefore"));

        signedJson("{\"version\":1,\"kind\":\"aegis-break-glass\",\"notBefore\":\"" + from + "\",\"notAfter\":\""
                + NOW.plusSeconds(60) + "\",\"servers\":{\"kafka\":\"REVOKED\"}}");
        assertTrue(orderRefused(NOW).getMessage().contains("needs an \"incident\""));

        signed(BreakGlassContainment.create("INC-7", from, NOW.plusSeconds(60), Map.of("kafka", State.REVOKED), "other-gw"));
        assertTrue(orderRefused(NOW).getMessage().contains("is for gateway 'other-gw'"));
    }
}
