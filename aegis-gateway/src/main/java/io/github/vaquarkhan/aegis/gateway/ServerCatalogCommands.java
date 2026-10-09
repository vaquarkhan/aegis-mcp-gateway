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

import io.github.vaquarkhan.aegis.config.ConfigLoader;
import io.github.vaquarkhan.aegis.config.GatewayFactory;
import io.github.vaquarkhan.aegis.engine.catalog.BreakGlassContainment;
import io.github.vaquarkhan.aegis.engine.catalog.SignedServerCatalog;
import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.json.JsonLimits;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Offline workflow for the signed server catalog and break-glass containment orders. Nothing here talks
 * to a running gateway; the files are reviewed by people and signed with {@code --manifest-sign}:
 * <pre>
 *   --server-catalog-create &lt;aegis.yaml&gt; &lt;catalog.json&gt; &lt;servers.json&gt; [prompts.json]
 *        ACTIVE records for every configured source: its configuration digest and the pins of its
 *        tools from a reviewed GET /aegis/v1/catalog reply, plus "prompt:&lt;name&gt;" pins from a
 *        reviewed GET /aegis/v1/prompts reply when one is given
 *   --server-catalog-verify &lt;servers.json&gt; &lt;public.pem&gt; &lt;gateway&gt;   checks as the gateway does at start
 *   --break-glass-create &lt;order.json&gt; &lt;gateway&gt; &lt;incident&gt; &lt;minutes&gt; &lt;id=QUARANTINED|REVOKED&gt;...
 *        a containment order valid from now for at most 24 hours
 *   --break-glass-verify &lt;order.json&gt; &lt;public.pem&gt; &lt;gateway&gt;      checks as the gateway does at start
 * </pre>
 * Use a separate key pair for break-glass orders; the gateway refuses an order signed with the
 * server-catalog key. Both create commands accept {@code --serial <n>}: once the gateway accepted a
 * document with serial n, it refuses any document of that kind with a lower serial or none.
 */
final class ServerCatalogCommands {

    private ServerCatalogCommands() {}

