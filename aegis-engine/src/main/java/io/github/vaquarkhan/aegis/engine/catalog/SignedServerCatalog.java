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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * An offline, Ed25519-signed register of the MCP servers (configured {@code sources[]}) a gateway may
 * run, read once at startup. It is the reviewed answer to "which servers, at which configuration, with
 * which exact tool definitions": a host that can write the gateway's configuration but does not hold the
 * catalog signing key cannot add a server, change where a server points, or add or change a tool.
 *
 * <p>It is deliberately not a runtime registry. Records only refer to sources already declared in the
 * gateway configuration by id; nothing here registers a URL, a command or a credential, and there is no
 * API to change it while the gateway runs. With a signed {@code serial}, an older catalog is refused
 * once a newer one was accepted ({@link SignedSerial}).
 *
 * <p>Format:
 * <pre>{@code
 * {"version": 1, "serial": <optional n>, "kind": "aegis-server-catalog", "gateway": "<optional gateway.name>",
 *  "servers": {
 *    "<source id>": {"state": "ACTIVE", "sourceDigest": "sha256:<hex>",
 *                    "pins": {"<exposed tool name or prompt:<name>>": "sha256:<hex>"}},
 *    "<source id>": {"state": "QUARANTINED" | "REVOKED", "evidence": "<why>"}}}
 * }</pre>
 * {@code sourceDigest} is the canonical SHA-256 of the source's configuration block; {@code pins} is the
 * exact allow list of that server's definitions.
 */
public final class SignedServerCatalog {

    /** Distinguishes this document from other files signed by the same key (domain separation). */
    public static final String KIND = "aegis-server-catalog";

    static final int MAX_SERVERS = 1024;
    static final int MAX_PINS_PER_SERVER = 4096;
    static final int MAX_TEXT = 1024;

    private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
    /** Same shape as a configured source id. */
    static final Pattern SERVER_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

    /** Review state of one server. Only ACTIVE servers are started. */
    public enum State { ACTIVE, QUARANTINED, REVOKED }

    /**
     * One reviewed server.
     *
     * @param id           the configured source id the record refers to
     * @param state        ACTIVE servers run; QUARANTINED and REVOKED servers are never started
     * @param sourceDigest canonical digest of the reviewed source configuration (ACTIVE only)
     * @param pins         exact definition pins, exposed name to digest (ACTIVE only)
     * @param evidence     why a server is not ACTIVE (required for QUARANTINED and REVOKED)
     */
    public record Server(String id, State state, String sourceDigest, Map<String, String> pins, String evidence) {
        public Server {
            pins = pins == null ? Map.of() : Map.copyOf(new TreeMap<>(pins));
        }

        public boolean active() {
            return state == State.ACTIVE;
        }
    }

    private SignedServerCatalog() {}

    /**
     * Verifies the signature first, then parses and validates. Nothing in an unverified catalog is read.
     *
     * @return the records by server id, sorted
     */
    public static Map<String, Server> load(Path catalog, Path signature, Path publicKey, String gatewayName)
            throws IOException {
        return loadWithSerial(catalog, signature, publicKey, gatewayName).servers();
    }

    /**
     * A verified catalog.
     *
     * @param servers the records by server id, sorted
     * @param serial  the signed {@code "serial"} for rollback protection ({@link SignedSerial}); 0 when absent
     */
    public record Loaded(Map<String, Server> servers, long serial) {}

    /** As {@link #loadWithSerial(Path, Path, PublicKey, String)}, reading the verification key from a PEM file. */
    public static Loaded loadWithSerial(Path catalog, Path signature, Path publicKey, String gatewayName)
            throws IOException {
        return loadWithSerial(catalog, signature, SignedCatalogManifest.readPublicKey(publicKey), gatewayName);
    }

    /** Verifies the catalog against immutable key material already read by the caller. */
    public static Loaded loadWithSerial(Path catalog, Path signature, java.security.PublicKey publicKey, String gatewayName)
            throws IOException {
        String what = "server catalog";
        Map<String, Object> m = SignedCatalogManifest.verifiedObject(what, catalog, signature, publicKey,
                "the configured review key");
        for (String k : m.keySet()) {
            if (!List.of("version", "serial", "kind", "gateway", "servers").contains(k)) {
                throw new IOException(what + " " + catalog + " has an unknown key '" + k + "'");
            }
        }
        if (!Long.valueOf(1).equals(m.get("version"))) {
            throw new IOException(what + " " + catalog + " must have \"version\": 1");
        }
        if (!KIND.equals(m.get("kind"))) {
            throw new IOException(what + " " + catalog + " must have \"kind\": \"" + KIND + "\"");
        }
        Object gw = m.get("gateway");
        if (gw != null && !(gw instanceof String g && g.equals(gatewayName))) {
            throw new IOException(what + " " + catalog + " is for gateway '" + gw + "', not '" + gatewayName + "'");
        }
        if (!(m.get("servers") instanceof Map<?, ?> servers) || servers.isEmpty()) {
            throw new IOException(what + " " + catalog + " needs a non-empty \"servers\" object");
        }
        if (servers.size() > MAX_SERVERS) {
            throw new IOException(what + " " + catalog + " lists more than " + MAX_SERVERS + " servers");
        }
        Map<String, Server> out = new TreeMap<>();
        Map<String, String> pinOwner = new TreeMap<>();
        for (Map.Entry<?, ?> e : servers.entrySet()) {
            String id = (String) e.getKey();
            String at = what + " " + catalog + " server '" + id + "'";
            if (!SERVER_ID.matcher(id).matches()) {
                throw new IOException(at + ": the id must match " + SERVER_ID.pattern());
            }
            if (!(e.getValue() instanceof Map<?, ?> rec)) {
                throw new IOException(at + " must be an object");
            }
            Server s = server(at, id, rec);
            for (String pinned : s.pins().keySet()) {
                // One definition name belongs to exactly one server, so a pin cannot vouch for a
                // tool another server happens to expose under the same name.
                String owner = pinOwner.putIfAbsent(pinned, id);
                if (owner != null) {
                    throw new IOException(at + ": '" + pinned + "' is already pinned by server '" + owner + "'");
                }
            }
            out.put(id, s);
        }
        return new Loaded(out, SignedSerial.parse(what + " " + catalog, m.get("serial")));
    }

