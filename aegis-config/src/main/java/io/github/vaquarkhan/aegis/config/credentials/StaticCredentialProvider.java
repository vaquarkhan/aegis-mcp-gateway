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
package io.github.vaquarkhan.aegis.config.credentials;

import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.spi.CredentialProvider;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Service credentials per source, resolved from environment variables when the gateway starts
 * (secrets never appear in the configuration file itself). The gateway presents its OWN service
 * identity to the backend and forwards the governed caller only as audit context; the caller's
 * token is never passed through (MCP security best practice: no token passthrough).
 *
 * <p>Per-caller delegation (for example RFC 8693 token exchange through an identity provider or
 * Apache Knox) is a different {@link CredentialProvider} implementation; this one does not claim it.
 */
public final class StaticCredentialProvider implements CredentialProvider {

    /** Credential kinds a source may declare. */
    public enum Kind { NONE, BEARER, BASIC, HEADER }

    public record Spec(Kind kind, String header, String value, String username, String password) {
        @Override
        public String toString() {
            return "Spec[kind=" + kind + ", header=" + header + "]"; // never print secrets
        }
    }

    private final Map<String, Map<String, String>> headersBySource;

    public StaticCredentialProvider(Map<String, Spec> specs) {
        Map<String, Map<String, String>> m = new LinkedHashMap<>();
        for (Map.Entry<String, Spec> e : specs.entrySet()) {
            m.put(e.getKey(), headersFor(e.getValue()));
        }
        this.headersBySource = Map.copyOf(m);
    }

    public static Map<String, String> headersFor(Spec s) {
        return switch (s.kind()) {
            case NONE -> Map.of();
            case BEARER -> Map.of("Authorization", "Bearer " + s.value());
            case BASIC -> Map.of("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString((s.username() + ":" + s.password()).getBytes(StandardCharsets.UTF_8)));
            case HEADER -> Map.of(s.header(), s.value());
        };
    }

    @Override
    public Map<String, String> credentialsFor(String sourceId, CallerIdentity caller) {
        Map<String, String> h = headersBySource.get(sourceId);
        return h == null ? Map.of() : h;
    }

    @Override
    public String toString() {
        return "StaticCredentialProvider" + headersBySource.keySet();
    }
}