    static int run(String command, String[] a, PrintStream out, PrintStream err, Clock clock) {
        long serial;
        try {
            switch (command) {
                case "--server-catalog-create" -> {
                    CatalogManifestCommands.SerialArgs sa = CatalogManifestCommands.serialOption(a);
                    if (sa == null) {
                        return usage(err, CatalogManifestCommands.SERIAL_USAGE);
                    }
                    a = sa.args();
                    serial = sa.serial();
                    if (a.length != 3 && a.length != 4) {
                        return usage(err, "--server-catalog-create <aegis.yaml|aegis.json> <catalog.json> <servers.json>"
                                + " [prompts.json] [--serial <n>]");
                    }
                    Map<String, Object> config = ConfigLoader.load(Path.of(a[0]));
                    String gateway = config.get("gateway") instanceof Map<?, ?> g && g.get("name") instanceof String n ? n : "aegis";
                    Map<String, Map<String, String>> pinsBySource = pinsBySource(Path.of(a[1]), "tools", "");
                    if (a.length == 4) {
                        // Prompt pins use the "prompt:<name>" key the gateway verifies prompts under.
                        for (Map.Entry<String, Map<String, String>> e
                                : pinsBySource(Path.of(a[3]), "prompts", "prompt:").entrySet()) {
                            pinsBySource.computeIfAbsent(e.getKey(), k -> new TreeMap<>()).putAll(e.getValue());
                        }
                    }
                    Map<String, SignedServerCatalog.Server> servers = new TreeMap<>();
                    if (!(config.get("sources") instanceof List<?> sources) || sources.isEmpty()) {
                        throw new IOException(a[0] + " has no sources");
                    }
                    for (Object o : sources) {
                        if (!(o instanceof Map<?, ?> m) || !(m.get("id") instanceof String id)) {
                            throw new IOException(a[0] + " has a source without an id");
                        }
                        Map<String, String> pins = pinsBySource.getOrDefault(id, Map.of());
                        if (pins.isEmpty()) {
                            throw new IOException("source '" + id + "' has no tools in " + a[1]
                                    + "; an ACTIVE record needs its exact pins (remove the source, or mark it"
                                    + " QUARANTINED or REVOKED by hand with evidence)");
                        }
                        @SuppressWarnings("unchecked")
                        Map<String, Object> block = (Map<String, Object>) m;
                        servers.put(id, new SignedServerCatalog.Server(id, SignedServerCatalog.State.ACTIVE,
                                GatewayFactory.sourceDigest(block), pins, null));
                    }
                    Files.write(Path.of(a[2]), SignedServerCatalog.create(servers, gateway, serial), StandardOpenOption.CREATE_NEW);
                    out.println("wrote " + a[2] + " with " + servers.size() + " servers for gateway '" + gateway
                            + "'; review it, then --manifest-sign it");
                    return Main.OK;
                }
                case "--server-catalog-verify" -> {
                    if (a.length != 3) {
                        return usage(err, "--server-catalog-verify <servers.json> <public.pem> <gateway-name>");
                    }
                    Path file = Path.of(a[0]);
                    SignedServerCatalog.Loaded loaded = SignedServerCatalog.loadWithSerial(file,
                            file.resolveSibling(file.getFileName() + ".sig"), Path.of(a[1]), a[2]);
                    Map<String, SignedServerCatalog.Server> servers = loaded.servers();
                    long active = servers.values().stream().filter(SignedServerCatalog.Server::active).count();
                    out.println("OK servers=" + servers.size() + " active=" + active
                            + (loaded.serial() > 0 ? " serial=" + loaded.serial() : ""));
                    return Main.OK;
                }
                case "--break-glass-create" -> {
                    CatalogManifestCommands.SerialArgs sa = CatalogManifestCommands.serialOption(a);
                    if (sa == null) {
                        return usage(err, CatalogManifestCommands.SERIAL_USAGE);
                    }
                    a = sa.args();
                    serial = sa.serial();
                    if (a.length < 5) {
                        return usage(err, "--break-glass-create <order.json> <gateway-name> <incident> <minutes>"
                                + " <id=QUARANTINED|REVOKED>... [--serial <n>]");
                    }
                    long minutes;
                    try {
                        minutes = Long.parseLong(a[3]);
                    } catch (NumberFormatException e) {
                        return usage(err, "<minutes> must be a number between 1 and "
                                + BreakGlassContainment.MAX_WINDOW.toMinutes());
                    }
                    if (minutes < 1 || minutes > BreakGlassContainment.MAX_WINDOW.toMinutes()) {
                        return usage(err, "<minutes> must be between 1 and " + BreakGlassContainment.MAX_WINDOW.toMinutes());
                    }
                    Map<String, SignedServerCatalog.State> servers = new TreeMap<>();
                    for (int i = 4; i < a.length; i++) {
                        int eq = a[i].indexOf('=');
                        SignedServerCatalog.State st = eq < 0 ? null : SignedServerCatalog.parseState(a[i].substring(eq + 1));
                        if (st == null || st == SignedServerCatalog.State.ACTIVE) {
                            return usage(err, "'" + a[i] + "' must be <id>=QUARANTINED or <id>=REVOKED");
                        }
                        servers.put(a[i].substring(0, eq), st);
                    }
                    Instant now = clock.instant();
                    Files.write(Path.of(a[0]), BreakGlassContainment.create(a[2], now, now.plus(Duration.ofMinutes(minutes)),
                            servers, a[1], serial), StandardOpenOption.CREATE_NEW);
                    out.println("wrote " + a[0] + " containing " + servers.keySet() + " until "
                            + now.plus(Duration.ofMinutes(minutes)) + "; sign it with the break-glass key (--manifest-sign)");
                    return Main.OK;
                }
                default -> {
                    if (a.length != 3) {
                        return usage(err, "--break-glass-verify <order.json> <public.pem> <gateway-name>");
                    }
                    Path file = Path.of(a[0]);
                    BreakGlassContainment.Order o = BreakGlassContainment.load(file,
                            file.resolveSibling(file.getFileName() + ".sig"), Path.of(a[1]), a[2], clock.instant());
                    out.println("OK incident=" + o.incident() + " servers=" + o.servers() + " notAfter=" + o.notAfter()
                            + (o.serial() > 0 ? " serial=" + o.serial() : ""));
                    return Main.OK;
                }
            }
        } catch (FileAlreadyExistsException e) {
            err.println("refusing to overwrite " + e.getFile());
            return Main.USAGE;
        } catch (IOException | RuntimeException e) {
            err.println("BROKEN " + e.getMessage());
            return Main.BROKEN;
        }
    }

    /**
     * Groups {@code {"tools":[{"name","source","digest"}]}} from {@code GET /aegis/v1/catalog}, or
     * {@code {"prompts":[...]}} from {@code GET /aegis/v1/prompts}, by source. Every entry is pinned
     * whatever its current status (under {@code REQUIRE_PINS} everything starts quarantined), so the
     * reply must be reviewed before the result is signed.
     */
    static Map<String, Map<String, String>> pinsBySource(Path file, String key, String prefix) throws IOException {
        Map<String, Object> reply = Json.parseObject(Files.readAllBytes(file), JsonLimits.CONFIG);
        String route = "tools".equals(key) ? "GET /aegis/v1/catalog" : "GET /aegis/v1/prompts";
        if (!(reply.get(key) instanceof List<?> entries) || entries.isEmpty()) {
            throw new IOException(file + " is not a " + route + " reply with " + key);
        }
        Map<String, Map<String, String>> out = new TreeMap<>();
        for (Object t : entries) {
            if (!(t instanceof Map<?, ?> m) || !(m.get("name") instanceof String name)
                    || !(m.get("source") instanceof String source) || !(m.get("digest") instanceof String d)) {
                throw new IOException(file + " has an entry in " + key + " without a name, source and digest");
            }
            out.computeIfAbsent(source, k -> new TreeMap<>()).put(prefix + name, d);
        }
        return out;
    }

    private static int usage(PrintStream err, String text) {
        err.println("usage: " + text);
        return Main.USAGE;
    }
}
