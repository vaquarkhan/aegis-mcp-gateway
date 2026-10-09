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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.engine.catalog.BreakGlassContainment;
import io.github.vaquarkhan.aegis.engine.catalog.SignedCatalogManifest;
import io.github.vaquarkhan.aegis.engine.catalog.SignedServerCatalog;
import io.github.vaquarkhan.aegis.engine.catalog.SignedServerCatalog.Server;
import io.github.vaquarkhan.aegis.engine.catalog.SignedServerCatalog.State;
import io.github.vaquarkhan.aegis.engine.catalog.ToolCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code serverCatalog}: an offline signed register of which configured sources may start, at which
 * reviewed configuration, with which exact tools; plus the containment-only break-glass order.
 */
class ServerCatalogConfigTest {

    @TempDir
    Path dir;

    static final Map<String, String> ENV = Map.of("AEGIS_SIGNING_KEY", "k".repeat(48));

    Map<String, String> digests = new TreeMap<>();
    Path reviewKey;
    Path incidentKey;

    private BuildContext ctx() {
        return BuildContext.defaults(dir).withEnv(ENV::get);
    }

    static Map<String, Object> sourceS() {
        return source("s", "http://127.0.0.1:1", "ping", "pong");
    }

    static Map<String, Object> sourceT() {
        return source("t", "http://127.0.0.2:1", "zap");
    }

    static Map<String, Object> source(String id, String url, String... tools) {
        List<Object> t = new java.util.ArrayList<>();
        for (String name : tools) {
            t.add(Map.of("name", name, "description", name + ".", "class", "READ"));
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("id", id);
        s.put("type", "http");
        s.put("baseUrl", url);
        s.put("tools", t);
        return s;
    }

    private static Map<String, Object> config(List<Map<String, Object>> sources, Map<String, Object> catalog,
                                              Map<String, Object> serverCatalog) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("gateway", Map.of("name", "gw-sc"));
        c.put("approval", Map.of("keyEnv", "AEGIS_SIGNING_KEY"));
        c.put("policy", Map.of("rules", List.of(Map.of("id", "r", "groups", List.of("dev"), "classes", List.of("READ")))));
        c.put("audit", Map.of("file", "audit.jsonl"));
        if (catalog != null) {
            c.put("catalog", catalog);
        }
        if (serverCatalog != null) {
            c.put("serverCatalog", serverCatalog);
        }
        c.put("egress", Map.of("allowCidrs", List.of("127.0.0.0/8")));
        c.put("sources", sources);
        return c;
    }

    @BeforeEach
    void discoverDigestsAndKeys() throws Exception {
        try (Gateway g = GatewayFactory.build(config(List.of(sourceS(), sourceT()), Map.of("mode", "REQUIRE_PINS"), null), ctx())) {
            for (ToolCatalog.Entry e : g.engine().catalog().entries()) {
                digests.put(e.tool().exposedName(), e.digest());
            }
        }
        assertEquals(List.of("ping", "pong", "zap"), List.copyOf(digests.keySet()));
        reviewKey = keyPair("review");
        incidentKey = keyPair("incident");
    }

    private Path keyPair(String name) throws Exception {
        String[] kp = SignedCatalogManifest.generateKeyPair();
        Files.writeString(dir.resolve(name + ".key"), kp[0]);
        return Files.writeString(dir.resolve(name + ".pub"), kp[1]);
    }

    private void sign(String file, byte[] body, String key) throws Exception {
        Files.write(dir.resolve(file), body);
        Files.writeString(dir.resolve(file + ".sig"), SignedCatalogManifest.sign(body,
                SignedCatalogManifest.readPrivateKey(dir.resolve(key + ".key"))));
    }

    private Server active(String id, Map<String, Object> source, String... pinned) {
        Map<String, String> pins = new TreeMap<>();
        for (String p : pinned) {
            pins.put(p, digests.get(p));
        }
        return new Server(id, State.ACTIVE, GatewayFactory.sourceDigest(source), pins, null);
    }

