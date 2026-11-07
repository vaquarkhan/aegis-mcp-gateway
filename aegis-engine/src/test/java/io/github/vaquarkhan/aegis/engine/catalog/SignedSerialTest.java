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

import io.github.vaquarkhan.aegis.engine.state.InMemoryStateStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Rollback protection for signed documents: the accepted serial only ever goes up. */
class SignedSerialTest {

    @TempDir
    Path dir;

    @Test
    void theHighestAcceptedSerialIsKeptPerKindAndGatewayAndNeverGoesDown() throws Exception {
        InMemoryStateStore store = new InMemoryStateStore(Clock.systemUTC(), 1000);
        assertEquals(0, SignedSerial.accept(store, "k", "gw", 0, "doc"), "no serial, no record: accepted");
        assertEquals(5, SignedSerial.accept(store, "k", "gw", 5, "doc"));
        assertEquals(5, SignedSerial.accept(store, "k", "gw", 5, "doc"), "the same version is accepted again");
        IOException older = assertThrows(IOException.class, () -> SignedSerial.accept(store, "k", "gw", 4, "doc"));
        assertTrue(older.getMessage().contains("doc has serial 4") && older.getMessage().contains("accepted serial 5"),
                older.getMessage());
        IOException none = assertThrows(IOException.class, () -> SignedSerial.accept(store, "k", "gw", 0, "doc"));
        assertTrue(none.getMessage().contains("has no serial"), none.getMessage());

        assertEquals(9, SignedSerial.accept(store, "k", "gw", 9, "doc"));
        // One atomic key per kind and gateway holds the latest serial.
        assertEquals(1, store.scan(SignedSerial.PREFIX + "k:", 100).size());
        assertThrows(IOException.class, () -> SignedSerial.accept(store, "k", "gw", 5, "doc"));

        // Kinds and gateways are independent, including a gateway name that extends another one.
        assertEquals(1, SignedSerial.accept(store, "other", "gw", 1, "doc"));
        assertEquals(1, SignedSerial.accept(store, "k", "gw:2", 1, "doc"));
        assertEquals(1, SignedSerial.accept(store, "k", "g", 1, "doc"));
    }

    @Test
    void anUnreadableRecordRefusesTheDocument() {
        InMemoryStateStore store = new InMemoryStateStore(Clock.systemUTC(), 1000);
        String key = SignedSerial.PREFIX + "k:" + java.util.HexFormat.of().formatHex("gw".getBytes(StandardCharsets.UTF_8));
        store.put(key, "not-a-number", null);
        IOException e = assertThrows(IOException.class, () -> SignedSerial.accept(store, "k", "gw", 7, "doc"));
        assertTrue(e.getMessage().contains("unreadable serial"), e.getMessage());
    }

    @Test
    void everySignedDocumentCarriesAnOptionalSerialThatMustBeAPositiveWholeNumber() throws Exception {
        String[] kp = SignedCatalogManifest.generateKeyPair();
        Path key = Files.writeString(dir.resolve("k.key"), kp[0]);
        Path pub = Files.writeString(dir.resolve("k.pub"), kp[1]);
        Path file = dir.resolve("m.json");
        Path sig = dir.resolve("m.json.sig");
        Map<String, String> pins = Map.of("t", "sha256:" + "a".repeat(64));

        write(file, sig, key, SignedCatalogManifest.create(pins, "gw", 12));
        assertEquals(12, SignedCatalogManifest.loadWithSerial(file, sig, pub, "gw").serial());
        write(file, sig, key, SignedCatalogManifest.create(pins, "gw"));
        assertEquals(0, SignedCatalogManifest.loadWithSerial(file, sig, pub, "gw").serial());

        for (String bad : List.of("0", "-1", "1.5", "\"3\"", "9007199254740992")) {
            write(file, sig, key, ("{\"version\":1,\"serial\":" + bad + ",\"pins\":{\"t\":\"sha256:" + "a".repeat(64) + "\"}}")
                    .getBytes(StandardCharsets.UTF_8));
            IOException e = assertThrows(IOException.class, () -> SignedCatalogManifest.loadWithSerial(file, sig, pub, "gw"), bad);
            assertTrue(e.getMessage().contains("\"serial\" must be a whole number"), bad + ": " + e.getMessage());
        }
    }

    private static void write(Path file, Path sig, Path key, byte[] body) throws Exception {
        Files.write(file, body);
        Files.writeString(sig, SignedCatalogManifest.sign(body, SignedCatalogManifest.readPrivateKey(key)));
    }
}
