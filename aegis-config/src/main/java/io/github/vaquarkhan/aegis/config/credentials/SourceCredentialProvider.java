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
import java.util.Map;

/**
 * Each source's credentials as configured: a token exchange for sources that declare
 * {@code credentials.type: token-exchange}, the static service credential for every other source.
 */
public final class SourceCredentialProvider implements CredentialProvider {

    private final StaticCredentialProvider statics;
    private final Map<String, TokenExchangeCredentials> exchanges;

    public SourceCredentialProvider(StaticCredentialProvider statics, Map<String, TokenExchangeCredentials> exchanges) {
        this.statics = statics;
        this.exchanges = Map.copyOf(exchanges);
    }

    @Override
    public Map<String, String> credentialsFor(String sourceId, CallerIdentity caller) throws Exception {
        TokenExchangeCredentials x = exchanges.get(sourceId);
        return x != null ? x.headers(caller) : statics.credentialsFor(sourceId, caller);
    }

    @Override
    public String toString() {
        return "SourceCredentialProvider" + exchanges.keySet();
    }
}