    private void catalog(Server... servers) throws Exception {
        Map<String, Server> m = new TreeMap<>();
        for (Server s : servers) {
            m.put(s.id(), s);
        }
        sign("servers.json", SignedServerCatalog.create(m, "gw-sc"), "review");
    }

    private static Map<String, Object> serverCatalog() {
        return Map.of("file", "servers.json", "publicKeyFile", "review.pub");
    }

    private static Map<String, Object> withBreakGlass(String key) {
        return Map.of("file", "servers.json", "publicKeyFile", "review.pub",
                "breakGlass", Map.of("file", "order.json", "publicKeyFile", key + ".pub"));
    }

    private static Map<String, ToolCatalog.Status> statuses(Gateway g) {
        Map<String, ToolCatalog.Status> st = new TreeMap<>();
        for (ToolCatalog.Entry e : g.engine().catalog().entries()) {
            st.put(e.tool().exposedName(), e.status());
        }
        return st;
    }

    @Test
    void onlySignedToolsOfActiveServersAreBoundAndNothingCanReTrustAtRunTime() throws Exception {
        catalog(active("s", sourceS(), "ping"),
                new Server("t", State.REVOKED, null, Map.of(), "INC-1: backend compromised"));
        try (Gateway g = GatewayFactory.build(config(List.of(sourceS(), sourceT()), null, serverCatalog()), ctx())) {
            // pong is served by s but not pinned by its record, so it is never bound; t is never started.
            assertEquals(Map.of("ping", ToolCatalog.Status.ACTIVE), statuses(g));
            assertFalse(g.engine().catalog().accept("ping", digests.get("ping")), "a signed pin cannot be re-accepted");
        }
    }

    @Test
    void changedUnlistedOrDuplicatedSourcesAreRefusedAtStartup() throws Exception {
        catalog(active("s", sourceS(), "ping"), active("t", sourceT(), "zap"));
        // A host that can write the config redirects s: the reviewed configuration no longer matches.
        Map<String, Object> moved = sourceS();
        moved.put("baseUrl", "http://127.0.0.9:1");
        ConfigException changed = assertThrows(ConfigException.class, () -> GatewayFactory.build(
                config(List.of(moved, sourceT()), null, serverCatalog()), ctx()));
        assertTrue(changed.getMessage().contains("differs from the configuration reviewed")
                && changed.getMessage().contains(GatewayFactory.sourceDigest(moved)), changed.getMessage());

        ConfigException unlisted = assertThrows(ConfigException.class, () -> GatewayFactory.build(
                config(List.of(sourceS(), source("u", "http://127.0.0.3:1", "x")), null, serverCatalog()), ctx()));
        assertTrue(unlisted.getMessage().contains("source 'u' is not listed in the signed server catalog"), unlisted.getMessage());

        ConfigException dup = assertThrows(ConfigException.class, () -> GatewayFactory.build(
                config(List.of(sourceS(), sourceS()), null, serverCatalog()), ctx()));
        assertTrue(dup.getMessage().contains("duplicate source id 's'"), dup.getMessage());

        // Key order inside the block does not matter; the digest is canonical.
        Map<String, Object> reordered = new LinkedHashMap<>();
        List<String> keys = new java.util.ArrayList<>(sourceS().keySet());
        java.util.Collections.reverse(keys);
        for (String k : keys) {
            reordered.put(k, sourceS().get(k));
        }
        try (Gateway g = GatewayFactory.build(config(List.of(reordered, sourceT()), null, serverCatalog()), ctx())) {
            assertEquals(Map.of("ping", ToolCatalog.Status.ACTIVE, "zap", ToolCatalog.Status.ACTIVE), statuses(g));
        }
    }

