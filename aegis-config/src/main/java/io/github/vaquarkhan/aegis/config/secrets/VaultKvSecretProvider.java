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
package io.github.vaquarkhan.aegis.config.secrets;

import io.github.vaquarkhan.aegis.config.ConfigException;
import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.json.JsonException;
import io.github.vaquarkhan.aegis.engine.json.JsonLimits;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * {@code secrets.source: vault}: reads one secret from the KV version 2 HTTP API that HashiCorp Vault
 * and OpenBao both serve ({@code GET /v1/<mount>/data/<path>}) once, at start-up, and answers names
 * from its keys. The address passes {@code config.egress} first; redirects are not followed, so the
 * token is only ever sent to the checked address. Rotation takes effect on the next start.
 */
public final class VaultKvSecretProvider implements Function<String, String> {

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*");
    private static final int MAX_RESPONSE_BYTES = 1 << 20;

    private final Map<String, String> values;

    /**
     * @param address   for example {@code https://vault.example.com:8200}
     * @param mount     the KV v2 mount, for example {@code secret}
     * @param path      the secret's path below the mount, for example {@code aegis/prod}
     * @param token     the Vault/OpenBao token (sent as {@code X-Vault-Token}, never logged)
     * @param namespace optional {@code X-Vault-Namespace}
     * @param caFile    optional PEM bundle that replaces the JDK trust store for this connection
     */
    public VaultKvSecretProvider(URI address, String mount, String path, String token, String namespace, Path caFile,
                                 Duration timeout) throws IOException, InterruptedException {
        if (!SEGMENT.matcher(mount).matches() || mount.contains("..")) {
            throw new ConfigException("config.secrets.settings.mount must be a plain path such as 'secret'");
        }
        if (!SEGMENT.matcher(path).matches() || path.contains("..")) {
            throw new ConfigException("config.secrets.settings.path must be a plain path such as 'aegis/prod'");
        }
        if (token == null || token.isBlank()) {
            throw new ConfigException("config.secrets.settings: the Vault token is empty");
        }
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER);
        if (caFile != null) {
            b.sslContext(trusting(caFile));
        }
        String base = address.toString().replaceAll("/+$", "");
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base + "/v1/" + mount + "/data/" + path))
                .timeout(timeout).header("X-Vault-Token", token).header("Accept", "application/json").GET();
        if (namespace != null && !namespace.isBlank()) {
            req.header("X-Vault-Namespace", namespace);
        }
        // One request at start-up; HttpClient is not closed explicitly because it is AutoCloseable only from JDK 21.
        HttpResponse<InputStream> r = b.build().send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
        byte[] body;
        try (InputStream in = r.body()) {
            body = in.readNBytes(MAX_RESPONSE_BYTES + 1);
        }
        String where = mount + "/data/" + path;
        if (r.statusCode() == 403) {
            throw new ConfigException("config.secrets: the Vault token may not read " + where + " (HTTP 403)");
        }
        if (r.statusCode() == 404) {
            throw new ConfigException("config.secrets: no secret at " + where + " (HTTP 404)");
        }
        if (r.statusCode() != 200) {
            // The body is not echoed: an error page could include request details.
            throw new ConfigException("config.secrets: Vault answered HTTP " + r.statusCode() + " for " + where);
        }
        if (body.length > MAX_RESPONSE_BYTES) {
            throw new ConfigException("config.secrets: the Vault response is larger than " + MAX_RESPONSE_BYTES + " bytes");
        }
        this.values = parse(body);
    }

    static Map<String, String> parse(byte[] body) {
        Map<String, Object> m;
        try {
            m = Json.parseObject(body, JsonLimits.DEFAULT);
        } catch (JsonException e) {
            throw new ConfigException("config.secrets: the Vault response is not JSON");
        }
        if (!(m.get("data") instanceof Map<?, ?> outer) || !(outer.get("data") instanceof Map<?, ?> data)) {
            throw new ConfigException("config.secrets: the Vault response has no data.data object (is the mount KV version 2?)");
        }
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<?, ?> e : data.entrySet()) {
            if (!(e.getValue() instanceof String s)) {
                throw new ConfigException("config.secrets: key '" + e.getKey() + "' is not a string value");
            }
            out.put((String) e.getKey(), s);
        }
        return Map.copyOf(out);
    }

    private static SSLContext trusting(Path caFile) throws IOException {
        try (InputStream in = Files.newInputStream(caFile)) {
            Collection<? extends Certificate> certs = CertificateFactory.getInstance("X.509").generateCertificates(in);
            if (certs.isEmpty()) {
                throw new ConfigException("config.secrets.settings.caFile " + caFile + " holds no certificate");
            }
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            int i = 0;
            for (Certificate c : certs) {
                ks.setCertificateEntry("ca-" + i++, c);
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);
            return ctx;
        } catch (GeneralSecurityException e) {
            throw new ConfigException("config.secrets.settings.caFile " + caFile + " is not a PEM certificate bundle", e);
        }
    }

    /** Key names only, for diagnostics; never the values. */
    public java.util.Set<String> names() {
        return values.keySet();
    }

    @Override
    public String apply(String name) {
        String v = name == null ? null : values.get(name);
        return v == null || v.isEmpty() ? null : v;
    }
}
