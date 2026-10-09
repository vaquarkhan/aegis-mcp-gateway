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

import io.github.vaquarkhan.aegis.config.BuildContext;
import io.github.vaquarkhan.aegis.config.ConfigNode;
import io.github.vaquarkhan.aegis.config.spi.SecretProviderFactory;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.util.Properties;
import java.util.function.Function;

/**
 * A small third-party style provider registered through {@code META-INF/services}: {@code secrets.source:
 * properties-file} reads a Java properties file. It exists to exercise the SPI the way an external jar would.
 */
public final class PropertiesFileSecretProviderFactory implements SecretProviderFactory {

    @Override
    public String type() {
        return "properties-file";
    }

    @Override
    public Function<String, String> create(ConfigNode settings, BuildContext context, Function<URI, String> egressCheck)
            throws Exception {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(context.baseDir().resolve(settings.requiredString("file")))) {
            p.load(in);
        }
        return p::getProperty;
    }
}
