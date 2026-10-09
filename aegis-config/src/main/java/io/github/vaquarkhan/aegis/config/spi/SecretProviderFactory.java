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
package io.github.vaquarkhan.aegis.config.spi;

import io.github.vaquarkhan.aegis.config.BuildContext;
import io.github.vaquarkhan.aegis.config.ConfigNode;
import java.net.URI;
import java.util.function.Function;

/**
 * Where the secrets that configuration refers to by name ({@code keyEnv}, {@code tokenEnv},
 * {@code passwordEnv}, {@code usernameEnv} and the like) come from. Selected once for the whole
 * gateway by {@code secrets.source}; built in are {@code env} (the default), {@code file} and
 * {@code vault}. Others are discovered through {@link java.util.ServiceLoader} in
 * {@code META-INF/services/io.github.vaquarkhan.aegis.config.spi.SecretProviderFactory}.
 *
 * <p>A provider only answers "what is the value of this name". It cannot change policy, and a name
 * it does not know must resolve to {@code null} so the referring setting fails closed as "not set".
 */
public interface SecretProviderFactory {

    /** Configuration value, for example {@code aws-secrets-manager}. Must not be env, file or vault. */
    String type();

    /**
     * @param settings    strict provider-specific {@code secrets.settings}; all supported keys must be consumed
     * @param context     build environment; {@code context.env()} is the process environment, for the
     *                    provider's own bootstrap credential
     * @param egressCheck returns null when a URI is allowed by {@code config.egress}, otherwise the reason;
     *                    a provider that makes network calls must check every destination with it
     * @return name to value; {@code null} for an unknown name
     */
    Function<String, String> create(ConfigNode settings, BuildContext context, Function<URI, String> egressCheck)
            throws Exception;
}