    @Test
    void theCatalogSectionCannotWeakenSignedPinning() throws Exception {
        catalog(active("s", sourceS(), "ping"), active("t", sourceT(), "zap"));
        List<Map<String, Object>> both = List.of(sourceS(), sourceT());
        ConfigException tofu = assertThrows(ConfigException.class, () -> GatewayFactory.build(
                config(both, Map.of("mode", "TOFU"), serverCatalog()), ctx()));
        assertTrue(tofu.getMessage().contains("must be REQUIRE_PINS when serverCatalog is configured"), tofu.getMessage());

        ConfigException pinFile = assertThrows(ConfigException.class, () -> GatewayFactory.build(
                config(both, Map.of("pinFile", "pins.json"), serverCatalog()), ctx()));
        assertTrue(pinFile.getMessage().contains("pinFile must not be set"), pinFile.getMessage());

        ConfigException conflict = assertThrows(ConfigException.class, () -> GatewayFactory.build(
                config(both, Map.of("pins", Map.of("ping", digests.get("pong"))), serverCatalog()), ctx()));
        assertTrue(conflict.getMessage().contains("differently from the signed server catalog"), conflict.getMessage());

        // An agreeing inline pin is redundant but harmless.
        try (Gateway g = GatewayFactory.build(config(both, Map.of("pins", Map.of("ping", digests.get("ping"))),
                serverCatalog()), ctx())) {
            assertEquals(Map.of("ping", ToolCatalog.Status.ACTIVE, "zap", ToolCatalog.Status.ACTIVE), statuses(g));
        }

        // An inline pin the signed catalog does not carry cannot vouch for anything: not for a tool the
        // record leaves out, and not for a prompt (prompt pins are not source-bound, so without this
        // check the configuration alone could activate one).
        ConfigException tool = assertThrows(ConfigException.class, () -> GatewayFactory.build(
                config(both, Map.of("pins", Map.of("pong", digests.get("pong"))), serverCatalog()), ctx()));
        assertTrue(tool.getMessage().contains("pins 'pong', which the signed server catalog does not pin"), tool.getMessage());
        ConfigException prompt = assertThrows(ConfigException.class, () -> GatewayFactory.build(
                config(both, Map.of("pins", Map.of("prompt:triage", digests.get("ping"))), serverCatalog()), ctx()));
        assertTrue(prompt.getMessage().contains("pins 'prompt:triage', which the signed server catalog does not pin"),
                prompt.getMessage());

        // The same holds for pins from a signed catalog.manifest: another key cannot widen the catalog.
        String[] other = SignedCatalogManifest.generateKeyPair();
        Files.writeString(dir.resolve("other.key"), other[0]);
        Files.writeString(dir.resolve("other.pub"), other[1]);
        sign("manifest.json", SignedCatalogManifest.create(Map.of("prompt:triage", digests.get("ping")), "gw-sc"), "other");
        ConfigException manifest = assertThrows(ConfigException.class, () -> GatewayFactory.build(
                config(both, Map.of("manifest", Map.of("file", "manifest.json", "publicKeyFile", "other.pub")),
                        serverCatalog()), ctx()));
        assertTrue(manifest.getMessage().contains("pins 'prompt:triage', which the signed server catalog does not pin"),
                manifest.getMessage());
    }

    @Test
    void aSourceTheConfigurationQuarantinesStaysQuarantinedUnderTheCatalog() throws Exception {
        Map<String, Object> held = sourceT();
        held.put("trust", Map.of("state", "QUARANTINED", "evidence", "INC-3: under review"));
        catalog(active("s", sourceS(), "ping"), active("t", held, "zap"));
        try (Gateway g = GatewayFactory.build(config(List.of(sourceS(), held), null, serverCatalog()), ctx())) {
            // The ACTIVE record cannot lift the configuration's own quarantine.
            assertEquals(ToolCatalog.Status.ACTIVE, statuses(g).get("ping"));
            assertTrue(statuses(g).get("zap") != ToolCatalog.Status.ACTIVE, statuses(g).toString());
        }
    }

