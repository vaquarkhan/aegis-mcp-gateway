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
package io.github.vaquarkhan.aegis.engine.spi;

import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import java.util.Map;

/**
 * Resolves the credentials the gateway presents to a backend. Called only AFTER the call passed
 * every control and the intent was audited (0.1 resolved credentials before the chain ran and
 * fell back to passthrough on failure). A failure here denies the call; there is no fallback.
 *
 * <p>The caller's own token is not part of {@link CallerIdentity}. A token-exchange implementation
 * (RFC 8693, {@code credentials.type: token-exchange}) reads the verified inbound token from
 * {@link SubjectToken} for the duration of the call and sends it only to the identity provider, which
 * returns a backend-audience token for the same user; the inbound token is never forwarded.
 */
@FunctionalInterface
public interface CredentialProvider {

    CredentialProvider NONE = (sourceId, caller) -> Map.of();

    /** @return HTTP headers to attach for this source (may be empty) */
    Map<String, String> credentialsFor(String sourceId, CallerIdentity caller) throws Exception;
}
