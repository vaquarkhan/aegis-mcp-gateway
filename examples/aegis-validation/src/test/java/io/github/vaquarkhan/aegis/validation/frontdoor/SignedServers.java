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
package io.github.vaquarkhan.aegis.validation.frontdoor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.vaquarkhan.aegis.config.BuildContext;
import io.github.vaquarkhan.aegis.config.ConfigLoader;
import io.github.vaquarkhan.aegis.config.Gateway;
import io.github.vaquarkhan.aegis.config.GatewayFactory;
import io.github.vaquarkhan.aegis.engine.catalog.BreakGlassContainment;
import io.github.vaquarkhan.aegis.engine.catalog.SignedCatalogManifest;
import io.github.vaquarkhan.aegis.engine.catalog.SignedServerCatalog;
import io.github.vaquarkhan.aegis.engine.catalog.ToolCatalog;
import io.github.vaquarkhan.aegis.engine.json.Json;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The offline signed server catalog, applied to a real adopter scenario: review the definitions the
 * scenario's sources expose, sign a catalog of exactly those sources and tools with a review key, and,
 * when asked, a break-glass order with a separate incident key. The signed files and public keys go next
 * to the configuration of whichever front door opens the result; the private keys never do.
 *
 * <p>The definitions are read with the same {@link GatewayFactory} both front doors use (in this JVM,
 * with {@code catalog.mode: REQUIRE_PINS} so nothing is trusted on first sight), from the scenario's own
 * substituted configuration, so each source digest covers the exact block the doors will load.
 */
public final class SignedServers {

    /**
     * A signed scenario.
     *
     * @param scenario the scenario to open, with {@code serverCatalog} appended and its files attached
     * @param pins     every definition the catalog activates, exposed name to digest
     * @param sources  exposed tool name to the source id that serves it
     */
    public record Signed(AdopterScenario scenario, Map<String, String> pins, Map<String, String> sources) {

        /** The pinned tool names a given source serves. */
        public List<String> toolsOf(String sourceId) {
            return sources.entrySet().stream().filter(e -> e.getValue().equals(sourceId)).map(Map.Entry::getKey).sorted().toList();
        }
    }

    private SignedServers() {}