    @Test
    void aRecordThatIsNotActiveNeverParsesItsSourceBlock() throws Exception {
        // This block would fail to build (unknown type, no egress for its host) if it were parsed.
        Map<String, Object> broken = new LinkedHashMap<>();
        broken.put("id", "t");
        broken.put("type", "no-such-source-type");
        broken.put("baseUrl", "http://203.0.113.9:1");
        catalog(active("s", sourceS(), "ping"), new Server("t", State.REVOKED, null, Map.of(), "INC-4: retired"));
        try (Gateway g = GatewayFactory.build(config(List.of(sourceS(), broken), null, serverCatalog()), ctx())) {
            assertEquals(Map.of("ping", ToolCatalog.Status.ACTIVE), statuses(g));
        }
        // Control: the same block is rejected when nothing tells the gateway to skip it.
        catalog(active("s", sourceS(), "ping"), active("t", broken, "zap"));
        assertThrows(ConfigException.class, () -> GatewayFactory.build(
                config(List.of(sourceS(), broken), null, serverCatalog()), ctx()));
    }

    @Test
    void aHostPinnedReviewKeyCannotBeDroppedOrSwappedByEditingTheConfiguration() throws Exception {
        catalog(active("s", sourceS(), "ping"), active("t", sourceT(), "zap"));
        List<Map<String, Object>> both = List.of(sourceS(), sourceT());
        String pinned = reviewKey.toAbsolutePath().toString();
        BuildContext host = BuildContext.defaults(dir).withEnv(name ->
                GatewayFactory.REQUIRED_CATALOG_KEY.equals(name) ? pinned : ENV.get(name));

        // The intended configuration starts.
        try (Gateway g = GatewayFactory.build(config(both, null, serverCatalog()), host)) {
            assertEquals(Map.of("ping", ToolCatalog.Status.ACTIVE, "zap", ToolCatalog.Status.ACTIVE), statuses(g));
        }

        // Removing the serverCatalog block no longer turns the protection off.
        ConfigException dropped = assertThrows(ConfigException.class, () -> GatewayFactory.build(
                config(both, null, null), host));
        assertTrue(dropped.getMessage().contains("config.serverCatalog is required"), dropped.getMessage());

        // Pointing the block at a key of one's own, with a catalog signed by it, is refused.
        Path rogue = keyPair("rogue");
        Map<String, Server> m = new TreeMap<>();
        Map<String, Object> moved = sourceS();
        moved.put("baseUrl", "http://127.0.0.9:1");
        m.put("s", active("s", moved, "ping"));
        m.put("t", active("t", sourceT(), "zap"));
        sign("rogue-servers.json", SignedServerCatalog.create(m, "gw-sc"), "rogue");
        ConfigException swapped = assertThrows(ConfigException.class, () -> GatewayFactory.build(
                config(List.of(moved, sourceT()), null,
                        Map.of("file", "rogue-servers.json", "publicKeyFile", rogue.getFileName().toString())), host));
        assertTrue(swapped.getMessage().contains("is not the review key named by " + GatewayFactory.REQUIRED_CATALOG_KEY),
                swapped.getMessage());

        // Control: without the host setting the same rogue configuration would start.
        try (Gateway g = GatewayFactory.build(config(List.of(moved, sourceT()), null,
                Map.of("file", "rogue-servers.json", "publicKeyFile", rogue.getFileName().toString())), ctx())) {
            assertEquals(ToolCatalog.Status.ACTIVE, statuses(g).get("ping"));
        }

        // The setting must be an absolute path, so it cannot be resolved against the config directory.
        BuildContext relative = BuildContext.defaults(dir).withEnv(name ->
                GatewayFactory.REQUIRED_CATALOG_KEY.equals(name) ? "review.pub" : ENV.get(name));
        ConfigException rel = assertThrows(ConfigException.class, () -> GatewayFactory.build(
                config(both, null, serverCatalog()), relative));
        assertTrue(rel.getMessage().contains("must be an absolute path"), rel.getMessage());
    }

