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
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * An offline, Ed25519-signed, time-boxed emergency containment order: during an incident, a responder
 * holding the break-glass key can stop named servers from starting without re-signing the reviewed
 * {@link SignedServerCatalog}. It is read once at startup.
 *
 * <p>Break-glass here can only take capability away. It cannot activate a server, add a pin, skip an
 * approval or grant a scope, so a stolen break-glass key can at worst stop servers. The order is valid
 * only inside its window ({@code notBefore} to {@code notAfter}, at most {@link #MAX_WINDOW}); outside it
 * the gateway refuses to start rather than silently dropping the containment, so the operator has to
 * remove or renew the order explicitly. A window that ends while the gateway runs keeps the containment
 * until the next restart (it only ever stays tighter).
 *
 * <p>Format:
 * <pre>{@code
 * {"version": 1, "serial": <optional n>, "kind": "aegis-break-glass", "gateway": "<optional gateway.name>",
 *  "incident": "<ticket or incident id>", "notBefore": "<ISO-8601 instant>", "notAfter": "<ISO-8601 instant>",
 *  "servers": {"<source id>": "QUARANTINED" | "REVOKED"}}
 * }</pre>
 */
public final class BreakGlassContainment {

    public static final String KIND = "aegis-break-glass";
    public static final Duration MAX_WINDOW = Duration.ofHours(24);

    /**
     * A verified containment order.
     *
     * @param incident  the incident or ticket the order is issued under (journaled with every use)
     * @param notBefore first instant the order is valid
     * @param notAfter  instant from which the order is no longer valid
     * @param servers   source id to the non-ACTIVE state it is forced into
     * @param serial    the signed {@code "serial"} for rollback protection ({@link SignedSerial}); 0 when absent
     */
    public record Order(String incident, Instant notBefore, Instant notAfter, Map<String, SignedServerCatalog.State> servers,
                        long serial) {
        public Order {
            servers = Map.copyOf(new TreeMap<>(servers));
        }
    }

    private BreakGlassContainment() {}

    /**
     * Verifies the signature first, then parses, validates and checks the window against {@code now}.
     */
    public static Order load(Path order, Path signature, Path publicKey, String gatewayName, Instant now)
            throws IOException {
        String what = "break-glass order";
        Map<String, Object> m = SignedCatalogManifest.verifiedObject(what, order, signature, publicKey);
        String at = what + " " + order;
        for (String k : m.keySet()) {
            if (!List.of("version", "serial", "kind", "gateway", "incident", "notBefore", "notAfter", "servers").contains(k)) {
                throw new IOException(at + " has an unknown key '" + k + "'");
            }
        }
        if (!Long.valueOf(1).equals(m.get("version"))) {
            throw new IOException(at + " must have \"version\": 1");
        }
        if (!KIND.equals(m.get("kind"))) {
            throw new IOException(at + " must have \"kind\": \"" + KIND + "\"");
        }
        Object gw = m.get("gateway");
        if (gw != null && !(gw instanceof String g && g.equals(gatewayName))) {
            throw new IOException(at + " is for gateway '" + gw + "', not '" + gatewayName + "'");
        }
        String incident = SignedServerCatalog.text(at, "incident", m.get("incident"));
        if (incident == null) {
            throw new IOException(at + " needs an \"incident\" naming the incident or ticket");
        }
        Instant notBefore = instant(at, "notBefore", m.get("notBefore"));
        Instant notAfter = instant(at, "notAfter", m.get("notAfter"));
        if (!notAfter.isAfter(notBefore)) {
            throw new IOException(at + ": notAfter must be after notBefore");
        }
        if (Duration.between(notBefore, notAfter).compareTo(MAX_WINDOW) > 0) {
            throw new IOException(at + ": the window must not exceed " + MAX_WINDOW.toHours() + " hours");
        }
        if (now.isBefore(notBefore)) {
            throw new IOException(at + " is not valid before " + notBefore + "; remove it from the configuration or wait");
        }
        if (!now.isBefore(notAfter)) {
            throw new IOException(at + " expired at " + notAfter
                    + "; remove it from the configuration, or issue a new order if containment must continue");
        }
        if (!(m.get("servers") instanceof Map<?, ?> servers) || servers.isEmpty()) {
            throw new IOException(at + " needs a non-empty \"servers\" object");
        }
        if (servers.size() > SignedServerCatalog.MAX_SERVERS) {
            throw new IOException(at + " names more than " + SignedServerCatalog.MAX_SERVERS + " servers");
        }
        Map<String, SignedServerCatalog.State> out = new TreeMap<>();
        for (Map.Entry<?, ?> e : servers.entrySet()) {
            String id = (String) e.getKey();
            if (!SignedServerCatalog.SERVER_ID.matcher(id).matches()) {
                throw new IOException(at + ": server id '" + id + "' must match " + SignedServerCatalog.SERVER_ID.pattern());
            }
            SignedServerCatalog.State st = null;
            if (e.getValue() instanceof String v) {
                try {
                    st = SignedServerCatalog.State.valueOf(v);
                } catch (IllegalArgumentException bad) {
                    st = null;
                }
            }
            if (st == null || st == SignedServerCatalog.State.ACTIVE) {
                throw new IOException(at + ": server '" + id + "' must be QUARANTINED or REVOKED;"
                        + " break-glass can only contain, never activate");
            }
            out.put(id, st);
        }
        return new Order(incident, notBefore, notAfter, out, SignedSerial.parse(at, m.get("serial")));
    }

    private static Instant instant(String at, String key, Object v) throws IOException {
        if (!(v instanceof String s)) {
            throw new IOException(at + " needs \"" + key + "\" as an ISO-8601 instant, e.g. 2026-10-05T12:00:00Z");
        }
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            throw new IOException(at + ": \"" + key + "\" is not an ISO-8601 instant");
        }
    }

    /** Builds order bytes (sorted, stable), ready to review and sign offline with the break-glass key. */
    public static byte[] create(String incident, Instant notBefore, Instant notAfter,
                                Map<String, SignedServerCatalog.State> servers, String gatewayName) {
        return create(incident, notBefore, notAfter, servers, gatewayName, 0);
    }

    /** As {@link #create(String, Instant, Instant, Map, String)} with a signed {@code serial} (0 omits it). */
    public static byte[] create(String incident, Instant notBefore, Instant notAfter,
                                Map<String, SignedServerCatalog.State> servers, String gatewayName, long serial) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", 1L);
        if (serial > 0) {
            m.put("serial", serial);
        }
        m.put("kind", KIND);
        if (gatewayName != null && !gatewayName.isBlank()) {
            m.put("gateway", gatewayName);
        }
        m.put("incident", incident);
        m.put("notBefore", notBefore.toString());
        m.put("notAfter", notAfter.toString());
        Map<String, Object> s = new TreeMap<>();
        servers.forEach((id, st) -> s.put(id, st.name()));
        m.put("servers", s);
        return (Json.write(m) + "\n").getBytes(StandardCharsets.UTF_8);
    }
}
