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
package io.github.vaquarkhan.aegis.state.derby;

import io.github.vaquarkhan.aegis.config.BuildContext;
import io.github.vaquarkhan.aegis.config.ConfigException;
import io.github.vaquarkhan.aegis.config.ConfigNode;
import io.github.vaquarkhan.aegis.config.spi.StateStoreFactory;
import io.github.vaquarkhan.aegis.engine.spi.StateStore;
import java.nio.file.Path;

/** {@code state.type: derby-local}, a no-server-install durable store for one Aegis process. */
public final class DerbyStateStoreFactory implements StateStoreFactory {

    @Override
    public String type() {
        return "derby-local";
    }

    @Override
    public StateStore create(ConfigNode settings, BuildContext context) throws Exception {
        String pathText = settings.requiredString("path");
        Path base = context.baseDir().toAbsolutePath().normalize();
        Path path = base.resolve(pathText).normalize();
        if (!path.startsWith(base)) {
            throw new ConfigException(settings.path() + ".path must stay under the configuration directory");
        }
        return new DerbyStateStore(path, context.clock());
    }
}