    /**
     * Signs every source of {@code base} ACTIVE with its reviewed definitions, and (when {@code contain} is
     * not empty) adds a break-glass order, valid from a minute ago for one hour, forcing those ids out.
     *
     * @param folder  result folder for the signed scenario (kept apart from the adopter's own record)
     * @param workDir a scratch directory for the review build and the private keys
     */
    public static Signed sign(AdopterScenario base, String folder, Path workDir,
                              Map<String, SignedServerCatalog.State> contain) throws Exception {
        Path review = Files.createDirectories(workDir.resolve("server-catalog-review-" + folder));
        Map<String, Object> config = ConfigLoader.parseYaml(base.configYaml(), base.adopter() + ".yaml");
        if (config.containsKey("catalog") || config.containsKey("serverCatalog")) {
            throw new IllegalStateException(base.adopter() + ": the profile already has a catalog section; extend this fixture");
        }
        String gateway = config.get("gateway") instanceof Map<?, ?> g && g.get("name") instanceof String n ? n : "aegis";

        // 1. Review build: the exact definitions each source exposes, none of them trusted yet.
        Map<String, Object> reviewConfig = new LinkedHashMap<>(config);
        reviewConfig.put("catalog", Map.of("mode", "REQUIRE_PINS"));
        Map<String, String> pins = new TreeMap<>();
        Map<String, String> sourceOf = new TreeMap<>();
        Map<String, Map<String, String>> pinsBySource = new TreeMap<>();
        try (Gateway g = GatewayFactory.build(reviewConfig, BuildContext.defaults(review).withEnv(base.env()::get))) {
            for (ToolCatalog.Entry e : g.engine().catalog().entries()) {
                assertEquals(ToolCatalog.Status.QUARANTINED, e.status(), "nothing is trusted before review: " + e.tool().exposedName());
                pins.put(e.tool().exposedName(), e.digest());
                sourceOf.put(e.tool().exposedName(), e.tool().sourceId());
                pinsBySource.computeIfAbsent(e.tool().sourceId(), k -> new TreeMap<>()).put(e.tool().exposedName(), e.digest());
            }
        }

        // 2. One ACTIVE record per configured source: its configuration digest and its exact definitions.
        Map<String, SignedServerCatalog.Server> servers = new TreeMap<>();
        for (Object o : (List<?>) config.get("sources")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> block = (Map<String, Object>) o;
            String id = (String) block.get("id");
            Map<String, String> mine = pinsBySource.getOrDefault(id, Map.of());
            assertFalse(mine.isEmpty(), "source " + id + " exposes no definition to pin");
            servers.put(id, new SignedServerCatalog.Server(id, SignedServerCatalog.State.ACTIVE,
                    GatewayFactory.sourceDigest(block), mine, null));
        }
        String[] reviewKeys = SignedCatalogManifest.generateKeyPair();
        Path reviewKey = Files.writeString(review.resolve("review.key"), reviewKeys[0]);
        byte[] catalog = SignedServerCatalog.create(servers, gateway);
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("servers.json", catalog);
        files.put("servers.json.sig", (SignedCatalogManifest.sign(catalog, SignedCatalogManifest.readPrivateKey(reviewKey)) + "\n")
                .getBytes(StandardCharsets.US_ASCII));
        files.put("review.pub", reviewKeys[1].getBytes(StandardCharsets.US_ASCII));

        StringBuilder yaml = new StringBuilder("""

                # Appended for the signed server-catalog scenario: only the reviewed sources and tools start.
                serverCatalog:
                  file: servers.json
                  publicKeyFile: review.pub
                """);
        Map<String, String> activePins = new TreeMap<>(pins);
        Map<String, String> activeSources = new TreeMap<>(sourceOf);
        if (!contain.isEmpty()) {
            // 3. Break-glass: a separate incident key, a short window, containment only.
            String[] incidentKeys = SignedCatalogManifest.generateKeyPair();
            Path incidentKey = Files.writeString(review.resolve("incident.key"), incidentKeys[0]);
            Instant now = Instant.now();
            byte[] order = BreakGlassContainment.create("INC-" + folder, now.minus(Duration.ofMinutes(1)),
                    now.plus(Duration.ofHours(1)), contain, gateway);
            files.put("order.json", order);
            files.put("order.json.sig", (SignedCatalogManifest.sign(order, SignedCatalogManifest.readPrivateKey(incidentKey)) + "\n")
                    .getBytes(StandardCharsets.US_ASCII));
            files.put("incident.pub", incidentKeys[1].getBytes(StandardCharsets.US_ASCII));
            yaml.append("""
                      breakGlass:
                        file: order.json
                        publicKeyFile: incident.pub
                    """);
            activeSources.entrySet().removeIf(e -> contain.containsKey(e.getValue()));
            activePins.keySet().retainAll(activeSources.keySet());
        }
        return new Signed(base.withFiles(folder, yaml.toString(), files), activePins, activeSources);
    }

    /**
     * {@code GET catalog} through the door as an approver: exactly the signed definitions are listed, every
     * one ACTIVE, with the digest the catalog pinned and from the source it was signed for.
     */
    @SuppressWarnings("unchecked")
    public static void assertOnlySignedToolsAreActive(FrontDoor door, Signed signed) throws Exception {
        McpReply r = door.catalog("catalog under the signed server catalog", Persona.APPROVER);
        assertEquals(200, r.status(), r.body());
        Map<String, String> digests = new TreeMap<>();
        Map<String, String> sources = new TreeMap<>();
        for (Object t : (List<Object>) Json.parseObject(r.body()).get("tools")) {
            Map<String, Object> m = (Map<String, Object>) t;
            String name = (String) m.get("name");
            assertEquals("ACTIVE", m.get("status"), name + " is listed but not active: " + m);
            digests.put(name, (String) m.get("digest"));
            sources.put(name, (String) m.get("source"));
        }
        assertEquals(signed.pins(), digests, "the door lists exactly the signed definitions, with the signed digests");
        assertEquals(signed.sources(), sources, "each tool comes from the source it was signed for");
    }

    /** Every message in a failure's cause chain, so a refusal can be asserted however a door wraps it. */
    public static String messages(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            sb.append(c.getClass().getSimpleName()).append(": ").append(c.getMessage()).append('\n');
        }
        return sb.toString();
    }

    /** A tool of a contained or unsigned source is not exposed through the door, to anyone. */
    public static void assertNotExposed(FrontDoor door, String tool, Map<String, Object> args) throws Exception {
        for (Persona p : List.of(Persona.READER, Persona.OPERATOR)) {
            McpReply r = door.call("call a contained server's tool as " + p, p, tool, args);
            assertFalse(r.allowed(), r.body());
            assertEquals("NOT_EXPOSED", r.code(), r.body());
        }
    }
}