    private static Server server(String at, String id, Map<?, ?> rec) throws IOException {
        for (Object k : rec.keySet()) {
            if (!List.of("state", "sourceDigest", "pins", "evidence").contains(k)) {
                throw new IOException(at + " has an unknown key '" + k + "'");
            }
        }
        State state;
        try {
            state = rec.get("state") instanceof String st ? State.valueOf(st) : null;
        } catch (IllegalArgumentException e) {
            state = null;
        }
        if (state == null) {
            throw new IOException(at + ": \"state\" must be ACTIVE, QUARANTINED or REVOKED");
        }
        String evidence = text(at, "evidence", rec.get("evidence"));
        if (state == State.ACTIVE) {
            if (!(rec.get("sourceDigest") instanceof String d) || !DIGEST.matcher(d).matches()) {
                throw new IOException(at + ": an ACTIVE server needs \"sourceDigest\": \"sha256:<hex>\"");
            }
            if (!(rec.get("pins") instanceof Map<?, ?> pins) || pins.isEmpty()) {
                throw new IOException(at + ": an ACTIVE server needs a non-empty \"pins\" object (its exact definitions)");
            }
            if (pins.size() > MAX_PINS_PER_SERVER) {
                throw new IOException(at + " pins more than " + MAX_PINS_PER_SERVER + " definitions");
            }
            Map<String, String> out = new TreeMap<>();
            for (Map.Entry<?, ?> p : pins.entrySet()) {
                String name = (String) p.getKey();
                if (name.isBlank() || name.length() > MAX_TEXT || hasControl(name)) {
                    throw new IOException(at + " has an invalid pin name");
                }
                if (!(p.getValue() instanceof String v) || !DIGEST.matcher(v).matches()) {
                    throw new IOException(at + " pin for '" + name + "' is not a sha256 digest");
                }
                out.put(name, v);
            }
            return new Server(id, state, d, out, evidence);
        }
        if (rec.containsKey("sourceDigest") || rec.containsKey("pins")) {
            // A record that is not ACTIVE must not look as if it vouched for anything.
            throw new IOException(at + ": a " + state + " server must not carry sourceDigest or pins");
        }
        if (evidence == null) {
            throw new IOException(at + ": a " + state + " server needs \"evidence\" saying why");
        }
        return new Server(id, state, null, Map.of(), evidence);
    }

    static String text(String at, String key, Object v) throws IOException {
        if (v == null) {
            return null;
        }
        if (!(v instanceof String s) || s.isBlank() || s.length() > MAX_TEXT || hasControl(s)) {
            throw new IOException(at + ": \"" + key + "\" must be non-blank text of at most " + MAX_TEXT
                    + " characters without control characters");
        }
        return s;
    }

    static boolean hasControl(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isISOControl(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** Builds catalog bytes (sorted, stable), ready to review and sign offline. */
    public static byte[] create(Map<String, Server> servers, String gatewayName) {
        return create(servers, gatewayName, 0);
    }

    /** As {@link #create(Map, String)} with a signed {@code serial} (0 omits it). */
    public static byte[] create(Map<String, Server> servers, String gatewayName, long serial) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", 1L);
        if (serial > 0) {
            m.put("serial", serial);
        }
        m.put("kind", KIND);
        if (gatewayName != null && !gatewayName.isBlank()) {
            m.put("gateway", gatewayName);
        }
        Map<String, Object> list = new TreeMap<>();
        for (Server s : servers.values()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("state", s.state().name());
            if (s.active()) {
                r.put("sourceDigest", s.sourceDigest());
                r.put("pins", new TreeMap<>(s.pins()));
            }
            if (s.evidence() != null) {
                r.put("evidence", s.evidence());
            }
            list.put(s.id(), r);
        }
        m.put("servers", list);
        return (Json.write(m) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /** @return {@code ACTIVE}, {@code QUARANTINED} or {@code REVOKED} parsed case-insensitively, or null */
    public static State parseState(String s) {
        try {
            return s == null ? null : State.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