    @Test
    void aTamperedCatalogIsRefused() throws Exception {
        catalog(active("s", sourceS(), "ping"), new Server("t", State.REVOKED, null, Map.of(), "INC-1"));
        Files.writeString(dir.resolve("servers.json"), Files.readString(dir.resolve("servers.json")).replace("REVOKED", "ACTIVE"));
        ConfigException e = assertThrows(ConfigException.class, () -> GatewayFactory.build(
                config(List.of(sourceS(), sourceT()), null, serverCatalog()), ctx()));
        assertTrue(e.getMessage().contains("config.serverCatalog") && e.getMessage().contains("does not verify"), e.getMessage());

        ConfigException missing = assertThrows(ConfigException.class, () -> GatewayFactory.build(
                config(List.of(sourceS()), null, Map.of("file", "servers.json")), ctx()));
        assertTrue(missing.getMessage().contains("publicKeyFile"), missing.getMessage());
    }

    @Test
    void aBreakGlassOrderStopsNamedServersWithASeparateKeyInsideItsWindow() throws Exception {
        catalog(active("s", sourceS(), "ping"), active("t", sourceT(), "zap"));
        Instant now = Instant.now();
        sign("order.json", BreakGlassContainment.create("INC-9", now.minus(Duration.ofMinutes(5)),
                now.plus(Duration.ofHours(1)), Map.of("t", State.QUARANTINED), "gw-sc"), "incident");
        List<Map<String, Object>> both = List.of(sourceS(), sourceT());
        try (Gateway g = GatewayFactory.build(config(both, null, withBreakGlass("incident")), ctx())) {
            assertEquals(Map.of("ping", ToolCatalog.Status.ACTIVE), statuses(g), "t is contained and never started");
        }

        // The review key may not double as the incident key.
        sign("order.json", BreakGlassContainment.create("INC-9", now.minus(Duration.ofMinutes(5)),
                now.plus(Duration.ofHours(1)), Map.of("t", State.QUARANTINED), "gw-sc"), "review");
        ConfigException sameKey = assertThrows(ConfigException.class, () -> GatewayFactory.build(
                config(both, null, withBreakGlass("review")), ctx()));
        assertTrue(sameKey.getMessage().contains("must be a different key"), sameKey.getMessage());

        // An expired order refuses startup instead of silently re-activating the server.
        sign("order.json", BreakGlassContainment.create("INC-9", now.minus(Duration.ofHours(3)),
                now.minus(Duration.ofHours(2)), Map.of("t", State.QUARANTINED), "gw-sc"), "incident");
        ConfigException expired = assertThrows(ConfigException.class, () -> GatewayFactory.build(
                config(both, null, withBreakGlass("incident")), ctx()));
        assertTrue(expired.getMessage().contains("expired"), expired.getMessage());

        // A typo in the server id would leave the intended server running, so it is refused.
        sign("order.json", BreakGlassContainment.create("INC-9", now.minus(Duration.ofMinutes(5)),
                now.plus(Duration.ofHours(1)), Map.of("tt", State.REVOKED), "gw-sc"), "incident");
        ConfigException typo = assertThrows(ConfigException.class, () -> GatewayFactory.build(
                config(both, null, withBreakGlass("incident")), ctx()));
        assertTrue(typo.getMessage().contains("names server 'tt'"), typo.getMessage());

        // Containing every configured server leaves nothing to start: refused, not an empty gateway.
        sign("order.json", BreakGlassContainment.create("INC-9", now.minus(Duration.ofMinutes(5)),
                now.plus(Duration.ofHours(1)), Map.of("s", State.REVOKED, "t", State.REVOKED), "gw-sc"), "incident");
        ConfigException none = assertThrows(ConfigException.class, () -> GatewayFactory.build(
                config(both, null, withBreakGlass("incident")), ctx()));
        assertTrue(none.getMessage().contains("no source that the signed server catalog lets start"), none.getMessage());
    }

