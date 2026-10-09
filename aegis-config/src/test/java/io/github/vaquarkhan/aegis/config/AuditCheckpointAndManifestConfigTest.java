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
package io.github.vaquarkhan.aegis.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.engine.audit.AuditCheckpointVerifier;
import io.github.vaquarkhan.aegis.engine.catalog.SignedCatalogManifest;
import io.github.vaquarkhan.aegis.engine.catalog.ToolCatalog;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.model.ToolCall;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code audit.checkpoint} and {@code catalog.manifest} as configuration: wiring, strictness and refusal. */
class AuditCheckpointAndManifestConfigTest {

    @TempDir
    Path dir;

    static final Map<String, String> ENV = Map.of("AEGIS_SIGNING_KEY", "k".repeat(48));

    private BuildContext ctx() {
        return BuildContext.defaults(dir).withEnv(ENV::get);
    }

    private static Map<String, Object> config(Map<String, Object> audit, Map<String, Object> catalog) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("gateway", Map.of("name", "gw-config"));
        c.put("approval", Map.of("keyEnv", "AEGIS_SIGNING_KEY"));
        c.put("policy", Map.of("rules", List.of(Map.of("id", "r", "groups", List.of("dev"), "classes", List.of("READ")))));
        c.put("audit", audit);
        if (catalog != null) {
            c.put("catalog", catalog);
        }
        c.put("egress", Map.of("allowCidrs", List.of("127.0.0.0/8")));
        c.put("sources", List.of(Map.of("id", "s", "type", "http", "baseUrl", "http://127.0.0.1:1", "tools", List.of(
                Map.of("name", "ping", "description", "Ping.", "class", "READ"),
                Map.of("name", "pong", "description", "Pong.", "class", "READ")))));
        return c;
    }

    @Test
    void checkpointsAreWrittenForTheConfiguredJournalAndVerify() throws Exception {
        Map<String, Object> audit = Map.of("file", "audit.jsonl",
                "checkpoint", Map.of("file", "anchor/heads.jsonl", "everyRecords", 1L, "everySeconds", 0L));
        try (Gateway g = GatewayFactory.build(config(audit, null), ctx())) {
            g.engine().call(CallerIdentity.of("u", Set.of("dev"), "i", "t"), new ToolCall("1", "ping", Map.of(), Map.of(), ""));
        }
        AuditCheckpointVerifier.Report r = AuditCheckpointVerifier.verify(dir.resolve("audit.jsonl"), dir.resolve("anchor/heads.jsonl"));
        assertTrue(r.ok(), r.error());
        assertTrue(r.checkpoints() >= 1);
        assertEquals(r.records(), r.checkpointSeq(), "every record is anchored at everyRecords: 1");
    }

    @Test
    void checkpointSettingsAreValidated() {
        for (Map<String, Object> cp : List.<Map<String, Object>>of(
                Map.of("everyRecords", 1L),
                Map.of("file", "c.jsonl", "everyRecords", 0L),
                Map.of("file", "c.jsonl", "everySeconds", -1L),
                Map.of("file", "c.jsonl", "everySeconds", 86_401L),
                Map.of("file", "./audit.jsonl"),
                Map.of("file", "c.jsonl", "unknown", true))) {
            ConfigException e = assertThrows(ConfigException.class, () -> GatewayFactory.build(
                    config(Map.of("file", "audit.jsonl", "checkpoint", cp), null), ctx()), String.valueOf(cp));
            assertTrue(e.getMessage().contains("audit.checkpoint"), cp + " -> " + e.getMessage());
        }
    }

    @Test
    void signedPinsActivateMatchingToolsAndRefuseEverythingElse() throws Exception {
        Map<String, Object> audit = Map.of("file", "audit.jsonl");
        Map<String, String> digests = new TreeMap<>();
        try (Gateway g = GatewayFactory.build(config(audit, Map.of("mode", "REQUIRE_PINS")), ctx())) {
            for (ToolCatalog.Entry e : g.engine().catalog().entries()) {
                assertEquals(ToolCatalog.Status.QUARANTINED, e.status());
                digests.put(e.tool().exposedName(), e.digest());
            }
        }
        String[] keys = SignedCatalogManifest.generateKeyPair();
        Files.writeString(dir.resolve("pub.pem"), keys[1]);
        byte[] body = SignedCatalogManifest.create(Map.of("ping", digests.get("ping")), "gw-config");
        Files.write(dir.resolve("m.json"), body);
        Files.writeString(dir.resolve("m.json.sig"), SignedCatalogManifest.sign(body,
                SignedCatalogManifest.readPrivateKey(Files.writeString(dir.resolve("priv.pem"), keys[0]))));
        Map<String, Object> manifest = Map.of("file", "m.json", "publicKeyFile", "pub.pem");

        try (Gateway g = GatewayFactory.build(config(audit, Map.of("mode", "REQUIRE_PINS", "manifest", manifest)), ctx())) {
            Map<String, ToolCatalog.Status> st = new TreeMap<>();
            for (ToolCatalog.Entry e : g.engine().catalog().entries()) {
                st.put(e.tool().exposedName(), e.status());
            }
            assertEquals(Map.of("ping", ToolCatalog.Status.ACTIVE, "pong", ToolCatalog.Status.QUARANTINED), st,
                    "only the signed pin is active; the unsigned tool stays quarantined in REQUIRE_PINS");
            assertTrue(!g.engine().catalog().accept("ping", "sha256:" + "0".repeat(64)), "a signed pin is not overridable");
        }

        // An inline pin that agrees with the manifest is fine; one that disagrees is refused.
        try (Gateway g = GatewayFactory.build(config(audit, Map.of("mode", "REQUIRE_PINS", "manifest", manifest,
                "pins", Map.of("ping", digests.get("ping")))), ctx())) {
            assertTrue(g.engine().catalog().lookup("ping").orElseThrow().active());
        }
        ConfigException conflict = assertThrows(ConfigException.class, () -> GatewayFactory.build(config(audit, Map.of(
                "mode", "REQUIRE_PINS", "manifest", manifest, "pins", Map.of("ping", digests.get("pong")))), ctx()));
        assertTrue(conflict.getMessage().contains("differently from the signed manifest"), conflict.getMessage());

        ConfigException noKey = assertThrows(ConfigException.class, () -> GatewayFactory.build(config(audit, Map.of(
                "manifest", Map.of("file", "m.json"))), ctx()));
        assertTrue(noKey.getMessage().contains("publicKeyFile"), noKey.getMessage());

        Files.writeString(dir.resolve("m.json.sig"), SignedCatalogManifest.sign(
                SignedCatalogManifest.create(Map.of("ping", digests.get("pong")), "gw-config"),
                SignedCatalogManifest.readPrivateKey(dir.resolve("priv.pem"))));
        ConfigException tampered = assertThrows(ConfigException.class, () -> GatewayFactory.build(config(audit, Map.of(
                "manifest", manifest)), ctx()));
        assertTrue(tampered.getMessage().contains("config.catalog.manifest") && tampered.getMessage().contains("does not verify"),
                tampered.getMessage());
    }
}