    @Test
    void anOlderSignedCatalogOrOrderIsRefusedOnceANewerOneWasAccepted() throws Exception {
        // One store shared by successive builds stands for durable state across restarts.
        io.github.vaquarkhan.aegis.engine.state.InMemoryStateStore store =
                new io.github.vaquarkhan.aegis.engine.state.InMemoryStateStore(java.time.Clock.systemUTC(), 1000);
        BuildContext durable = new BuildContext(ENV::get, dir, null, null, null, null, null, store);
        Map<String, Server> servers = new TreeMap<>(Map.of("s", active("s", sourceS(), "ping"), "t", active("t", sourceT(), "zap")));
        List<Map<String, Object>> both = List.of(sourceS(), sourceT());
        java.util.function.Function<Long, byte[]> version = n -> SignedServerCatalog.create(servers, "gw-sc", n);

        // An unnumbered catalog starts as before and records nothing.
        sign("servers.json", version.apply(0L), "review");
        GatewayFactory.build(config(both, null, serverCatalog()), durable).close();

        sign("servers.json", version.apply(2L), "review");
        GatewayFactory.build(config(both, null, serverCatalog()), durable).close();
        GatewayFactory.build(config(both, null, serverCatalog()), durable).close(); // the same version restarts

        sign("servers.json", version.apply(1L), "review");
        ConfigException older = assertThrows(ConfigException.class, () -> GatewayFactory.build(
                config(both, null, serverCatalog()), durable));
        assertTrue(older.getMessage().contains("config.serverCatalog.file has serial 1")
                && older.getMessage().contains("already accepted serial 2"), older.getMessage());
        sign("servers.json", version.apply(0L), "review");
        ConfigException unnumbered = assertThrows(ConfigException.class, () -> GatewayFactory.build(
                config(both, null, serverCatalog()), durable));
        assertTrue(unnumbered.getMessage().contains("has no serial"), unnumbered.getMessage());

        sign("servers.json", version.apply(3L), "review");
        GatewayFactory.build(config(both, null, serverCatalog()), durable).close();

        // Break-glass orders keep their own record, separate from the catalog's.
        Instant now = Instant.now();
        java.util.function.Function<Long, byte[]> order = n -> BreakGlassContainment.create("INC-9",
                now.minus(Duration.ofMinutes(5)), now.plus(Duration.ofHours(1)), Map.of("t", State.QUARANTINED), "gw-sc", n);
        sign("order.json", order.apply(5L), "incident");
        GatewayFactory.build(config(both, null, withBreakGlass("incident")), durable).close();
        sign("order.json", order.apply(4L), "incident");
        ConfigException olderOrder = assertThrows(ConfigException.class, () -> GatewayFactory.build(
                config(both, null, withBreakGlass("incident")), durable));
        assertTrue(olderOrder.getMessage().contains("breakGlass.file has serial 4")
                && olderOrder.getMessage().contains("already accepted serial 5"), olderOrder.getMessage());

        // A signed serial must be a positive whole number.
        Files.writeString(dir.resolve("servers.json"), new String(version.apply(3L), java.nio.charset.StandardCharsets.UTF_8)
                .replaceFirst("\"serial\"\\s*:\\s*3", "\"serial\":0"));
        sign("servers.json", Files.readAllBytes(dir.resolve("servers.json")), "review");
        ConfigException zero = assertThrows(ConfigException.class, () -> GatewayFactory.build(
                config(both, null, serverCatalog()), durable));
        assertTrue(zero.getMessage().contains("\"serial\" must be a whole number"), zero.getMessage());

        // Without durable state the record lasts one process: a fresh in-memory store has none.
        sign("servers.json", version.apply(1L), "review");
        sign("order.json", order.apply(1L), "incident");
        GatewayFactory.build(config(both, null, withBreakGlass("incident")), ctx()).close();
    }
}
